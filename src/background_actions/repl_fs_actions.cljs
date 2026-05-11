(ns background-actions.repl-fs-actions
  "Action implementations for background REPL FS operations.
   No Chrome APIs, no atoms, no side effects - just state transitions."
  (:require [script-utils :as script-utils]
            [manifest-parser :as mp]))

;; ============================================================
;; Helper Functions (Pure)
;; ============================================================

(defn find-script-by-name
  "Find a script by name in the scripts list."
  [scripts name]
  (->> scripts
       (filter #(= (:script/name %) name))
       first))

(defn find-script-by-id
  "Find a script by ID in the scripts list."
  [scripts id]
  (->> scripts
       (filter #(= (:script/id %) id))
       first))

(defn- make-error-response
  "Create a failure response with error message and broadcast error event."
  ([error-msg]
   (make-error-response error-msg {}))
  ([error-msg {:keys [operation script-name event-data]}]
   {:uf/fxs [[:bg/fx.broadcast-system-banner! (merge {:event-type "error"
                                                 :operation operation
                                                 :script-name script-name
                                                 :error error-msg}
                                                event-data)]
             [:bg/fx.send-response {:success false :error error-msg}]]}))

(defn- make-success-response
  "Create a success response with optional extra data and broadcast success event."
  ([updated-scripts operation script-name]
   (make-success-response updated-scripts operation script-name {}))
  ([updated-scripts operation script-name {:keys [event-data response-data] :as extra}]
   (let [response-data (or response-data (dissoc extra :event-data))]
     {:uf/db {:storage/scripts updated-scripts}
      :uf/fxs [[:storage/fx.persist!]
               [:bg/fx.broadcast-system-banner! (merge {:event-type "success"
                                                   :operation operation
                                                   :script-name script-name}
                                                  event-data)]
               [:bg/fx.send-response (merge {:success true} response-data)]]})))

(defn- make-info-response
  "Create an info response (no storage change) with broadcast info event."
  [operation script-name {:keys [event-data response-data]}]
  {:uf/fxs [[:bg/fx.broadcast-system-banner! (merge {:event-type "info"
                                                     :operation operation
                                                     :script-name script-name}
                                                    event-data)]
            [:bg/fx.send-response (merge {:success true} response-data)]]})

(defn- update-script-in-list
  "Update a script in the list by ID, applying update-fn."
  [scripts script-id update-fn]
  (mapv (fn [s]
          (if (= (:script/id s) script-id)
            (update-fn s)
            s))
        scripts))

(defn- remove-script-from-list
  "Remove a script from the list by ID."
  [scripts script-id]
  (filterv #(not= (:script/id %) script-id) scripts))

;; ============================================================
;; Action Implementations
;; ============================================================



(defn script->base-info
  "Build consistent base info map from script record.
   Returns normalized return shape for all epupp.fs operations.
   When script has no auto-run patterns, omits :fs/auto-run-match and :fs/enabled? keys."
  [script]
  (let [match (:script/match script)
        has-auto-run? (and match (seq match))]
    (cond-> {:fs/name (:script/name script)
             :fs/modified (:script/modified script)
             :fs/created (:script/created script)}
      has-auto-run?
      (assoc :fs/auto-run-match match
             :fs/enabled? (:script/enabled script))

      (seq (:script/description script))
      (assoc :fs/description (:script/description script))

      (:script/run-at script)
      (assoc :fs/run-at (:script/run-at script))

      (seq (:script/inject script))
      (assoc :fs/inject (:script/inject script)))))

(defn- update-renamed-script [script normalized-to-name now-iso]
  (let [existing-code (:script/code script)
        manifest (when existing-code
                   (try (mp/extract-manifest existing-code)
                        (catch :default _ nil)))
        has-script-name? (and manifest (get manifest "script-name"))
        updated-code (if has-script-name?
                       (mp/update-manifest-script-name existing-code normalized-to-name)
                       existing-code)]
    (cond-> (assoc script :script/name normalized-to-name :script/modified now-iso)
      has-script-name? (assoc :script/code updated-code))))

(defn- validate-rename [{:keys [name-error source-script target-script same-script-target? force? from-name normalized-to-name]}]
  (let [source-builtin? (and source-script (script-utils/builtin-script? source-script))
        target-force-builtin? (and force? target-script (script-utils/builtin-script? target-script))
        name-collision? (and target-script (or (not force?) same-script-target?))]
    (cond
      name-error
      (make-error-response name-error {:operation "rename" :script-name normalized-to-name})

      (nil? source-script)
      (make-error-response (str "Script not found: " from-name) {:operation "rename" :script-name from-name})

      source-builtin?
      (make-error-response "Cannot rename built-in scripts" {:operation "rename" :script-name from-name})

      target-force-builtin?
      (make-error-response "Cannot overwrite built-in scripts" {:operation "rename" :script-name normalized-to-name})

      name-collision?
      (make-error-response (str "Script already exists: " normalized-to-name) {:operation "rename" :script-name from-name}))))

(defn- execute-rename [{:keys [scripts source-script target-script normalized-to-name now-iso force? from-name]}]
  (let [scripts-to-update (if (and force? target-script)
                            (remove-script-from-list scripts (:script/id target-script))
                            scripts)
        updated-scripts (update-script-in-list
                         scripts-to-update
                         (:script/id source-script)
                         #(update-renamed-script % normalized-to-name now-iso))
        renamed-script (find-script-by-name updated-scripts normalized-to-name)]
    (make-success-response updated-scripts "rename" normalized-to-name
                           {:event-data {:script-id (:script/id source-script)
                                         :from-name from-name
                                         :to-name normalized-to-name}
                            :response-data (merge (script->base-info renamed-script)
                                                  {:fs/from-name from-name
                                                   :fs/to-name normalized-to-name})})))

(defn rename-script
  "Rename a script by name.
   With :fs/force? true, replaces an existing normal target while
   preserving the source script's identity."
  [state {:fs/keys [now-iso from-name to-name force?]}]
  (let [scripts (:storage/scripts state)
        name-error (script-utils/validate-script-name to-name)
        normalized-to-name (when to-name (script-utils/normalize-script-name to-name))
        source-script (find-script-by-name scripts from-name)
        target-script (find-script-by-name scripts normalized-to-name)
        same-script-target? (and source-script target-script
                                 (= (:script/id source-script) (:script/id target-script)))
        ctx {:name-error name-error :source-script source-script :target-script target-script
             :same-script-target? same-script-target? :force? force?
             :from-name from-name :normalized-to-name normalized-to-name
             :scripts scripts :now-iso now-iso}]
    (or (validate-rename ctx) (execute-rename ctx))))

(defn delete-script
  "Delete a script by name."
  [state {:fs/keys [script-name bulk-id bulk-index bulk-count]}]
  (let [scripts (:storage/scripts state)
        script (find-script-by-name scripts script-name)
        bulk-event-data (cond-> {}
                          (some? bulk-id) (assoc :bulk-id bulk-id)
                          (some? bulk-index) (assoc :bulk-index bulk-index)
                          (some? bulk-count) (assoc :bulk-count bulk-count))]
    (cond
      ;; Script not found
      (nil? script)
      (make-error-response (str "Not deleting non-existent file: " script-name)
                           {:operation "delete"
                            :script-name script-name
                            :event-data bulk-event-data})

      ;; Script is builtin
      (script-utils/builtin-script? script)
      (make-error-response "Cannot delete built-in scripts"
                           {:operation "delete"
                            :script-name script-name
                            :event-data bulk-event-data})

      ;; All checks pass - allow delete
      :else
      (let [updated-scripts (remove-script-from-list scripts (:script/id script))]
        (make-success-response updated-scripts "delete" script-name
                               {:event-data (merge {:script-id (:script/id script)}
                                                   bulk-event-data)
                                :response-data (script->base-info script)})))))

(defn- resolve-script-id [{:keys [force? existing-by-name existing-by-id incoming-id]}]
  (let [force-overwrite? (and force? existing-by-name (not existing-by-id))
        name-based-update? (and (nil? incoming-id) existing-by-name)]
    (cond
      force-overwrite? (:script/id existing-by-name)
      name-based-update? (:script/id existing-by-name)
      incoming-id incoming-id
      :else (script-utils/generate-script-id))))

(defn- build-bulk-event-data [{:keys [bulk-id bulk-index bulk-count]}]
  (cond-> {}
    (some? bulk-id) (assoc :bulk-id bulk-id)
    (some? bulk-index) (assoc :bulk-index bulk-index)
    (some? bulk-count) (assoc :bulk-count bulk-count)))

(defn- compute-updated-scripts [{:keys [scripts script-id timestamped-script is-update? force? existing-by-name]}]
  (if is-update?
    (update-script-in-list scripts script-id (constantly timestamped-script))
    (let [filtered (if (and force? existing-by-name)
                     (remove-script-from-list scripts (:script/id existing-by-name))
                     scripts)]
      (conj filtered timestamped-script))))

(defn- validate-save [{:keys [name-error is-update? existing-by-id existing-by-name force? script-name raw-name scripts script]}]
  (let [update-builtin? (and is-update? existing-by-id (script-utils/builtin-script? existing-by-id))
        overwrite-builtin? (and existing-by-name (script-utils/builtin-script? existing-by-name))
        shadow-builtin? (script-utils/name-matches-builtin? scripts script-name)
        name-collision? (and (not is-update?) existing-by-name (not force?))
        content-unchanged? (and is-update? (= (:script/code existing-by-id) (:script/code script)))]
    (cond
      name-error
      (make-error-response name-error {:operation "save" :script-name raw-name})

      update-builtin?
      (make-error-response "Cannot modify built-in scripts" {:operation "save" :script-name script-name})

      (or overwrite-builtin? shadow-builtin?)
      (make-error-response "Cannot overwrite built-in scripts" {:operation "save" :script-name script-name})

      name-collision?
      (make-error-response (str "Script already exists: " script-name) {:operation "save" :script-name script-name})

      content-unchanged?
      (make-info-response "save" script-name
                          {:event-data (merge {:script-id (:script/id script) :unchanged true}
                                              (build-bulk-event-data script))
                           :response-data (merge (script->base-info existing-by-id)
                                                 {:fs/unchanged? true})}))))

(defn- execute-save [{:keys [scripts script manifest is-update? force? existing-by-id existing-by-name now-iso script-name raw-name]}]
  (let [clean-script (dissoc script :script/force? :script/bulk-id :script/bulk-index :script/bulk-count)
        result (script-utils/normalize-and-merge-script clean-script existing-by-id manifest {:now-iso now-iso})
        timestamped-script (:script result)]
    (if (:error result)
      (make-error-response (:error result) {:operation "save" :script-name raw-name})
      (let [updated-scripts (compute-updated-scripts {:scripts scripts
                                                      :script-id (:script/id script)
                                                      :timestamped-script timestamped-script
                                                      :is-update? is-update?
                                                      :force? force?
                                                      :existing-by-name existing-by-name})]
        (make-success-response updated-scripts "save" script-name
                               {:event-data (merge {:script-id (:script/id script)
                                                    :created (not is-update?)}
                                                   (build-bulk-event-data script))
                                :response-data (script->base-info timestamped-script)})))))

(defn- parse-save-manifest [code]
  (when code
    (try (mp/extract-manifest code)
         (catch :default _ nil))))

(defn- compute-raw-name [manifest script]
  (or (when manifest (aget manifest "raw-script-name"))
      (:script/name script)))

(defn- compute-is-update? [existing-by-id existing-by-name force? incoming-id]
  (or (some? existing-by-id)
      (and force? (some? existing-by-name))
      (and (nil? incoming-id) (some? existing-by-name))))

(defn- find-effective-existing [existing-by-id existing-by-name force? incoming-id]
  (or existing-by-id
      (when (and force? existing-by-name) existing-by-name)
      (when (and (nil? incoming-id) existing-by-name) existing-by-name)))

(defn save-script
  "Create or update a script. When force-overwriting by name, preserves the
   existing script's ID to ensure stable identity."
  [state {:fs/keys [now-iso script]}]
  (let [scripts (:storage/scripts state)
        manifest (parse-save-manifest (:script/code script))
        raw-name (compute-raw-name manifest script)
        normalized-name (when raw-name (script-utils/normalize-script-name raw-name))
        name-error (script-utils/validate-script-name normalized-name)
        script (assoc script :script/name normalized-name)
        incoming-id (:script/id script)
        force? (:script/force? script)
        existing-by-id (when incoming-id (find-script-by-id scripts incoming-id))
        existing-by-name (find-script-by-name scripts (:script/name script))
        is-update? (compute-is-update? existing-by-id existing-by-name force? incoming-id)
        script-id (resolve-script-id {:force? force? :existing-by-name existing-by-name
                                      :existing-by-id existing-by-id :incoming-id incoming-id})
        source (or (:script/source script) :source/repl)
        script (assoc script :script/id script-id :script/source source)
        existing-by-id (find-effective-existing existing-by-id existing-by-name force? incoming-id)
        ctx {:scripts scripts :script script :manifest manifest
             :raw-name raw-name :name-error name-error
             :force? force? :is-update? is-update?
             :existing-by-id existing-by-id :existing-by-name existing-by-name
             :script-name (:script/name script) :now-iso now-iso}]
    (or (validate-save ctx) (execute-save ctx))))
