(ns panel
  "DevTools panel for live ClojureScript evaluation.
   Communicates with inspected page via chrome.devtools.inspectedWindow."
  (:require [reagami :as r]
            [event-handler :as event-handler]
            [log :as log]
            [manifest-parser :as mp]
            [panel-actions :as panel-actions]
            [panel.effects :as panel-effects]
            [panel.views.main-views :as views-main]
            [script-utils :as script-utils]
            [storage :as storage]
            [test-logger :as test-logger]))

(defonce !state
  (atom {:panel/results []
         :panel/code ""
         :panel/evaluating? false
         :panel/scittle-status :unknown
         :panel/script-name ""
         :panel/original-name nil
         :panel/script-id nil
         :panel/script-match ""
         :panel/script-description ""
         :panel/init-version nil
         :panel/needs-refresh? false
         :panel/current-hostname nil
         :panel/manifest-hints nil
         :panel/selection nil
         :panel/system-banners []
         :panel/system-bulk-names {}
         :panel/page-banner nil
         :panel/scripts-list []
         :panel/tab-connected? false
         :panel/inspected-tab-id nil
         :runtime/errors {}
         :sponsor/status false
         :sponsor/checked-at nil}))

(defn dispatch! [actions]
  (event-handler/dispatch! !state panel-actions/handle-action panel-effects/perform-effect! actions))

(defn- render! []
  (r/render (js/document.getElementById "app")
            [views-main/panel-ui dispatch! @!state]))

(defn- get-extension-version []
  (try
    (.-version (js/chrome.runtime.getManifest))
    (catch :default _e nil)))

(defn- check-version! []
  (let [current-version (get-extension-version)]
    (dispatch! [[:panel/ax.check-version current-version]])))

(defn- update-page-banner!
  "Update page banner based on URL scriptability."
  [url]
  (let [scriptability (script-utils/check-page-scriptability url (script-utils/detect-browser-type))]
    (dispatch! [[:db/ax.assoc :panel/page-banner
                 (when-not (:scriptable? scriptability)
                   {:type "info" :message (:message scriptability)})]])))

(defn- check-page-scriptability! []
  (js/chrome.devtools.inspectedWindow.eval
   "window.location.href"
   (fn [url _exception]
     (update-page-banner! url))))

(defn- on-page-navigated [url]
  (log/debug "Panel" "Page navigated")
  (check-version!)
  (update-page-banner! url)
  (dispatch! [[:editor/ax.reset-for-navigation]])
  (dispatch! [[:editor/ax.clear-results]
              [:editor/ax.check-scittle]])
  (panel-effects/perform-effect! dispatch! [:editor/fx.restore-panel-state nil]))

(defn init! []
  (log/info "Panel" "Initializing...")
  (test-logger/install-global-error-handlers! "panel" js/window)
  (when (test-logger/test-mode?)
    (set! js/window.__panelState !state))
  (dispatch! [[:editor/ax.set-init-version (get-extension-version)]])
  (panel-effects/perform-effect!
   dispatch!
   [:editor/fx.restore-panel-state
    (fn []
      ((^:async fn []
         (js-await (storage/load!))
         (log/debug "Panel" "Storage loaded, version:" (get-extension-version))
         (add-watch !state :panel/render
                    (fn [_ _ old-state new-state]
                      (render!)
                      (when (not= (:panel/code old-state) (:panel/code new-state))
                        (panel-effects/save-panel-state! new-state))))
         (render!)
         (dispatch! [[:editor/ax.check-scittle]])
         (panel-effects/perform-effect! dispatch! [:editor/fx.load-connections])
         (panel-effects/perform-effect! dispatch! [:editor/fx.load-scripts-list])
         (panel-effects/perform-effect! dispatch! [:editor/fx.load-sponsor-status])
         (dispatch! [[:editor/ax.check-editing-script]])
         (check-page-scriptability!)
         (js/chrome.devtools.network.onNavigated.addListener on-page-navigated)
         (js/document.addEventListener "visibilitychange"
                                       (fn [_] (when (= "visible" js/document.visibilityState)
                                                 (check-version!)))))))]))

;; Listen for storage changes
(js/chrome.storage.onChanged.addListener
 (fn [changes area]
   (when (= area "local")
     (when (.-scripts changes)
       (let [new-scripts (.-newValue (.-scripts changes))
             parsed (if new-scripts
                      (script-utils/parse-scripts new-scripts {:extract-manifest mp/extract-manifest})
                      [])]
         (dispatch! [[:editor/ax.update-scripts-list parsed]])))
     (when (.-editingScript changes)
       (dispatch! [[:editor/ax.check-editing-script]]))
     (let [status-change (.-sponsorStatus changes)
           checked-change (.-sponsorCheckedAt changes)]
       (when (or status-change checked-change)
         (dispatch! (cond-> []
                      status-change
                      (conj [:db/ax.assoc :sponsor/status (boolean (.-newValue status-change))])
                      checked-change
                      (conj [:db/ax.assoc :sponsor/checked-at (.-newValue checked-change)]))))))))

;; Listen for messages from background
(js/chrome.runtime.onMessage.addListener
 (fn [message _sender _send-response]
   (cond
     (= "system-banner" (.-type message))
     (dispatch! [[:panel/ax.handle-system-banner
                  {:event-type (aget message "event-type")
                   :operation (aget message "operation")
                   :script-name (aget message "script-name")
                   :error (aget message "error")
                   :unchanged (aget message "unchanged")
                   :from-name (aget message "from-name")
                   :bulk-id (aget message "bulk-id")
                   :bulk-count (aget message "bulk-count")
                   :bulk-index (aget message "bulk-index")}]])

     (= "connections-changed" (.-type message))
     (let [connections (.-connections message)
           inspected-tab-id js/chrome.devtools.inspectedWindow.tabId
           tab-id-str (str inspected-tab-id)
           tab-connected? (boolean (some #(= (str (:tab-id %)) tab-id-str) connections))]
       (dispatch! [[:editor/ax.set-tab-connected tab-connected?]])
       (when-not tab-connected?
         (dispatch! [[:editor/ax.handle-ws-close]])))

     (= "runtime-status" (.-type message))
     (let [tab-id (aget message "tab-id")
           inspected-tab-id js/chrome.devtools.inspectedWindow.tabId]
       (when (= tab-id inspected-tab-id)
         (dispatch! [[:panel/ax.handle-runtime-status
                      {:errors (aget message "errors")}]]))))
   false))

(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
