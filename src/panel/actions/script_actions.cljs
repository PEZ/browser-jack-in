(ns panel.actions.script-actions
  "Pure action handlers for panel script CRUD operations."
  (:require [manifest-parser :as mp]
            [script-utils :as script-utils]))

(defn- build-manifest-hints
  "Build hints map from manifest for UI display.
   Copies values directly from manifest parser output."
  [manifest]
  (when manifest
    {:name-normalized? (get manifest "name-normalized?")
     :raw-script-name (get manifest "raw-script-name")
     :unknown-keys (get manifest "unknown-keys")
     :run-at-invalid? (get manifest "run-at-invalid?")
     :raw-run-at (get manifest "raw-run-at")
     :run-at (get manifest "run-at")
     :inject (get manifest "inject")}))

(defn- build-manifest-dxs
  "Build deferred dispatch actions from manifest data.
   When manifest is present, it's the source of truth. If
   epupp/auto-run-match is absent from found-keys, we explicitly clear
   the match field to implement auto-run revocation."
  [manifest]
  (when manifest
    (let [script-name (get manifest "script-name")
          auto-run-match (get manifest "auto-run-match")
          description (get manifest "description")
          found-keys (get manifest "found-keys")
          has-auto-run-key? (some #(= % "epupp/auto-run-match") found-keys)]
      (cond-> []
        script-name (conj [:editor/ax.set-script-name script-name])
        has-auto-run-key? (conj [:editor/ax.set-script-match auto-run-match])
        (and (not has-auto-run-key?) manifest) (conj [:editor/ax.set-script-match nil])
        description (conj [:editor/ax.set-script-description description])))))

(defn- build-save-script
  "Build script map for save/overwrite operations."
  [{:panel/keys [code script-name script-match script-description script-id manifest-hints]}
   {:keys [force? include-id?]}]
  (let [normalized-match (script-utils/normalize-match-patterns script-match)
        script-inject (:inject manifest-hints)
        script-run-at (:run-at manifest-hints)]
    (cond-> {:script/name script-name
             :script/match normalized-match
             :script/code code
             :script/source :source/panel}
      force? (assoc :script/force? true)
      (seq script-description) (assoc :script/description script-description)
      (seq script-inject) (assoc :script/inject script-inject)
      script-run-at (assoc :script/run-at script-run-at)
      (and include-id? script-id) (assoc :script/id script-id))))

(defn default-script
  "Generate default script for panel initialization.
   Uses the current hostname for the auto-run-match pattern.
   Falls back to example.com if hostname is unknown or missing."
  [hostname]
  (let [effective-host (if (or (nil? hostname) (= hostname "unknown"))
                         "example.com"
                         hostname)]
    (str "{:epupp/script-name \"hello_world.cljs\"
 :epupp/auto-run-match \"https://" effective-host "/*\"
 :epupp/description \"A script saying hello\"}

(ns hello-world)

(defn hello [s]
  (js/console.log \"Epupp: Hello\" (str s \"!\")))

(hello \"World\")")))

(defn- parse-manifest [code]
  (try (mp/extract-manifest code) (catch :default _ nil)))

(defn- missing-required? [{:panel/keys [code script-name]}]
  (or (empty? code) (empty? script-name)))

(defn- compute-save-context [state]
  (let [{:panel/keys [script-name original-name]} state
        normalized-name (script-utils/normalize-script-name script-name)
        name-changed? (and original-name (not= normalized-name original-name))
        existing-unchanged? (and original-name (not name-changed?))]
    {:normalized-name normalized-name
     :existing-unchanged? existing-unchanged?}))

(defn- handle-save-script [state]
  (if (missing-required? state)
    {:uf/dxs [[:editor/ax.show-system-banner "error" "Name and code are required"]]}
    (let [{:keys [normalized-name existing-unchanged?]} (compute-save-context state)
          script (build-save-script state {:include-id? existing-unchanged?})
          action-text (if existing-unchanged? "Saved" "Created")]
      {:uf/fxs [[:editor/fx.save-script script normalized-name action-text]]})))

(defn- handle-save-script-overwrite [state]
  (if (missing-required? state)
    {:uf/dxs [[:editor/ax.show-system-banner "error" "Name and code are required"]]}
    (let [normalized-name (script-utils/normalize-script-name (:panel/script-name state))
          script (build-save-script state {:force? true})]
      {:uf/fxs [[:editor/fx.save-script script normalized-name "Replaced"]]})))

(defn- handle-save-response [state args]
  (let [[{:keys [success error name action-text unchanged]}] args]
    (cond
      (not success)
      {:uf/dxs [[:editor/ax.show-system-banner "error" (or error "Save failed")]]}
      unchanged
      {:uf/db (assoc state :panel/script-name name :panel/original-name name)
       :uf/dxs [[:editor/ax.show-system-banner "info" (str "Script \"" name "\" unchanged")]]}
      :else
      {:uf/db (assoc state :panel/script-name name :panel/original-name name)
       :uf/dxs [[:editor/ax.show-system-banner "success" (str action-text " \"" name "\"")]]})))

(defn- handle-rename-script [state]
  (if-let [original-name (:panel/original-name state)]
    (let [{:panel/keys [script-name]} state]
      (if (= script-name original-name)
        {:uf/dxs [[:editor/ax.show-system-banner "error" "Name unchanged"]]}
        (let [normalized-name (script-utils/normalize-script-name script-name)
              script (build-save-script state {:include-id? true})]
          {:uf/fxs [[:editor/fx.save-script script normalized-name "Renamed"]]})))
    {:uf/dxs [[:editor/ax.show-system-banner "error" "Cannot rename: no script loaded"]]}))

(defn- handle-rename-response [state args]
  (let [[{:keys [success error to-name]}] args]
    (if success
      {:uf/db (-> state
                  (assoc :panel/original-name to-name)
                  (assoc :panel/script-name to-name))
       :uf/fxs [[:editor/fx.persist-code (:panel/code state)]]
       :uf/dxs [[:editor/ax.show-system-banner "success" (str "Renamed to \"" to-name "\"")]]}
      {:uf/dxs [[:editor/ax.show-system-banner "error" (or error "Rename failed")]]})))

(defn- handle-new-script [state]
  (let [hostname (:panel/current-hostname state)
        script (default-script hostname)
        manifest (parse-manifest script)
        hints (build-manifest-hints manifest)
        dxs (build-manifest-dxs manifest)]
    {:uf/db (assoc state
                   :panel/code script
                   :panel/original-name nil
                   :panel/script-name ""
                   :panel/script-match ""
                   :panel/script-description ""
                   :panel/manifest-hints hints)
     :uf/fxs [[:editor/fx.clear-persisted-state (:panel/current-hostname state)]]
     :uf/dxs dxs}))

(defn- handle-set-code [state args]
  (let [[code] args
        manifest (parse-manifest code)
        hints (build-manifest-hints manifest)
        dxs (build-manifest-dxs manifest)
        new-state (assoc state :panel/code code :panel/manifest-hints hints)]
    (cond-> {:uf/db new-state}
      (seq dxs) (assoc :uf/dxs dxs))))

(defn- handle-initialize-editor [state args]
  (let [[{:keys [code hostname]}] args
        effective-code (if (seq code) code (default-script hostname))
        manifest (parse-manifest effective-code)
        hints (build-manifest-hints manifest)
        dxs (build-manifest-dxs manifest)
        manifest-name (get manifest "script-name")
        new-state (cond-> (assoc state
                                 :panel/code effective-code
                                 :panel/manifest-hints hints
                                 :panel/current-hostname hostname)
                    (and (seq code) (seq manifest-name)) (assoc :panel/original-name manifest-name))]
    (cond-> {:uf/db new-state}
      (seq dxs) (assoc :uf/dxs dxs))))

(defn handle-action [state _uf-data [action & args]]
  (case action
    :editor/ax.save-script (handle-save-script state)
    :editor/ax.save-script-overwrite (handle-save-script-overwrite state)
    :editor/ax.handle-save-response (handle-save-response state args)
    :editor/ax.rename-script (handle-rename-script state)
    :editor/ax.handle-rename-response (handle-rename-response state args)
    :editor/ax.load-script-for-editing
    (let [[script-id name match code description] args
          hints (build-manifest-hints (parse-manifest code))]
      {:uf/db (assoc state
                     :panel/script-id script-id
                     :panel/script-name name
                     :panel/original-name name
                     :panel/script-match match
                     :panel/code code
                     :panel/script-description (or description "")
                     :panel/manifest-hints hints)})
    :editor/ax.reload-script-from-storage
    (let [[script-name] args]
      {:uf/fxs [[:editor/fx.reload-script-from-storage script-name]]})
    :editor/ax.new-script (handle-new-script state)
    :editor/ax.check-editing-script {:uf/fxs [[:editor/fx.check-editing-script]]}
    :editor/ax.set-code (handle-set-code state args)
    :editor/ax.initialize-editor (handle-initialize-editor state args)))
