(ns popup.actions.sponsor-actions)

(defn set-dev-sponsor-username [state username]
  {:uf/db (assoc state :sponsor/sponsored-username username)
   :uf/fxs [[:popup/fx.set-dev-sponsor-username username]]})

(defn reset-sponsor-status [state]
  {:uf/db (assoc state :sponsor/status false :sponsor/checked-at nil)
   :uf/fxs [[:popup/fx.reset-sponsor-status]]})

(defn check-sponsor [state]
  (let [username (or (:sponsor/sponsored-username state) "PEZ")]
    {:uf/fxs [[:popup/fx.check-sponsor username]]}))

(defn load-dev-sponsor-username []
  {:uf/fxs [[:popup/fx.load-dev-sponsor-username]]})

(defn load-sponsor-status []
  {:uf/fxs [[:popup/fx.load-sponsor-status]]})
