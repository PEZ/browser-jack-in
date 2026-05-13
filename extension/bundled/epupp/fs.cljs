(ns epupp.fs
  "File system operations for managing userscripts from the REPL.

   All fs operations require an active REPL connection and FS REPL Sync
   to be enabled in settings.

   Use :fs/force? to overwrite an existing normal script with save! or mv!.
   Forced rename keeps the source script identity and still rejects
   built-in targets.")

(defonce ^:private !request-id (atom 0))

(defn- next-request-id []
  (swap! !request-id inc))

(defn- send-and-receive
  "Helper: send message to bridge and return promise of response."
  [msg-type payload response-type]
  (let [req-id (next-request-id)]
    (js/Promise.
      (fn [resolve _reject]
        (letfn [(handler [e]
                  (when (= (.-source e) js/window)
                    (let [msg (.-data e)]
                      (when (and msg
                                 (= "epupp-bridge" (.-source msg))
                                 (= response-type (.-type msg))
                                 (= req-id (.-requestId msg)))
                        (.removeEventListener js/window "message" handler)
                        (resolve msg)))))]
          (.addEventListener js/window "message" handler)
          (.postMessage js/window
            (clj->js (assoc payload :source "epupp-page" :type msg-type :requestId req-id))
            "*"))))))

(defn- ensure-success!
  "Throw when response indicates failure, otherwise pass through msg."
  [msg]
  (if (.-success msg)
    msg
    (throw (js/Error. (or (.-error msg) "Unknown error")))))

(def ^:private fs-base-keys
  [:fs/name
   :fs/modified
   :fs/created
   :fs/auto-run-match
   :fs/enabled?
   :fs/description
   :fs/run-at
   :fs/inject])

(def ^:private fs-save-keys
  (conj fs-base-keys :fs/newly-created? :fs/unchanged?))

(def ^:private fs-mv-keys
  (conj fs-base-keys :fs/from-name :fs/to-name))

(def ^:private fs-rm-keys
  (conj fs-base-keys :fs/existed?))

(defn- msg->fs-response
  "Filter response message to the public fs API keys."
  [msg allowed-keys]
  (let [m (js->clj msg :keywordize-keys true)]
    (-> (select-keys m allowed-keys)
        (assoc :fs/success (:success m)))))

(defn ^:async show
  "Get script code by name(s). Returns promise.
   Single name: returns code string or nil
   Vector of names: returns map of name->code (nil for missing)

   Examples: (epupp.fs/show \"my-script.cljs\")
             (epupp.fs/show [\"script1.cljs\" \"script2.cljs\"])"
  [name-or-names]
  (if (vector? name-or-names)
    ;; Bulk mode: fetch all and return map
    (let [results (await (js/Promise.all
                           (to-array
                             (map (^:async fn [n]
                                    (let [msg (await (send-and-receive "get-script" {:name n} "get-script-response"))]
                                      [n (when (.-success msg) (.-code msg))]))
                                  name-or-names))))]
      (into {} results))
    ;; Single mode: return code or nil
    (let [msg (await (send-and-receive "get-script" {:name name-or-names} "get-script-response"))]
      (when (.-success msg) (.-code msg)))))

(defn ^:async ls
  "List all scripts. Returns promise of vector with script info.
   Each script has :fs/name, :fs/modified, :fs/created, :fs/auto-run-match keys.
   Scripts with auto-run patterns also have :fs/enabled?.
   Optional keys: :fs/description, :fs/run-at, :fs/inject.

   Opts:
   - :fs/ls-hidden? bool - include built-in scripts (default: false)

   Example: (epupp.fs/ls)"
  ([] (ls {}))
  ([opts]
   (let [ls-hidden? (get opts :fs/ls-hidden?)
         msg (await (send-and-receive "list-scripts" {:lsHidden ls-hidden?} "list-scripts-response"))]
     (if (.-success msg)
       (js->clj (.-scripts msg) :keywordize-keys true)
       []))))

(defn ^:async save!
  "Save code to Epupp. Parses manifest from code.
   Requires FS REPL Sync to be enabled in settings.

   Returns promise of base script info; no-op saves may also include
   :fs/unchanged? true.
   Throws on failure.

   Opts:
   - :fs/enabled? bool - script enabled state (default: true)
   - :fs/force?  bool - overwrite existing script with same name (default: false)

   Examples:
   (epupp.fs/save! code)
   (epupp.fs/save! code {:fs/enabled? false})
   (epupp.fs/save! code {:fs/force? true})  ; overwrite existing
   (epupp.fs/save! [code1 code2])           ; bulk save"
  ([code-or-codes] (save! code-or-codes {}))
  ([code-or-codes opts]
   (let [force? (get opts :fs/force?)
         enabled (get opts :fs/enabled? true)]
     (if (vector? code-or-codes)
       ;; Bulk mode - use map-indexed (realized by to-array for Promise.all)
       (let [bulk-id (str (.now js/Date) "-" (.random js/Math))
             results (await (js/Promise.all
                             (to-array
                              (map-indexed (^:async fn [idx code]
                                             (let [msg (await (send-and-receive "save-script" {:code code
                                                                                               :enabled enabled
                                                                                               :force force?
                                                                                               :bulk-id bulk-id
                                                                                               :bulk-index idx
                                                                                               :bulk-count (count code-or-codes)}
                                                                                "save-script-response"))]
                                               (ensure-success! msg)
                                               [idx (msg->fs-response msg fs-save-keys)]))
                                           code-or-codes))))]
         (into {} results))
       ;; Single mode
       (let [msg (await (send-and-receive "save-script" {:code code-or-codes :enabled enabled :force force?} "save-script-response"))]
         (ensure-success! msg)
         (msg->fs-response msg fs-save-keys))))))

(defn ^:async mv!
  "Rename a script. Requires FS REPL Sync to be enabled in settings.

   Returns promise of base script info plus :fs/from-name.
   Throws on failure.

   Opts:
   - :fs/force? bool - overwrite an existing normal target while keeping the
     source script identity; built-in targets are still rejected

   Examples:
   (epupp.fs/mv! \"old.cljs\" \"new.cljs\")
   (epupp.fs/mv! \"old.cljs\" \"existing.cljs\" {:fs/force? true})  ; overwrite existing normal target"
  ([from-name to-name] (mv! from-name to-name {}))
  ([from-name to-name opts]
   (let [force? (get opts :fs/force?)
         msg (await (send-and-receive "rename-script" {:from from-name :to to-name :force force?} "rename-script-response"))]
     (ensure-success! msg)
     (msg->fs-response msg fs-mv-keys))))

(defn- not-found-error?
  "Returns true when a response message indicates the script was not found."
  [msg]
  (let [err (.-error msg)]
    (and err (or (.includes err "not found")
                 (.includes err "does not exist")
                 (.includes err "non-existent")))))

(defn- ^:async rm-single!
  "Delete a single script by name. Throws if not found or on other error."
  [name]
  (let [msg (await (send-and-receive "delete-script" {:name name} "delete-script-response"))]
    (cond
      (.-success msg)
      (assoc (msg->fs-response msg fs-rm-keys) :fs/existed? true)

      (not-found-error? msg)
      (throw (js/Error. (or (.-error msg) "Script not found")))

      :else
      (throw (js/Error. (or (.-error msg) "Unknown error"))))))

(defn- ^:async rm-bulk-one! [{:keys [bulk-id bulk-count idx name]}]
  (let [msg (await (send-and-receive "delete-script" {:name name
                                                      :bulk-id bulk-id
                                                      :bulk-index idx
                                                      :bulk-count bulk-count}
                                     "delete-script-response"))]
    (cond
      (.-success msg)
      [name (assoc (msg->fs-response msg fs-rm-keys) :fs/existed? true)]

      (not-found-error? msg)
      [name (assoc (msg->fs-response msg fs-rm-keys) :fs/existed? false)]

      :else
      (throw (js/Error. (or (.-error msg) "Unknown error"))))))

(defn- ^:async rm-bulk!
  "Delete multiple scripts by name. Throws if any are missing."
  [names]
  (let [bulk-id (str (.now js/Date) "-" (.random js/Math))
        bulk-count (count names)
        results (await (js/Promise.all
                        (to-array
                         (map-indexed (^:async fn [idx n]
                                        (await (rm-bulk-one! {:bulk-id bulk-id
                                                              :bulk-count bulk-count
                                                              :idx idx
                                                              :name n})))
                                      names))))
        missing (keep (fn [[n r]] (when (false? (:fs/existed? r)) n)) results)]
    (when (seq missing)
      (throw (js/Error. (str "Scripts not found: " (.join (to-array missing) ", ")))))
    (into {} results)))

(defn ^:async rm!
  "Delete script(s) by name. Requires FS REPL Sync to be enabled in settings.

   Returns promise of base script info of deleted script plus :fs/existed?.
   Throws on failure.

   Bulk mode returns map of name->base-info
   - Rejects when any script is missing

   Examples:
   (epupp.fs/rm! \"my-script.cljs\")
   (epupp.fs/rm! [\"script1.cljs\" \"script2.cljs\"])"
  [name-or-names]
  (if (vector? name-or-names)
    (await (rm-bulk! name-or-names))
    (await (rm-single! name-or-names))))
