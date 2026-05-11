(ns background-actions.fs-guard-actions
  (:require [background-utils :as bg-utils]
            [script-utils :as script-utils]))

(def ^:private fs-denied-error
  "FS Sync requires an active REPL connection and FS Sync enabled in settings")

(defn- fs-denied-response [send-response]
  {:uf/fxs [[:msg/fx.send-response send-response {:success false :error fs-denied-error}]]})

(defn- fs-denied-with-banner [send-response operation]
  {:uf/fxs [[:banner/fx.broadcast-system {:event-type "error"
                                          :operation operation
                                          :error fs-denied-error}]
            [:msg/fx.send-response send-response {:success false :error fs-denied-error}]]})

(defn- handle-guard-list-scripts [state args]
  (let [[tab-id send-response include-hidden?] args
        allowed? (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id)]
    (if allowed?
      {:uf/fxs [[:msg/fx.list-scripts send-response include-hidden?]]}
      (fs-denied-response send-response))))

(defn- handle-guard-get-script [state args]
  (let [[tab-id send-response script-name] args
        allowed? (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id)]
    (if allowed?
      {:uf/fxs [[:msg/fx.get-script send-response script-name]]}
      (fs-denied-response send-response))))

(defn- handle-guard-rename-script [state args]
  (let [[tab-id send-response from-name to-name force?] args
        allowed? (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id)
        name-error (when allowed? (script-utils/validate-script-name to-name))
        rename-action (cond-> [:fs/ax.rename-script from-name to-name]
                        force? (conj true))]
    (cond
      (not allowed?)
      (fs-denied-with-banner send-response "rename")

      name-error
      {:uf/fxs [[:msg/fx.send-response send-response {:success false :error name-error}]]}

      :else
      {:uf/fxs [[:fs/fx.dispatch-action send-response rename-action]]})))

(defn- handle-guard-delete-script [state args]
  (let [[tab-id send-response delete-params] args
        allowed? (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id)]
    (if allowed?
      {:uf/fxs [[:fs/fx.dispatch-action send-response [:fs/ax.delete-script delete-params]]]}
      (fs-denied-with-banner send-response "delete"))))

(defn- handle-guard-save-script [state args]
  (let [[tab-id send-response raw-data web-install?] args
        allowed? (or web-install?
                     (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id))]
    (if allowed?
      {:uf/fxs [[:fs/fx.parse-and-save send-response raw-data]]}
      (fs-denied-with-banner send-response "save"))))

(defn handle-action [state _uf-data [action & args]]
  (case action
    :fs/ax.guard-list-scripts (handle-guard-list-scripts state args)
    :fs/ax.guard-get-script (handle-guard-get-script state args)
    :fs/ax.guard-rename-script (handle-guard-rename-script state args)
    :fs/ax.guard-delete-script (handle-guard-delete-script state args)
    :fs/ax.guard-save-script (handle-guard-save-script state args)))
