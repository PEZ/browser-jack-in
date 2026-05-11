(ns popup.actions.ui-actions)

(defn mark-scripts-modified [state script-names]
  (let [new-modified (into (or (:ui/recently-modified-scripts state) #{}) script-names)]
    {:uf/db (assoc state :ui/recently-modified-scripts new-modified)
     :uf/fxs [[:uf/fx.defer-dispatch [[:ui/ax.clear-modified-scripts]] 2000]]}))

(defn handle-action [state _uf-data [action & args]]
  (case action
    :ui/ax.toggle-section {:uf/db (update-in state [:ui/sections-collapsed (first args)] not)}
    :ui/ax.toggle-creator-menu {:uf/db (update state :ui/creator-menu-open? not)}
    :ui/ax.close-creator-menu {:uf/db (assoc state :ui/creator-menu-open? false)}
    :ui/ax.dump-dev-log {:uf/fxs [[:popup/fx.dump-dev-log]]}
    :ui/ax.reveal-tab {:uf/fxs [[:popup/fx.reveal-tab (first args)]]}
    :ui/ax.disconnect-tab {:uf/fxs [[:popup/fx.disconnect-tab (first args)]]}
    :ui/ax.mark-scripts-modified (mark-scripts-modified state (first args))
    :ui/ax.clear-modified-scripts {:uf/db (assoc state :ui/recently-modified-scripts #{})}
    :ui/ax.set-brave-detected {:uf/db (assoc state :browser/brave? (first args))}
    :ui/ax.check-page-scriptability {:uf/fxs [[:popup/fx.check-page-scriptability]]}
    :uf/unhandled-ax))
