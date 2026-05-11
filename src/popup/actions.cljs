(ns popup.actions
  "Pure action handlers for the extension popup.
   No browser dependencies - testable without Chrome APIs."
  (:require [popup.actions.port-actions :as port-actions]
            [popup.actions.banner-actions :as banner-actions]
            [popup.actions.shadow-actions :as shadow-actions]))

(def normalize-domain-ports port-actions/normalize-domain-ports)

(defn handle-action
  "Pure action handler for popup state transitions.
   Returns map with :uf/db, :uf/fxs, :uf/dxs keys.

   uf-data should contain:
   - :system/now - current timestamp
   - :config/deps-string - deps string for server command generation"
  [state uf-data [action & args]]
  (case action
    ;; Port & connection actions - delegated to port-actions
    :popup/ax.set-nrepl-port (port-actions/set-port state :ports/nrepl (first args))
    :popup/ax.set-ws-port (port-actions/set-port state :ports/ws (first args))
    :popup/ax.copy-command (port-actions/copy-command state uf-data)
    :popup/ax.connect (port-actions/connect state)
    :popup/ax.cancel-connect {:uf/db (assoc state :ui/connecting? false)}
    :popup/ax.connect-finished {:uf/db (assoc state :ui/connecting? false)}
    :popup/ax.set-connect-mode (let [[mode] args] {:uf/db (assoc state :ui/connect-mode mode)})
    :popup/ax.check-status {:uf/fxs [[:popup/fx.check-status (:ports/ws state)]]}
    :popup/ax.load-saved-ports {:uf/fxs [[:popup/fx.load-saved-ports (:settings/default-nrepl-port state) (:settings/default-ws-port state)]]}
    :popup/ax.init-ports {:uf/fxs [[:popup/fx.init-ports]]}
    :popup/ax.apply-init-ports (port-actions/apply-init-ports state (first args))
    :popup/ax.set-default-nrepl-port (port-actions/set-default-port state :settings/default-nrepl-port (first args))
    :popup/ax.set-default-ws-port (port-actions/set-default-port state :settings/default-ws-port (first args))
    :popup/ax.load-default-ports-setting {:uf/fxs [[:popup/fx.load-default-ports-setting]]}
    :popup/ax.on-default-ports-changed (let [[new-defaults domain-ports] args] (port-actions/on-default-ports-changed state new-defaults domain-ports))
    :popup/ax.run-port-migration {:uf/fxs [[:popup/fx.run-port-migration]]}
    :popup/ax.apply-port-migration (port-actions/apply-port-migration state (first args))
    :popup/ax.load-connections {:uf/fxs [[:popup/fx.load-connections]]}

    ;; Script actions
    :popup/ax.load-scripts {:uf/fxs [[:popup/fx.load-scripts]]}
    :popup/ax.toggle-script
    (let [[script-id matching-pattern] args]
      {:uf/fxs [[:popup/fx.toggle-script (:scripts/list state) script-id matching-pattern]]})
    :popup/ax.delete-script
    (let [[script-id] args
          updated (filterv (fn [s] (not= (:script/id s) script-id)) (:scripts/list state))]
      {:uf/db (assoc state :scripts/list updated)
       :uf/fxs [[:popup/fx.delete-script updated script-id]]})
    :popup/ax.load-current-url {:uf/fxs [[:popup/fx.load-current-url]]}
    :popup/ax.inspect-script
    (let [[script-id] args
          script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
      (when script
        {:uf/fxs [[:popup/fx.inspect-script script]
                  [:uf/fx.defer-dispatch [[:popup/ax.show-system-banner "info" "Open the Epupp panel in Developer Tools"]] 0]]}))
    :popup/ax.evaluate-script
    (let [[script-id] args
          script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
      (when script
        {:uf/fxs [[:popup/fx.evaluate-script script]]}))
    :popup/ax.export-scripts {:uf/fxs [[:popup/fx.export-scripts]]}
    :popup/ax.import-scripts {:uf/fxs [[:popup/fx.trigger-import]]}
    :popup/ax.handle-import (let [[scripts-data] args] {:uf/fxs [[:popup/fx.import-scripts scripts-data]]})
    :popup/ax.reveal-script
    (let [[script-name] args]
      {:uf/db (-> state
                  (assoc-in [:ui/sections-collapsed :matching-scripts] false)
                  (assoc-in [:ui/sections-collapsed :other-scripts] false)
                  (assoc-in [:ui/sections-collapsed :manual-scripts] false)
                  (assoc-in [:ui/sections-collapsed :libraries] false)
                  (assoc :ui/reveal-highlight-script-name script-name))
       :uf/fxs [[:popup/fx.reveal-script script-name]
                [:uf/fx.defer-dispatch [[:db/ax.assoc :ui/reveal-highlight-script-name nil]] 2000]]})

    ;; Settings actions
    :popup/ax.load-auto-connect-level {:uf/fxs [[:popup/fx.load-auto-connect-level]]}
    :popup/ax.set-auto-connect-level
    (let [[level] args]
      {:uf/db (assoc state :settings/auto-connect-level level)
       :uf/fxs [[:popup/fx.save-auto-connect-level level]]})
    :popup/ax.load-auto-reconnect-setting {:uf/fxs [[:popup/fx.load-auto-reconnect-setting]]}
    :popup/ax.toggle-auto-reconnect-repl
    (let [new-value (not (:settings/auto-reconnect-repl state))]
      {:uf/db (assoc state :settings/auto-reconnect-repl new-value)
       :uf/fxs [[:popup/fx.save-auto-reconnect-setting new-value]]})
    :popup/ax.load-fs-sync-status {:uf/fxs [[:popup/fx.load-fs-sync-status]]}
    :popup/ax.toggle-fs-sync
    (let [current-tab-id (:scripts/current-tab-id state)
          currently-enabled? (and (some? current-tab-id)
                                  (= current-tab-id (:fs/sync-tab-id state)))]
      {:uf/fxs [[:popup/fx.toggle-fs-sync current-tab-id (not currently-enabled?)]]})
    :popup/ax.load-debug-logging-setting {:uf/fxs [[:popup/fx.load-debug-logging-setting]]}
    :popup/ax.toggle-debug-logging
    (let [new-value (not (:settings/debug-logging state))]
      {:uf/db (assoc state :settings/debug-logging new-value)
       :uf/fxs [[:popup/fx.save-debug-logging-setting new-value]]})

    ;; UI actions
    :popup/ax.toggle-section (let [[section-id] args] {:uf/db (update-in state [:ui/sections-collapsed section-id] not)})
    :popup/ax.toggle-creator-menu {:uf/db (update state :ui/creator-menu-open? not)}
    :popup/ax.close-creator-menu {:uf/db (assoc state :ui/creator-menu-open? false)}
    :popup/ax.dump-dev-log {:uf/fxs [[:popup/fx.dump-dev-log]]}
    :popup/ax.reveal-tab (let [[tab-id] args] {:uf/fxs [[:popup/fx.reveal-tab tab-id]]})
    :popup/ax.disconnect-tab (let [[tab-id] args] {:uf/fxs [[:popup/fx.disconnect-tab tab-id]]})
    :popup/ax.mark-scripts-modified
    (let [[script-names] args
          new-modified (into (or (:ui/recently-modified-scripts state) #{}) script-names)]
      {:uf/db (assoc state :ui/recently-modified-scripts new-modified)
       :uf/fxs [[:uf/fx.defer-dispatch [[:popup/ax.clear-modified-scripts]] 2000]]})
    :popup/ax.clear-modified-scripts {:uf/db (assoc state :ui/recently-modified-scripts #{})}
    :popup/ax.set-brave-detected (let [[brave?] args] {:uf/db (assoc state :browser/brave? brave?)})
    :popup/ax.check-page-scriptability {:uf/fxs [[:popup/fx.check-page-scriptability]]}

    ;; Sponsor actions
    :popup/ax.set-dev-sponsor-username
    (let [[username] args]
      {:uf/db (assoc state :sponsor/sponsored-username username)
       :uf/fxs [[:popup/fx.set-dev-sponsor-username username]]})
    :popup/ax.reset-sponsor-status
    {:uf/db (assoc state :sponsor/status false :sponsor/checked-at nil)
     :uf/fxs [[:popup/fx.reset-sponsor-status]]}
    :popup/ax.load-dev-sponsor-username {:uf/fxs [[:popup/fx.load-dev-sponsor-username]]}
    :popup/ax.check-sponsor
    (let [username (or (:sponsor/sponsored-username state) "PEZ")]
      {:uf/fxs [[:popup/fx.check-sponsor username]]})
    :popup/ax.load-sponsor-status {:uf/fxs [[:popup/fx.load-sponsor-status]]}

    ;; Permission actions
    :popup/ax.check-host-permission {:uf/fxs [[:popup/fx.check-host-permission]]}
    :popup/ax.request-host-permission {:uf/fxs [[:popup/fx.request-host-permission (:scripts/current-tab-id state)]]}

    ;; Banner actions - delegated to banner-actions
    :popup/ax.show-system-banner
    (let [[event-type message bulk-info category] args]
      (banner-actions/show-system-banner state uf-data event-type message
                                         (cond-> (or bulk-info {})
                                           category (assoc :category category))))
    :popup/ax.clear-system-banner (banner-actions/clear-system-banner state (first args))
    :popup/ax.track-bulk-name
    (let [[bulk-id script-name] args]
      {:uf/db (update-in state [:ui/system-bulk-names bulk-id] (fnil conj []) script-name)})
    :popup/ax.clear-bulk-names
    (let [[bulk-id] args]
      {:uf/db (update state :ui/system-bulk-names dissoc bulk-id)})
    :popup/ax.handle-system-banner
    (let [[banner-data] args]
      (banner-actions/handle-system-banner state banner-data))

    ;; Shadow list actions - delegated to shadow-actions
    :ui/ax.sync-scripts-shadow
    (let [[{:keys [added-items removed-ids]}] args]
      (shadow-actions/sync-scripts-shadow state added-items removed-ids))
    :ui/ax.clear-entering-scripts (shadow-actions/clear-entering-scripts state (first args))
    :ui/ax.remove-leaving-scripts (shadow-actions/remove-leaving-scripts state (first args))

    ;; Runtime status
    :popup/ax.handle-runtime-status
    (let [[{:keys [tab-id errors]}] args]
      (when (= tab-id (:scripts/current-tab-id state))
        {:uf/db (assoc state :runtime/errors (or errors {}))}))
    :popup/ax.load-runtime-status {:uf/fxs [[:popup/fx.load-runtime-status (:scripts/current-tab-id state)]]}

    :uf/unhandled-ax))