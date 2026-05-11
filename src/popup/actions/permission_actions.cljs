(ns popup.actions.permission-actions)

(defn request-host-permission [state]
  {:uf/fxs [[:popup/fx.request-host-permission (:scripts/current-tab-id state)]]})

(defn check-host-permission []
  {:uf/fxs [[:popup/fx.check-host-permission]]})
