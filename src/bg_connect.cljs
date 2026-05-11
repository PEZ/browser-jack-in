(ns bg-connect
  "REPL connection flow: page-context JS functions, bridge injection,
   scittle.nrepl setup, and end-to-end tab connection orchestration."
  (:require [bg-inject :as bg-inject]
            [test-logger :as test-logger]
            [log :as log]))

;; ============================================================
;; Page-context JS function literals
;; ============================================================

(defn ws-fail-message []
  "WebSocket connection failed. Is the server running?")

(def set-nrepl-config-fn
  (js* "function(port) {
    window.SCITTLE_NREPL_WEBSOCKET_HOST = 'localhost';
    window.SCITTLE_NREPL_WEBSOCKET_PORT = port;
  }"))

(def check-status-fn
  (js* "function() {
    return {
      hasScittle: !!(window.scittle && window.scittle.core),
      hasScittleNrepl: !!(window.scittle && window.scittle.nrepl && window.scittle.nrepl.core),
      hasWsBridge: !!window.__browserJackInWSBridge,
      hasContentBridge: !!window.__browserJackInContentBridge
    };
  }"))

(def inject-script-fn
  (js* "function(url, isModule) {
    var script = document.createElement('script');
    if (isModule) script.type = 'module';
    if (window.trustedTypes && window.trustedTypes.createPolicy) {
      try {
        var policy = window.trustedTypes.defaultPolicy;
        if (!policy) {
          policy = window.trustedTypes.createPolicy('default', {
            createHTML: function(s) { return s; },
            createScript: function(s) { return s; },
            createScriptURL: function(s) { return s; }
          });
        }
        script.src = policy.createScriptURL(url);
      } catch(e) {
        console.warn('[Epupp] TrustedTypes policy creation failed, using direct assignment:', e.message);
        script.src = url;
      }
    } else {
      script.src = url;
    }
    document.head.appendChild(script);
    return 'ok';
  }"))

(def close-websocket-fn
  (js* "function() {
    var ws = window.ws_nrepl;
    if (ws) {
      window.__savedNreplOnMessage = ws.onmessage || null;
      if (ws.readyState === 0 || ws.readyState === 1) {
        ws.close();
      }
      window.ws_nrepl = null;
    }
  }"))

(def reconnect-nrepl-fn
  (js* "function(port) {
    // Create new WebSocket - will be intercepted by ws-bridge
    new WebSocket('ws://localhost:' + port + '/_nrepl');
    // Restore Scittle's onmessage handler onto the new proxy
    if (window.__savedNreplOnMessage && window.ws_nrepl) {
      window.ws_nrepl.onmessage = window.__savedNreplOnMessage;
    }
  }"))

(def check-connection-fn
  (js* "function() {
    var ws = window.ws_nrepl;
    if (!ws) return {connected: false, state: -1};
    return {connected: ws.readyState === 1, state: ws.readyState};
  }"))

;; ============================================================
;; Connection helpers
;; ============================================================

(def ^:private epupp-api-files
  [{:id "epupp-repl"
    :path "bundled/epupp/repl.cljs"}
   {:id "epupp-fs"
    :path "bundled/epupp/fs.cljs"}
   {:id "epupp-internal-helpers"
    :path "bundled/epupp/internal/helpers.cljs"}
   {:id "epupp-tools"
    :path "bundled/epupp/tools.cljs"}])

(defn ^:async fetch-text!
  [url]
  (let [resp (js-await (js/fetch url))]
    (when-not (.-ok resp)
      (throw (js/Error. (str "Failed to fetch " url " (" (.-status resp) ")"))))
    (js-await (.text resp))))

(defn find-tab-id-by-url-pattern!
  "Return the first tab id matching url-pattern, or nil if none match."
  [url-pattern]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.tabs.query
      #js {:url url-pattern}
      (fn [tabs]
        (cond
          js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))

          (pos? (.-length tabs))
          (resolve (.-id (aget tabs 0)))

          :else
          (resolve nil)))))))

(defn poll-until-connection
  "Poll for window.ws_nrepl to reach OPEN state."
  [tab-id timeout]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)]
       (letfn [(poll []
                 (-> (bg-inject/execute-in-page tab-id check-connection-fn)
                     (.then (fn [result]
                              (cond
                                (and result (.-connected result))
                                (resolve result)

                                (= 3 (.-state result))
                                (reject (js/Error. (ws-fail-message)))

                                (> (- (js/Date.now) start) timeout)
                                (reject (js/Error. "Timeout"))

                                :else
                                (js/setTimeout poll 100))))
                     (.catch reject)))]
         (poll))))))

