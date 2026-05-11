(ns popup.actions
  "Pure action handlers for the extension popup.
   No browser dependencies - testable without Chrome APIs."
  (:require [popup.actions.port-actions :as port-actions]
            [popup.actions.banner-actions :as banner-actions]
            [popup.actions.shadow-actions :as shadow-actions]
            [popup.actions.script-actions :as script-actions]
            [popup.actions.settings-actions :as settings-actions]
            [popup.actions.ui-actions :as ui-actions]
            [popup.actions.sponsor-actions :as sponsor-actions]
            [popup.actions.permission-actions :as permission-actions]
            [popup.actions.runtime-actions :as runtime-actions]))

(def normalize-domain-ports port-actions/normalize-domain-ports)

(defn handle-action
  "Pure action handler for popup state transitions.
   Returns map with :uf/db, :uf/fxs, :uf/dxs keys."
  [state uf-data [action & args]]
  (case action
    ;; Port & connection actions
    :popup-connection/ax.set-nrepl-port (port-actions/set-port state :ports/nrepl (first args))
    :popup-connection/ax.set-ws-port (port-actions/set-port state :ports/ws (first args))
    :popup-connection/ax.copy-command (port-actions/copy-command state uf-data)
    :popup-connection/ax.connect (port-actions/connect state)
    :popup-connection/ax.cancel-connect (port-actions/cancel-connect state)
    :popup-connection/ax.connect-finished (port-actions/connect-finished state)
    :popup-connection/ax.set-connect-mode (port-actions/set-connect-mode state (first args))
    :popup-connection/ax.check-status (port-actions/check-status state)
    :popup-connection/ax.load-saved-ports (port-actions/load-saved-ports state)
    :popup-connection/ax.init-ports (port-actions/init-ports)
    :popup-connection/ax.apply-init-ports (port-actions/apply-init-ports state (first args))
    :popup-connection/ax.set-default-nrepl-port (port-actions/set-default-port state :settings/default-nrepl-port (first args))
    :popup-connection/ax.set-default-ws-port (port-actions/set-default-port state :settings/default-ws-port (first args))
    :popup-connection/ax.load-default-ports-setting (port-actions/load-default-ports-setting)
    :popup-connection/ax.on-default-ports-changed (port-actions/on-default-ports-changed state (first args) (second args))
    :popup-connection/ax.run-port-migration (port-actions/run-port-migration)
    :popup-connection/ax.apply-port-migration (port-actions/apply-port-migration state (first args))
    :popup-connection/ax.load-connections (port-actions/load-connections)
    ;; Script actions
    :popup/ax.load-scripts (script-actions/load-scripts)
    :popup/ax.toggle-script (script-actions/toggle-script state (first args) (second args))
    :popup/ax.delete-script (script-actions/delete-script state (first args))
    :popup/ax.load-current-url (script-actions/load-current-url)
    :popup/ax.inspect-script (script-actions/inspect-script state (first args))
    :popup/ax.evaluate-script (script-actions/evaluate-script state (first args))
    :popup/ax.export-scripts (script-actions/export-scripts)
    :popup/ax.import-scripts (script-actions/import-scripts)
    :popup/ax.handle-import (script-actions/handle-import (first args))
    :popup/ax.reveal-script (script-actions/reveal-script state (first args))
    ;; Settings actions
    :popup/ax.load-auto-connect-level (settings-actions/load-auto-connect-level)
    :popup/ax.set-auto-connect-level (settings-actions/set-auto-connect-level state (first args))
    :popup/ax.load-auto-reconnect-setting (settings-actions/load-auto-reconnect-setting)
    :popup/ax.toggle-auto-reconnect-repl (settings-actions/toggle-auto-reconnect-repl state)
    :popup/ax.load-fs-sync-status (settings-actions/load-fs-sync-status)
    :popup/ax.toggle-fs-sync (settings-actions/toggle-fs-sync state)
    :popup/ax.load-debug-logging-setting (settings-actions/load-debug-logging-setting)
    :popup/ax.toggle-debug-logging (settings-actions/toggle-debug-logging state)
    ;; UI actions
    :popup/ax.toggle-section (ui-actions/toggle-section state (first args))
    :popup/ax.toggle-creator-menu (ui-actions/toggle-creator-menu state)
    :popup/ax.close-creator-menu (ui-actions/close-creator-menu state)
    :popup/ax.dump-dev-log (ui-actions/dump-dev-log)
    :popup/ax.reveal-tab (ui-actions/reveal-tab (first args))
    :popup/ax.disconnect-tab (ui-actions/disconnect-tab (first args))
    :popup/ax.mark-scripts-modified (ui-actions/mark-scripts-modified state (first args))
    :popup/ax.clear-modified-scripts (ui-actions/clear-modified-scripts state)
    :popup/ax.set-brave-detected (ui-actions/set-brave-detected state (first args))
    :popup/ax.check-page-scriptability (ui-actions/check-page-scriptability)
    ;; Sponsor actions
    :popup/ax.set-dev-sponsor-username (sponsor-actions/set-dev-sponsor-username state (first args))
    :popup/ax.reset-sponsor-status (sponsor-actions/reset-sponsor-status state)
    :popup/ax.load-dev-sponsor-username (sponsor-actions/load-dev-sponsor-username)
    :popup/ax.check-sponsor (sponsor-actions/check-sponsor state)
    :popup/ax.load-sponsor-status (sponsor-actions/load-sponsor-status)
    ;; Permission actions
    :popup/ax.check-host-permission (permission-actions/check-host-permission)
    :popup/ax.request-host-permission (permission-actions/request-host-permission state)
    ;; Banner actions
    :popup/ax.show-system-banner
    (let [[event-type message bulk-info category] args]
      (banner-actions/show-system-banner state uf-data event-type message
                                         (cond-> (or bulk-info {})
                                           category (assoc :category category))))
    :popup/ax.clear-system-banner (banner-actions/clear-system-banner state (first args))
    :popup/ax.track-bulk-name (banner-actions/track-bulk-name state (first args) (second args))
    :popup/ax.clear-bulk-names (banner-actions/clear-bulk-names state (first args))
    :popup/ax.handle-system-banner (banner-actions/handle-system-banner state (first args))
    ;; Shadow list actions
    :ui/ax.sync-scripts-shadow
    (let [[{:keys [added-items removed-ids]}] args]
      (shadow-actions/sync-scripts-shadow state added-items removed-ids))
    :ui/ax.clear-entering-scripts (shadow-actions/clear-entering-scripts state (first args))
    :ui/ax.remove-leaving-scripts (shadow-actions/remove-leaving-scripts state (first args))
    ;; Runtime status
    :popup/ax.handle-runtime-status
    (let [[{:keys [tab-id errors]}] args]
      (runtime-actions/handle-runtime-status state tab-id errors (:scripts/current-tab-id state)))
    :popup/ax.load-runtime-status (runtime-actions/load-runtime-status (:scripts/current-tab-id state))

    :uf/unhandled-ax))