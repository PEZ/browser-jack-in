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