;; ============================================================
;; Connection flow
;; ============================================================

(defn ^:async ensure-bridge!
  "Ensure the content bridge is injected and the ws-bridge is installed in the page."
  [tab-id status]
  (when-not (and status (.-hasContentBridge status))
    (js-await (bg-inject/inject-content-script tab-id "content-bridge.js")))
  (when-not (and status (.-hasWsBridge status))
    (let [bridge-url (js/chrome.runtime.getURL "ws-bridge.js")]
      (js-await (bg-inject/execute-in-page tab-id inject-script-fn bridge-url false))))
  (js-await (bg-inject/wait-for-bridge-ready tab-id))
  true)

(defn ^:async ensure-scittle-nrepl!
  "Ensure scittle.nrepl is loaded and connected."
  [tab-id ws-port status]
  (let [nrepl-url (js/chrome.runtime.getURL "vendor/scittle.nrepl.js")]
    (js-await (bg-inject/execute-in-page tab-id close-websocket-fn))
    (js-await (bg-inject/execute-in-page tab-id set-nrepl-config-fn ws-port))
    (if (and status (.-hasScittleNrepl status))
      (js-await (bg-inject/execute-in-page tab-id reconnect-nrepl-fn ws-port))
      (js-await (bg-inject/execute-in-page tab-id inject-script-fn nrepl-url false)))
    (js-await (poll-until-connection tab-id 3000))
    true))

(defn ^:async inject-epupp-api!
  "Inject Epupp REPL API namespaces from bundled Scittle source files."
  [tab-id]
  (try
    (let [trigger-url (js/chrome.runtime.getURL "trigger-scittle.js")]
      (loop [remaining epupp-api-files]
        (when (seq remaining)
          (let [{:keys [id path]} (first remaining)
                url (js/chrome.runtime.getURL path)
                code (js-await (fetch-text! url))]
            (js-await (bg-inject/send-tab-message tab-id {:type "inject-userscript"
                                                          :id id
                                                          :code code}))
            (recur (rest remaining)))))
      (js-await (bg-inject/send-tab-message tab-id {:type "inject-script" :url trigger-url}))
      (js-await (test-logger/log-event! "EPUPP_API_INJECTED"
                                        {:tab-id tab-id
                                         :files (vec (map :path epupp-api-files))}))
      (log/info "Background:REPL" "Injected Epupp API into tab:" tab-id)
      true)
    (catch :default err
      (log/error "Background:REPL" "Failed to inject Epupp API:" err)
      (js-await (test-logger/log-event! "EPUPP_API_INJECT_ERROR"
                                        {:tab-id tab-id
                                         :error (.-message err)}))
      false)))

(defn ^:async connect-tab!
  "End-to-end connect flow for a specific tab.
   Ensures bridge + Scittle + scittle.nrepl and waits for connection.
   Also injects the Epupp API for manifest! support."
  [dispatch! tab-id ws-port icon-state]
  (when-not (and tab-id ws-port)
    (throw (js/Error. "connect-tab: Missing tab-id or ws-port")))
  (let [status (js-await (bg-inject/execute-in-page tab-id check-status-fn))]
    (js-await (ensure-bridge! tab-id status))
    (js-await (bg-inject/ensure-scittle! dispatch! tab-id icon-state))
    (let [status2 (js-await (bg-inject/execute-in-page tab-id check-status-fn))]
      (js-await (ensure-scittle-nrepl! tab-id ws-port status2)))
    (js-await (inject-epupp-api! tab-id))
    true))
