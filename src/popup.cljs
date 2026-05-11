(ns popup
  "Epupp extension popup - built with Squint + Reagami
   Inspired by Replicant tic-tac-toe state management pattern"
  (:require [reagami :as r]
            [event-handler :as event-handler]
            [manifest-parser :as mp]
            [script-utils :as script-utils]
            [popup.utils :as popup-utils]
            [popup.actions :as popup-actions]
            [popup.effects.port-effects :as port-effects]
            [popup.effects.connection-effects :as connection-effects]
            [popup.effects.script-effects :as script-effects]
            [popup.effects.settings-effects :as settings-effects]
            [popup.effects.sponsor-effects :as sponsor-effects]
            [popup.effects.ui-effects :as ui-effects]
            [log :as log]
            [test-logger :as test-logger]
            [popup.views.main-views :as views-main]))

;; EXTENSION_CONFIG is injected by esbuild at bundle time from config/*.edn
;; Shape: {"dev": boolean, "depsString": string, "sectionsCollapsed": {...}}
(def ^:private config js/EXTENSION_CONFIG)

(defonce !state
  (atom {:ports/nrepl "3339"
         :ports/ws "3340"
         :ui/reveal-highlight-script-name nil ; Temporary highlight when revealing a script
         :ui/connecting? false
         :ui/connect-mode "direct"
         :ui/sections-collapsed (or (.-sectionsCollapsed config)
                                    {:repl-connect false
                                     :manual-scripts false
                                     :libraries true
                                     :matching-scripts false
                                     :other-scripts true
                                     :special true
                                     :settings true
                                     :dev-tools true})
         :browser/brave? false
         :scripts/list []
         :scripts/current-url nil
         :scripts/current-tab-id nil
         :settings/auto-connect-level "off"
         :settings/auto-reconnect-repl true
         :fs/sync-tab-id nil
         :settings/debug-logging false
         :settings/default-nrepl-port "3339" ; Default nREPL port for new hostnames
         :settings/default-ws-port "3340"    ; Default WebSocket port for new hostnames
         :permissions/host-granted? true ; Assume granted (Chrome default), check on init
         :ui/system-banners []          ; System banners [{:id :type :message :leaving} ...]
         :ui/system-bulk-names {}      ; bulk-id -> [script-name ...]
         :ui/page-banner nil           ; Page-level banner (e.g., unscriptable page)
         :ui/recently-modified-scripts #{} ; Scripts modified via REPL FS sync
         :browser/type :chrome         ; Detected browser type
         :sponsor/status false
         :sponsor/checked-at nil
         :sponsor/sponsored-username "PEZ"
         :repl/connections []         ; Source of truth for connections
         :runtime/errors {}           ; {script-name -> error-envelope} for current tab
         ;; Shadow lists for rendering with animation state
         ;; Shape: [{:item <original> :ui/entering? bool :ui/leaving? bool}]
         :ui/scripts-shadow []
         ;; List watchers: compare source to shadow, trigger sync actions
         :uf/list-watchers {:scripts/list {:id-fn :script/id
                                           :shadow-path :ui/scripts-shadow
                                           :on-change :ui/ax.sync-scripts-shadow}}}))

(def ^:private effect-router
  {:popup/fx.save-ports port-effects/save-ports!
   :popup/fx.clear-domain-ports port-effects/clear-domain-ports!
   :popup/fx.load-saved-ports port-effects/load-saved-ports!
   :popup/fx.init-ports port-effects/init-ports!
   :popup/fx.load-default-ports-setting port-effects/load-default-ports-setting!
   :popup/fx.save-default-ports-setting port-effects/save-default-ports-setting!
   :popup/fx.run-port-migration port-effects/run-port-migration!
   :popup/fx.remove-storage-keys port-effects/remove-storage-keys!
   :popup/fx.set-storage-key port-effects/set-storage-key!
   :popup/fx.connect connection-effects/connect!
   :popup/fx.check-status connection-effects/check-status!
   :popup/fx.disconnect-tab connection-effects/disconnect-tab!
   :popup/fx.load-current-url connection-effects/load-current-url!
   :popup/fx.load-connections connection-effects/load-connections!
   :popup/fx.load-runtime-status connection-effects/load-runtime-status!
   :popup/fx.load-scripts script-effects/load-scripts!
   :popup/fx.toggle-script script-effects/toggle-script!
   :popup/fx.delete-script script-effects/delete-script!
   :popup/fx.inspect-script script-effects/inspect-script!
   :popup/fx.evaluate-script script-effects/evaluate-script!
   :popup/fx.export-scripts script-effects/export-scripts!
   :popup/fx.trigger-import script-effects/trigger-import!
   :popup/fx.import-scripts script-effects/import-scripts!
   :popup/fx.load-auto-connect-level settings-effects/load-auto-connect-level!
   :popup/fx.save-auto-connect-level settings-effects/save-auto-connect-level!
   :popup/fx.load-auto-reconnect-setting settings-effects/load-auto-reconnect-setting!
   :popup/fx.save-auto-reconnect-setting settings-effects/save-auto-reconnect-setting!
   :popup/fx.load-fs-sync-status settings-effects/load-fs-sync-status!
   :popup/fx.toggle-fs-sync settings-effects/toggle-fs-sync!
   :popup/fx.load-debug-logging-setting settings-effects/load-debug-logging-setting!
   :popup/fx.save-debug-logging-setting settings-effects/save-debug-logging-setting!
   :popup/fx.check-sponsor sponsor-effects/check-sponsor!
   :popup/fx.load-sponsor-status sponsor-effects/load-sponsor-status!
   :popup/fx.set-dev-sponsor-username sponsor-effects/set-dev-sponsor-username!
   :popup/fx.reset-sponsor-status sponsor-effects/reset-sponsor-status!
   :popup/fx.load-dev-sponsor-username sponsor-effects/load-dev-sponsor-username!
   :popup/fx.copy-command ui-effects/copy-command!
   :popup/fx.reveal-script ui-effects/reveal-script!
   :popup/fx.reveal-tab ui-effects/reveal-tab!
   :popup/fx.dump-dev-log ui-effects/dump-dev-log!
   :popup/fx.log-system-banner ui-effects/log-system-banner!
   :popup/fx.check-page-scriptability ui-effects/check-page-scriptability!
   :popup/fx.check-host-permission ui-effects/check-host-permission!
   :popup/fx.request-host-permission ui-effects/request-host-permission!})

(defn ^:async perform-effect! [dispatch [effect & args]]
  (let [handler (get effect-router effect)]
    (if handler
      (js-await (apply handler dispatch args))
      (case effect
        :uf/fx.defer-dispatch
        (let [[actions timeout] args]
          (js/setTimeout #(dispatch actions) timeout))
        :uf/unhandled-fx))))

(defn- make-uf-data []
  {:config/deps-string (.-depsString config)})

(defn dispatch! [actions]
  (event-handler/dispatch! !state popup-actions/handle-action perform-effect! actions (make-uf-data)))

(defn render! []
  (r/render (js/document.getElementById "app")
            [views-main/popup-ui dispatch! @!state]))

(defn- handle-runtime-message [message _sender _send-response]
  (case (.-type message)
    "connections-changed"
    (dispatch! [[:db/ax.assoc :repl/connections (.-connections message)]])
    "fs-sync-status-changed"
    (dispatch! [[:db/ax.assoc :fs/sync-tab-id (.-fsSyncTabId message)]])
    "runtime-status"
    (dispatch! [[:popup/ax.handle-runtime-status
                 {:tab-id (aget message "tab-id")
                  :errors (aget message "errors")}]])
    nil)
  false)

(defn- handle-system-banner-message [message _sender _send-response]
  (when (= "system-banner" (.-type message))
    (dispatch! [[:popup/ax.handle-system-banner
                 {:event-type (aget message "event-type")
                  :operation (aget message "operation")
                  :script-name (aget message "script-name")
                  :error (aget message "error")
                  :unchanged (aget message "unchanged")
                  :bulk-id (aget message "bulk-id")
                  :bulk-count (aget message "bulk-count")
                  :bulk-index (aget message "bulk-index")}]]))
  false)

(defn- notify-scripts-modified! [old-scripts new-scripts]
  (when (and old-scripts new-scripts)
    (let [{:keys [added modified]} (script-utils/diff-scripts old-scripts new-scripts)
          changed-names (concat added modified)]
      (when (seq changed-names)
        (dispatch! [[:popup/ax.mark-scripts-modified (vec changed-names)]])))))

(defn- handle-scripts-storage-change [changes area]
  (when (and (= area "local") (.-scripts changes))
    (let [scripts-change (.-scripts changes)
          old-scripts (when (.-oldValue scripts-change)
                        (script-utils/parse-scripts (.-oldValue scripts-change) {:extract-manifest mp/extract-manifest}))
          new-scripts (when (.-newValue scripts-change)
                        (script-utils/parse-scripts (.-newValue scripts-change) {:extract-manifest mp/extract-manifest}))]
      (dispatch! [[:popup/ax.load-scripts]
                  [:popup/ax.load-runtime-status]])
      (notify-scripts-modified! old-scripts new-scripts))))

(defn- handle-sponsor-storage-change [changes area]
  (when (= area "local")
    (let [status-change (.-sponsorStatus changes)
          checked-change (.-sponsorCheckedAt changes)]
      (when (or status-change checked-change)
        (dispatch! (cond-> []
                     status-change
                     (conj [:db/ax.assoc :sponsor/status (boolean (.-newValue status-change))])
                     checked-change
                     (conj [:db/ax.assoc :sponsor/checked-at (.-newValue checked-change)])))))))

(defn- handle-default-ports-change [changes area]
  (when (popup-utils/default-ports-changed? changes area)
    (.then (popup-utils/get-active-tab)
           (fn [tab]
             (let [key (port-effects/storage-key tab)]
               (js/chrome.storage.local.get
                #js ["defaultNreplPort" "defaultWsPort" key]
                (fn [result]
                  (let [new-defaults (popup-utils/parse-new-defaults result)
                        domain-ports (popup-utils/parse-domain-ports (aget result key))]
                    (dispatch! [[:popup-connection/ax.on-default-ports-changed new-defaults domain-ports]])))))))))

(defn init! []
  (log/info "Popup" "Init!")
  (test-logger/install-global-error-handlers! "popup" js/window)
  (add-watch !state :popup/render (fn [_ _ _ _] (render!)))
  (dispatch! [[:popup/ax.set-brave-detected (some? (.-brave js/navigator))]])
  (render!)
  (js/requestAnimationFrame
   (fn [] (js/requestAnimationFrame
           (fn [] (.add (.-classList js/document.body) "ready")))))
  (js/chrome.runtime.onMessage.addListener handle-runtime-message)
  (js/chrome.runtime.onMessage.addListener handle-system-banner-message)
  (js/chrome.storage.onChanged.addListener handle-scripts-storage-change)
  (js/chrome.storage.onChanged.addListener handle-sponsor-storage-change)
  (js/chrome.storage.onChanged.addListener handle-default-ports-change)
  (dispatch! [[:popup-connection/ax.init-ports]
              [:popup-connection/ax.check-status]
              [:popup/ax.load-scripts]
              [:popup/ax.load-current-url]
              [:popup/ax.check-page-scriptability]
              [:popup/ax.load-auto-connect-level]
              [:popup/ax.load-auto-reconnect-setting]
              [:popup/ax.load-fs-sync-status]
              [:popup/ax.load-debug-logging-setting]
              [:popup-connection/ax.load-connections]
              [:popup/ax.load-sponsor-status]
              [:popup/ax.load-dev-sponsor-username]
              [:popup/ax.check-host-permission]])
  (js/setTimeout #(dispatch! [[:popup-connection/ax.run-port-migration]]) 1000))

;; Start the app when DOM is ready
(log/info "Popup" "Script loaded, readyState:" js/document.readyState)
(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
