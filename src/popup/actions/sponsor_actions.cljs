(ns popup.actions.sponsor-actions)

(defn check-sponsor [state]
  (let [username (or (:sponsor/sponsored-username state) "PEZ")]
    {:uf/fxs [[:popup/fx.check-sponsor username]]}))

(defn handle-action [state _uf-data [action & args]]
  (case action
    :sponsor/ax.set-dev-sponsor-username {:uf/db (assoc state :sponsor/sponsored-username (first args))
                                          :uf/fxs [[:popup/fx.set-dev-sponsor-username (first args)]]}
    :sponsor/ax.reset-sponsor-status {:uf/db (assoc state :sponsor/status false :sponsor/checked-at nil)
                                      :uf/fxs [[:popup/fx.reset-sponsor-status]]}
    :sponsor/ax.load-dev-sponsor-username {:uf/fxs [[:popup/fx.load-dev-sponsor-username]]}
    :sponsor/ax.check-sponsor (check-sponsor state)
    :sponsor/ax.load-sponsor-status {:uf/fxs [[:popup/fx.load-sponsor-status]]}
    :uf/unhandled-ax))
