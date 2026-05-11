(ns popup.actions.permission-actions)

(defn handle-action [state _uf-data [action & _args]]
  (case action
    :permission/ax.check-host-permission {:uf/fxs [[:popup/fx.check-host-permission]]}
    :permission/ax.request-host-permission {:uf/fxs [[:popup/fx.request-host-permission (:scripts/current-tab-id state)]]}
    :uf/unhandled-ax))
