(ns popup.actions.permission-actions)

(defn request-host-permission [state]
  {:uf/fxs [[:popup/fx.request-host-permission (:scripts/current-tab-id state)]]})

(defn check-host-permission []
  {:uf/fxs [[:popup/fx.check-host-permission]]})

(defn handle-action [state _uf-data [action & _args]]
  (case action
    :permission/ax.check-host-permission (check-host-permission)
    :permission/ax.request-host-permission (request-host-permission state)
    :uf/unhandled-ax))
