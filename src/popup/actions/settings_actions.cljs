(ns popup.actions.settings-actions)

(defn set-auto-connect-level [state level]
  {:uf/db (assoc state :settings/auto-connect-level level)
   :uf/fxs [[:popup/fx.save-auto-connect-level level]]})

(defn toggle-auto-reconnect-repl [state]
  (let [new-value (not (:settings/auto-reconnect-repl state))]
    {:uf/db (assoc state :settings/auto-reconnect-repl new-value)
     :uf/fxs [[:popup/fx.save-auto-reconnect-setting new-value]]}))

(defn toggle-fs-sync [state]
  (let [current-tab-id (:scripts/current-tab-id state)
        currently-enabled? (and (some? current-tab-id)
                                (= current-tab-id (:fs/sync-tab-id state)))]
    {:uf/fxs [[:popup/fx.toggle-fs-sync current-tab-id (not currently-enabled?)]]}))

(defn toggle-debug-logging [state]
  (let [new-value (not (:settings/debug-logging state))]
    {:uf/db (assoc state :settings/debug-logging new-value)
     :uf/fxs [[:popup/fx.save-debug-logging-setting new-value]]}))

(defn load-auto-connect-level []
  {:uf/fxs [[:popup/fx.load-auto-connect-level]]})

(defn load-auto-reconnect-setting []
  {:uf/fxs [[:popup/fx.load-auto-reconnect-setting]]})

(defn load-fs-sync-status []
  {:uf/fxs [[:popup/fx.load-fs-sync-status]]})

(defn load-debug-logging-setting []
  {:uf/fxs [[:popup/fx.load-debug-logging-setting]]})
