(ns popup.actions.runtime-actions)

(defn handle-runtime-status [state tab-id errors current-tab-id]
  (when (= tab-id current-tab-id)
    {:uf/db (assoc state :runtime/errors (or errors {}))}))

(defn load-runtime-status [current-tab-id]
  {:uf/fxs [[:popup/fx.load-runtime-status current-tab-id]]})
