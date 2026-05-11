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

(defn handle-action [state _uf-data [action & args]]
  (case action
    :settings/ax.load-auto-connect-level (load-auto-connect-level)
    :settings/ax.set-auto-connect-level (set-auto-connect-level state (first args))
    :settings/ax.load-auto-reconnect-setting (load-auto-reconnect-setting)
    :settings/ax.toggle-auto-reconnect-repl (toggle-auto-reconnect-repl state)
    :settings/ax.load-fs-sync-status (load-fs-sync-status)
    :settings/ax.toggle-fs-sync (toggle-fs-sync state)
    :settings/ax.load-debug-logging-setting (load-debug-logging-setting)
    :settings/ax.toggle-debug-logging (toggle-debug-logging state)
    :uf/unhandled-ax))
