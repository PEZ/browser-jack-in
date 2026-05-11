(ns popup.actions.ui-actions)

(defn toggle-section [state section-id]
  {:uf/db (update-in state [:ui/sections-collapsed section-id] not)})

(defn toggle-creator-menu [state]
  {:uf/db (update state :ui/creator-menu-open? not)})

(defn close-creator-menu [state]
  {:uf/db (assoc state :ui/creator-menu-open? false)})

(defn mark-scripts-modified [state script-names]
  (let [new-modified (into (or (:ui/recently-modified-scripts state) #{}) script-names)]
    {:uf/db (assoc state :ui/recently-modified-scripts new-modified)
     :uf/fxs [[:uf/fx.defer-dispatch [[:ui/ax.clear-modified-scripts]] 2000]]}))

(defn clear-modified-scripts [state]
  {:uf/db (assoc state :ui/recently-modified-scripts #{})})

(defn set-brave-detected [state brave?]
  {:uf/db (assoc state :browser/brave? brave?)})

(defn dump-dev-log []
  {:uf/fxs [[:popup/fx.dump-dev-log]]})

(defn reveal-tab [tab-id]
  {:uf/fxs [[:popup/fx.reveal-tab tab-id]]})

(defn disconnect-tab [tab-id]
  {:uf/fxs [[:popup/fx.disconnect-tab tab-id]]})

(defn check-page-scriptability []
  {:uf/fxs [[:popup/fx.check-page-scriptability]]})

(defn handle-action [state _uf-data [action & args]]
  (case action
    :ui/ax.toggle-section (toggle-section state (first args))
    :ui/ax.toggle-creator-menu (toggle-creator-menu state)
    :ui/ax.close-creator-menu (close-creator-menu state)
    :ui/ax.dump-dev-log (dump-dev-log)
    :ui/ax.reveal-tab (reveal-tab (first args))
    :ui/ax.disconnect-tab (disconnect-tab (first args))
    :ui/ax.mark-scripts-modified (mark-scripts-modified state (first args))
    :ui/ax.clear-modified-scripts (clear-modified-scripts state)
    :ui/ax.set-brave-detected (set-brave-detected state (first args))
    :ui/ax.check-page-scriptability (check-page-scriptability)
    :uf/unhandled-ax))
