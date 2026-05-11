(ns popup.actions.runtime-actions)

(defn handle-runtime-status [state tab-id errors current-tab-id]
  (when (= tab-id current-tab-id)
    {:uf/db (assoc state :runtime/errors (or errors {}))}))

(defn load-runtime-status [current-tab-id]
  {:uf/fxs [[:popup/fx.load-runtime-status current-tab-id]]})

(defn handle-action [state _uf-data [action & args]]
  (case action
    :runtime-status/ax.handle-runtime-status
    (let [[{:keys [tab-id errors]}] args]
      (handle-runtime-status state tab-id errors (:scripts/current-tab-id state)))
    :runtime-status/ax.load-runtime-status (load-runtime-status (:scripts/current-tab-id state))
    :uf/unhandled-ax))
