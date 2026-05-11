(ns background
  "Background service worker for WebSocket connections.
   Runs in extension context, immune to page CSP.
   Relays WebSocket messages to/from content scripts."
  (:require [storage :as storage]
            [url-matching :as url-matching]
            [script-utils :as script-utils]
            [page-scriptability :as page-scriptability]
            [registration :as registration]
            [manifest-parser :as manifest-parser]
            [test-logger :as test-logger]
            [background-utils :as bg-utils]
            [log :as log]
            [event-handler :as event-handler]
            [background-actions :as bg-actions]
            [bg-fs-dispatch :as fs-dispatch]
            [bg-icon :as bg-icon]
            [bg-inject :as bg-inject]
            [dep-resolver :as dep-resolver]
            [ext-dep :as ext-dep]
            [permissions :as permissions]
            [utils :as utils]
            [background-effects.ws-effects :as ws-effects]
            [background-effects.icon-effects :as icon-effects]
            [background-effects.alarm-effects :as alarm-effects]
            [background-effects.storage-effects :as storage-effects]
            [background-effects.fs-effects :as fs-effects]
            [background-effects.sponsor-effects :as sponsor-effects]
            [background-effects.banner-effects :as banner-effects]
            [background-effects.runtime-effects :as runtime-effects]
            [background-effects.msg-effects :as msg-effects]
            [background-effects.script-effects :as script-effects]
            [background-effects.ext-dep-effects :as ext-dep-effects]))

(def ^:private config js/EXTENSION_CONFIG)

;; Note: Use def (not defonce) for state that should reset on script wake.
;; WebSocket connections don't survive script termination anyway.

(def !state (atom {:init/promise nil
                   :storage/ext-dep-cache {}
                   :ws/connections {}
                   :icon/states {}
                   :connected-tabs/history {}
                   :fs/sync-tab-id nil
                   :runtime/errors {}}))  ; tab-id -> {:port ws-port} - tracks intentionally connected tabs

;; Ephemeral tracking - NOT Uniflow state. Tracks which tabs have had the
;; web installer injected to avoid redundant re-injection.
(def ^:private installer-injected-tabs* (atom #{}))

;; Ephemeral tracking - NOT Uniflow state. Tracks scans that have started but
;; have not yet reached the injected-tab mark, preventing overlapping events
;; from entering the installer scan concurrently for the same tab.
(def ^:private installer-in-flight-tabs* (atom #{}))

;; ============================================================
;; Initialization Promise - single source of truth for readiness
;; ============================================================

;; Use a mutable variable (not defonce) so each script wake gets fresh state.
;; The :init/promise key ensures all operations wait for storage to load.

(defn ^:async ensure-initialized!
  "Returns a promise that resolves when initialization is complete.
   Safe to call multiple times - only initializes once per script lifetime.
   Works by dispatching an action that handles both 'already initialized'
   and 'needs initialization' cases. dispatch! returns a promise via
   the :uf/await effect chain."
  [dispatch!]
  (js-await (dispatch! [[:init/ax.ensure-initialized]])))



;; ============================================================
;; Auto-Injection: Run userscripts on page load
;; ============================================================
;; Injection functions extracted to bg-inject module

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

;; Page-context function for injecting scripts (used for ws-bridge.js)
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
    ;; Inject Epupp API for manifest! support
    (js-await (inject-epupp-api! tab-id))
    true))

(defn ^:async get-auto-connect-settings
  "Get auto-connect REPL settings from storage.
   Returns {:enabled? boolean :ws-port string} or nil if disabled."
  []
  (js/Promise.
   (fn [resolve]
     (js/chrome.storage.local.get
      #js ["autoConnectRepl"]
      (fn [result]
        (let [enabled (.-autoConnectRepl result)]
          (resolve {:enabled? (boolean enabled)})))))))

(defn ^:async get-auto-reconnect-setting
  "Get auto-reconnect REPL setting from storage.
   Returns true if enabled (defaults to true if not set)."
  []
  (js/Promise.
   (fn [resolve]
     (js/chrome.storage.local.get
      #js ["autoReconnectRepl"]
      (fn [result]
        (let [value (.-autoReconnectRepl result)]
          ;; Default to true if not set
          (resolve (if (some? value) value true))))))))

(defn ^:async get-auto-connect-level
  "Get auto-connect level from storage with migration fallback.
   Reads autoConnectLevel first; if absent, falls back to legacy
   autoConnectRepl: true -> 'all-pages', false/absent -> 'off'."
  [legacy-enabled?]
  (js/Promise.
   (fn [resolve]
     (js/chrome.storage.local.get
      #js ["autoConnectLevel"]
      (fn [result]
        (let [level (.-autoConnectLevel result)]
          (resolve (if (some? level)
                     level
                     (if legacy-enabled? "all-pages" "off")))))))))

(defn ^:async get-tab-hostname
  "Get hostname for a specific tab to look up its saved port."
  [tab-id]
  (js/Promise.
   (fn [resolve]
     (js/chrome.tabs.get
      tab-id
      (fn [tab]
        (if js/chrome.runtime.lastError
          (resolve "default")
          (try
            (resolve (.-hostname (js/URL. (.-url tab))))
            (catch :default _ (resolve "default")))))))))

(defn ^:async get-saved-ws-port
  "Get saved WebSocket port for a tab's hostname.
   Falls back to user-configured default port, then to 3340."
  [tab-id]
  (let [hostname (js-await (get-tab-hostname tab-id))
        key (str "ports_" hostname)]
    (js/Promise.
     (fn [resolve]
       (js/chrome.storage.local.get
        #js [key "defaultWsPort"]
        (fn [result]
          (let [saved (aget result key)
                has-override? (and saved (.-wsPort saved))]
            (if has-override?
              (resolve (str (.-wsPort saved)))
              (let [default-port (aget result "defaultWsPort")]
                (resolve (str (or default-port "3340"))))))))))))
 ; default ws port

(defn- ^:async execute-idle-scripts!
  "Execute matching idle scripts for a tab, handling resolution errors."
  [dispatch! tab-id idle-scripts icon-state]
  (log/debug "Background:Inject" "Found" (count idle-scripts) "document-idle scripts")
  (js-await (test-logger/log-event! "AUTO_INJECT_START" {:count (count idle-scripts)}))
  (when (some #(= bg-utils/sponsor-script-id (:script/id %)) idle-scripts)
    (dispatch! [[:sponsor/ax.set-pending tab-id]]))
  (try
    (let [all-scripts (storage/get-scripts)
          plan (dep-resolver/resolve-execution-plan (vec idle-scripts) all-scripts
                                                    (storage/get-ext-dep-cache))
          errors (:plan/errors plan)]
      (when (seq errors)
        (doseq [err errors]
          (log/error "Background:Resolve" (:error/message err))
          (js-await (test-logger/log-event! "RESOLUTION_ERROR"
                                            {:script (:error/script-name err)
                                             :dep (:error/dep-raw err)
                                             :message (:error/message err)})))
        (dispatch! [[:banner/ax.broadcast-resolution-errors errors]
                    [:runtime/ax.set-tab-errors tab-id errors]]))
      (js-await (bg-inject/ensure-scittle! dispatch! tab-id icon-state))
      (js-await (bg-inject/execute-plan! tab-id plan)))
    (catch :default err
      (log/error "Background:Inject" "Failed:" (.-message err))
      (js-await (test-logger/log-event! "AUTO_INJECT_ERROR" {:error (.-message err)})))))

(defn ^:async process-navigation!
  "Process a navigation event after ensuring initialization is complete.
   Find matching scripts, resolve dependencies via dep-resolver, and execute the plan.
   Only processes document-idle scripts - early-timing scripts are handled
   by registered content scripts (see registration.cljs).
   Checks host permission before injection (Firefox treats these as revocable).
   On resolution errors: skips failing scripts, logs to console, broadcasts banner."
  [dispatch! tab-id url icon-state]
  (let [has-perm? (js-await (permissions/check-tab-permission tab-id))]
    (if has-perm?
      (let [matching-scripts (url-matching/get-matching-scripts url)
            idle-scripts (filter #(= "document-idle"
                                     (or (:script/run-at %) "document-idle"))
                                 matching-scripts)]
        (js-await (test-logger/log-event! "NAVIGATION_PROCESSED"
                                          {:url url
                                           :all-scripts-count (count matching-scripts)
                                           :idle-scripts-count (count idle-scripts)}))
        (when (seq idle-scripts)
          (js-await (execute-idle-scripts! dispatch! tab-id idle-scripts icon-state))))
      (log/debug "Background:Inject" "Skipping navigation - host permission not granted for" url))))

(defn- handle-ws-connect [message tab-id dispatch!]
  (dispatch! [[:ws/ax.handle-connect tab-id (.-port message)]])
  false)

(defn- handle-ws-send [message tab-id dispatch!]
  (dispatch! [[:ws/ax.handle-send tab-id (.-data message)]])
  false)

(defn- handle-ws-close [tab-id dispatch!]
  (dispatch! [[:ws/ax.handle-close tab-id]])
  false)

(defn- handle-list-scripts [message tab-id dispatch! send-response]
  (let [include-hidden? (.-lsHidden message)]
    (dispatch! [[:fs/ax.guard-list-scripts tab-id send-response include-hidden?]])
    true))

(defn- handle-save-script [message tab-id dispatch! send-response]
  (let [script-source (.-scriptSource message)
        web-install? (and script-source
                          (or (.startsWith script-source "http://")
                              (.startsWith script-source "https://")))
        raw-data {:code (.-code message)
                  :enabled (if (some? (.-enabled message)) (.-enabled message) true)
                  :force? (.-force message)
                  :bulk-id (.-bulkId message)
                  :bulk-index (.-bulkIndex message)
                  :bulk-count (.-bulkCount message)
                  :script-source script-source}]
    (dispatch! [[:fs/ax.guard-save-script tab-id send-response raw-data web-install?]])
    true))

(defn- handle-panel-save-script [message send-response]
  (let [js-script (.-script message)
        script (cond-> {:script/name (.-name js-script)
                        :script/description (.-description js-script)
                        :script/match (vec (.-match js-script))
                        :script/code (.-code js-script)
                        :script/enabled (.-enabled js-script)
                        :script/run-at (script-utils/normalize-run-at (.-runAt js-script))
                        :script/inject (if (.-inject js-script)
                                         (vec (.-inject js-script))
                                         [])}
                 (.-id js-script) (assoc :script/id (.-id js-script))
                 (.-force js-script) (assoc :script/force? true))]
    (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.save-script script]))
  true)

(defn- handle-panel-rename-script [message send-response]
  (let [from-name (.-from message)
        to-name (.-to message)]
    (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.rename-script from-name to-name]))
  true)

(defn- handle-rename-script [message tab-id dispatch! send-response]
  (let [from-name (.-from message)
        to-name (.-to message)
        force? (when (.-force message) true)
        action (cond-> [:fs/ax.guard-rename-script tab-id send-response from-name to-name]
                 force? (conj true))]
    (dispatch! [action])
    true))

(defn- handle-delete-script [message tab-id dispatch! send-response]
  (let [script-name (.-name message)
        bulk-id (.-bulkId message)
        bulk-index (.-bulkIndex message)
        bulk-count (.-bulkCount message)]
    (dispatch! [[:fs/ax.guard-delete-script tab-id send-response
                 {:script-name script-name
                  :bulk-id bulk-id
                  :bulk-index bulk-index
                  :bulk-count bulk-count}]])
    true))

(defn- handle-get-script [message tab-id dispatch! send-response]
  (let [script-name (.-name message)]
    (dispatch! [[:fs/ax.guard-get-script tab-id send-response script-name]])
    true))

(defn- handle-load-manifest [message tab-id dispatch! send-response]
  (let [manifest (.-manifest message)
        all-scripts (storage/get-scripts)]
    (dispatch! [[:msg/ax.load-manifest send-response tab-id manifest all-scripts]])
    true))

(defn- handle-get-connections [dispatch! send-response]
  (dispatch! [[:msg/ax.get-connections send-response]])
  false)

(defn- handle-loader-resolution-errors [message sender dispatch!]
  (let [tab-id (when (.-tab sender) (.. sender -tab -id))
        js-errors (.-errors message)
        errors (when js-errors
                 (mapv (fn [e]
                         {:error/type (.-errorType e)
                          :error/phase :resolve
                          :error/surface :early-loader
                          :error/script-name (.-scriptName e)
                          :error/dep-raw (.-depRaw e)
                          :error/dep-chain (when (.-depChain e) (vec (.-depChain e)))
                          :error/message (.-message e)})
                       (vec js-errors)))]
    (when (and tab-id (seq errors))
      (dispatch! [[:banner/ax.broadcast-resolution-errors errors]
                  [:runtime/ax.set-tab-errors tab-id errors]]))
    false))

(defn- handle-get-runtime-status [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    (dispatch! [[:runtime/ax.get-tab-errors send-response tab-id]])
    true))

(defn- handle-connect-tab [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)
        ws-port (.-wsPort message)]
    (dispatch! [[:msg/ax.connect-tab send-response target-tab-id ws-port]])
    true))

(defn- handle-check-status [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.check-status send-response target-tab-id]])
    true))

(defn- handle-disconnect-tab [message dispatch!]
  (let [target-tab-id (.-tabId message)]
    (dispatch! [[:ws/ax.explicit-disconnect target-tab-id]]))
  false)

(defn- handle-e2e-find-tab-id [message dispatch! send-response]
  (let [url-pattern (.-urlPattern message)]
    (dispatch! [[:msg/ax.e2e-find-tab-id send-response url-pattern]])
    true))

(defn- handle-e2e-get-test-events [dispatch! send-response]
  (dispatch! [[:msg/ax.e2e-get-test-events send-response]])
  true)

(defn- handle-e2e-get-storage [message dispatch! send-response]
  (let [key (.-key message)]
    (dispatch! [[:msg/ax.e2e-get-storage send-response key]])
    true))

(defn- handle-e2e-set-storage [message dispatch! send-response]
  (let [key (.-key message)
        value (.-value message)]
    (dispatch! [[:msg/ax.e2e-set-storage send-response key value]])
    true))

(defn- handle-e2e-activate-tab [message send-response]
  (let [tab-id (.-tabId message)]
    (js/chrome.tabs.update tab-id #js {:active true}
                           (fn [tab]
                             (if js/chrome.runtime.lastError
                               (send-response #js {:success false :error (.-message js/chrome.runtime.lastError)})
                               (js/chrome.windows.update (.-windowId tab) #js {:focused true}
                                                         (fn [_win]
                                                           (if js/chrome.runtime.lastError
                                                             (send-response #js {:success false :error (.-message js/chrome.runtime.lastError)})
                                                             (send-response #js {:success true :tabId tab-id})))))))
    true))

(defn- handle-e2e-update-icon [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    ((^:async fn []
       (try
         (js-await (dispatch! [[:icon/ax.refresh-toolbar tab-id]]))
         (send-response #js {:success true :tabId tab-id})
         (catch :default err
           (send-response #js {:success false :error (.-message err)})))))
    true))

(defn- handle-e2e-get-icon-display-state [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.e2e-get-icon-display-state send-response tab-id]])
    true))

(defn- handle-e2e-ensure-builtin [dispatch! send-response]
  ((^:async fn []
     (try
       (js-await (ensure-initialized! dispatch!))
       (js-await (storage/sync-builtin-scripts!))
       (send-response #js {:success true})
       (catch :default err
         (send-response #js {:success false :error (.-message err)})))))
  true)


(defn- handle-e2e-simulate-tab-visible [message dispatch! send-response]
  (let [tab-id (.-tabId message)]
    (dispatch! [[:visibility/ax.handle-tab-visible tab-id]])
    (send-response #js {:success true :tabId tab-id}))
  false)

(defn- handle-ensure-scittle [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.ensure-scittle send-response target-tab-id]])
    true))

(defn- handle-inject-libs [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)
        libs (when (.-libs message)
               (vec (.-libs message)))
        all-scripts (storage/get-scripts)]
    (dispatch! [[:msg/ax.inject-libs send-response target-tab-id libs all-scripts]])
    true))

(defn- handle-evaluate-script [message dispatch! send-response]
  (let [target-tab-id (.-tabId message)
        code (.-code message)
        libs (when (.-inject message)
               (vec (.-inject message)))]
    (dispatch! [[:msg/ax.evaluate-script send-response target-tab-id code libs (.-scriptId message)]])
    true))

(defn- handle-sponsor-status [_message sender dispatch! send-response]
  (let [tab-id (when (.-tab sender) (.. sender -tab -id))
        tab-url (when (.-tab sender) (.. sender -tab -url))]
    (dispatch! [[:sponsor/ax.consume-pending tab-id tab-url send-response]]))
  true)



(defn- ^:async update-sponsor-script-match!
  "Rewrite the sponsor script's auto-run-match URL to use the given username.
   This updates the source of truth (the code itself), so derive-script-fields
   naturally picks up the new match pattern on save."
  [username]
  (let [sponsor-script (storage/get-script "epupp-builtin-sponsor-check")]
    (when sponsor-script
      (let [old-code (:script/code sponsor-script)
            new-code (.replace old-code
                               (js/RegExp. "https://github\\.com/sponsors/[^\"*]+" "g")
                               (str "https://github.com/sponsors/" username))
            updated (assoc sponsor-script :script/code new-code)]
        (when (not= old-code new-code)
          (js-await (storage/save-script! updated))
          (log/info "Background" "Updated sponsor script match to:" username))))))
(defn- handle-permission-granted [message dispatch!]
  (let [tab-id (.-tabId message)]
    (dispatch! [[:msg/ax.handle-permission-granted tab-id]])
    false))

(defn- handle-get-sponsored-username [_message send-response]
  ((^:async fn []
     (let [storage-result (js-await (js/chrome.storage.local.get #js ["sponsor/sponsored-username"]))
           username (or (aget storage-result "sponsor/sponsored-username") "PEZ")]
       (send-response #js {:success true :username username}))))
  true)
(defn- handle-check-script-exists [message _dispatch! send-response]
  (let [script-name (.-name message)
        code (.-code message)
        script (storage/get-script-by-name script-name)]
    (if script
      (send-response #js {:success true
                          :exists true
                          :identical (= code (:script/code script))})
      (send-response #js {:success true
                          :exists false}))
    false))

(defn- normalize-match-pattern
  "Normalize :epupp/auto-run-match to a vector of patterns."
  [auto-run-match]
  (cond
    (nil? auto-run-match) []
    (vector? auto-run-match) auto-run-match
    :else [auto-run-match]))

(defn- build-web-installer-script
  "Build a script map from web installer save data.
   Returns nil if the manifest has no script name."
  [code manifest sender]
  (let [{:keys [raw-script-name script-name auto-run-match inject run-at]} manifest
        raw-name (or raw-script-name script-name)
        effective-inject (or inject [])
        effective-run-at (or run-at "document-idle")]
    (when raw-name
      {:script/id (str (.now js/Date))
       :script/name raw-name
       :script/code code
       :script/match (normalize-match-pattern auto-run-match)
       :script/inject effective-inject
       :script/enabled true
       :script/run-at effective-run-at
       :script/force? true
       :script/source (.. sender -tab -url)})))

(defn- handle-web-installer-save-script [message sender _dispatch! send-response]
  (if-not (bg-utils/web-installer-origin-allowed? sender)
    (do (send-response #js {:success false :error "Domain not allowed for web installation"})
        false)
    (try
      (let [code (.-code message)
            manifest (manifest-parser/extract-manifest code)
            script (build-web-installer-script code manifest sender)]
        (if script
          (do (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.save-script script])
              true)
          (do (send-response #js {:success false :error "No script name in manifest"})
              false)))
      (catch :default err
        (send-response #js {:success false :error (str "Parse error: " (.-message err))})
        false))))

(defn- handle-tab-became-visible [tab-id dispatch!]
  (dispatch! [[:visibility/ax.handle-tab-visible tab-id]])
  false)

(defn- ^:async capture-visible-tab!
  "Capture screenshot of the visible area of a tab."
  [window-id format quality]
  (let [capture-opts (if (= format "jpeg")
                       #js {:format "jpeg" :quality quality}
                       #js {:format "png"})]
    (js-await (js/chrome.tabs.captureVisibleTab window-id capture-opts))))

(defn- handle-capture-element
  "Handle capture-element message: take viewport screenshot.
   Cropping to element rect is done page-side for cross-browser compatibility."
  [message sender send-response]
  (let [tab (.-tab sender)
        tab-id (when tab (.-id tab))
        window-id (when tab (.-windowId tab))
        format (or (.-format message) "jpeg")
        quality (or (.-quality message) 75)]
    (if-not tab-id
      (do (send-response #js {:success false :error "No tab context"})
          false)
      (do ((^:async fn []
             (try
               (let [data-url (js-await (capture-visible-tab! window-id format quality))]
                 (send-response #js {:success true :dataUrl data-url}))
               (catch :default err
                 (send-response #js {:success false
                                     :error (str "Capture failed: " (.-message err))})))))
          true))))

(defn- handle-unknown-message [msg-type]
  (log/debug "Background" "Unknown message type:" msg-type)
  false)

(defn- handle-e2e-message
  "Route e2e test messages. Only available in dev mode."
  [msg-type message dispatch! send-response]
  (if (.-dev config)
    (case msg-type
      "e2e/find-tab-id" (handle-e2e-find-tab-id message dispatch! send-response)
      "e2e/get-test-events" (handle-e2e-get-test-events dispatch! send-response)
      "e2e/get-storage" (handle-e2e-get-storage message dispatch! send-response)
      "e2e/set-storage" (handle-e2e-set-storage message dispatch! send-response)
      "e2e/activate-tab" (handle-e2e-activate-tab message send-response)
      "e2e/update-icon" (handle-e2e-update-icon message dispatch! send-response)
      "e2e/get-icon-display-state" (handle-e2e-get-icon-display-state message dispatch! send-response)
      "e2e/ensure-builtin" (handle-e2e-ensure-builtin dispatch! send-response)
      "e2e/simulate-tab-visible" (handle-e2e-simulate-tab-visible message dispatch! send-response)
      (handle-unknown-message msg-type))
    (do (send-response #js {:success false :error "Not available"})
        false)))

(defn- add-on-message-handler [dispatch!]
  (.addListener js/chrome.runtime.onMessage
                (fn [message sender send-response]
                  (let [tab-id (when (.-tab sender) (.. sender -tab -id))
                        msg-type (.-type message)]
                    (case msg-type
                      "ws-connect" (handle-ws-connect message tab-id dispatch!)
                      "ws-send" (handle-ws-send message tab-id dispatch!)
                      "ws-close" (handle-ws-close tab-id dispatch!)
                      "ping" false
                      "tab-became-visible" (handle-tab-became-visible tab-id dispatch!)
                      "list-scripts" (handle-list-scripts message tab-id dispatch! send-response)
                      "save-script" (handle-save-script message tab-id dispatch! send-response)
                      "panel-save-script" (handle-panel-save-script message send-response)
                      "panel-rename-script" (handle-panel-rename-script message send-response)
                      "rename-script" (handle-rename-script message tab-id dispatch! send-response)
                      "delete-script" (handle-delete-script message tab-id dispatch! send-response)
                      "get-script" (handle-get-script message tab-id dispatch! send-response)
                      "check-script-exists" (handle-check-script-exists message dispatch! send-response)
                      "web-installer-save-script" (handle-web-installer-save-script message sender dispatch! send-response)
                      "load-manifest" (handle-load-manifest message tab-id dispatch! send-response)
                      "get-connections" (handle-get-connections dispatch! send-response)
                      "get-runtime-status" (handle-get-runtime-status message dispatch! send-response)
                      "loader-resolution-errors" (handle-loader-resolution-errors message sender dispatch!)
                      "connect-tab" (handle-connect-tab message dispatch! send-response)
                      "check-status" (handle-check-status message dispatch! send-response)
                      "disconnect-tab" (handle-disconnect-tab message dispatch!)
                      "toggle-fs-sync"
                      (do (dispatch! [[:fs/ax.toggle-sync (.-tabId message) (.-enabled message) send-response]])
                          true)
                      "get-fs-sync-status"
                      (do (dispatch! [[:fs/ax.get-sync-status send-response]])
                          true)
                      "capture-element" (handle-capture-element message sender send-response)
                      "ensure-scittle" (handle-ensure-scittle message dispatch! send-response)
                      "inject-libs" (handle-inject-libs message dispatch! send-response)
                      "evaluate-script" (handle-evaluate-script message dispatch! send-response)
                      "sponsor-status" (handle-sponsor-status message sender dispatch! send-response)
                      "get-sponsored-username" (handle-get-sponsored-username message send-response)
                      "permission-granted" (handle-permission-granted message dispatch!)
                      ;; Default: e2e test messages or unknown
                      (if (.startsWith msg-type "e2e/")
                        (handle-e2e-message msg-type message dispatch! send-response)
                        (handle-unknown-message msg-type)))))))

(defn- ^:async delay-ms!
  "Wait for the given number of milliseconds. No-op for zero or negative."
  [ms]
  (when (pos? ms)
    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))))

(defn- ^:async scan-with-delay!
  "Wait, then scan for userscript blocks. Returns truthy if found."
  [tab-id delay-ms]
  (js-await (delay-ms! delay-ms))
  (js-await (bg-inject/execute-in-isolated tab-id bg-inject/scan-for-userscripts-fn)))

(defn- ^:async scan-for-userscripts-with-retry!
  "Try scanning for userscript blocks with bounded retry delays.
   Returns true if found, falsy if not."
  [tab-id]
  (loop [remaining bg-utils/installer-scan-delays]
    (when (seq remaining)
      (or (js-await (scan-with-delay! tab-id (first remaining)))
          (recur (rest remaining))))))

(defn- ^:async inject-installer-for-tab!
  "Inject the web userscript installer for a tab that has userscript blocks."
  [dispatch! tab-id]
  (let [installer (storage/get-script-by-name "epupp/web_userscript_installer.cljs")]
    (when (and installer (:script/enabled installer))
      (when (js-await (scan-for-userscripts-with-retry! tab-id))
        (js-await (bg-inject/ensure-scittle! dispatch! tab-id :disconnected))
        (let [all-scripts (storage/get-scripts)
              plan (dep-resolver/resolve-execution-plan [installer] all-scripts
                                                       (storage/get-ext-dep-cache))]
          (js-await (bg-inject/execute-plan! tab-id plan)))
        (swap! installer-injected-tabs* conj tab-id)))))

(defn- ^:async maybe-inject-installer!
  "Scan a tab for userscript blocks and inject the installer if found.
   Only scans on whitelisted origins. Skips if already injected on this tab.
   Checks host permission before injection (Firefox treats these as revocable)."
  [dispatch! tab-id url]
  (try
    (when (bg-utils/should-scan-for-installer? url
                                               (deref installer-injected-tabs*)
                                               (deref installer-in-flight-tabs*)
                                               tab-id)
      (swap! installer-in-flight-tabs* conj tab-id)
      (try
        (let [has-perm? (js-await (permissions/check-tab-permission tab-id))]
          (if has-perm?
            (do
              (js-await (ensure-initialized! dispatch!))
              (js-await (inject-installer-for-tab! dispatch! tab-id)))
            (log/debug "Background" "Installer scan skipped - host permission not granted for tab" tab-id)))
        (finally
          (swap! installer-in-flight-tabs* disj tab-id))))
    (catch :default err
      (log/warn "Background" "Installer scan failed for tab" tab-id ":" (.-message err)))))

(defn- register-tab-listeners! [dispatch!]
  (.addListener js/chrome.tabs.onRemoved
                (fn [tab-id _remove-info]
                  (log/debug "Background" "Tab closed, cleaning up:" tab-id)
                  (swap! installer-injected-tabs* disj tab-id)
                  (dispatch! [[:tab/ax.handle-removed tab-id]])))

  (.addListener js/chrome.tabs.onActivated
                (fn [active-info]
                  (let [tab-id (.-tabId active-info)]
                    (-> (js/chrome.tabs.get tab-id)
                        (.then (fn [tab]
                                 (let [url (or (.-url tab) "")]
                                   (when (and (seq url)
                                              (not (.startsWith url "chrome-extension://"))
                                              (not (.startsWith url "about:")))
                                     (dispatch! [[:icon/ax.refresh-toolbar tab-id]
                                                 [:visibility/ax.handle-tab-visible tab-id]])))))
                        (.catch (fn [_] nil)))))))

(defn- register-navigation-listeners! [dispatch!]
  (.addListener js/chrome.webNavigation.onBeforeNavigate
                (fn [details]
                  (when (zero? (.-frameId details))
                    (let [tab-id (.-tabId details)]
                      (swap! installer-injected-tabs* disj tab-id)
                      (dispatch! [[:nav/ax.handle-before-navigate tab-id]])))))

  (.addListener js/chrome.webNavigation.onCompleted
                (fn [details]
                  (let [url (.-url details)]
                    (when (and (zero? (.-frameId details))
                               (:scriptable? (page-scriptability/check-page-scriptability
                                              url (page-scriptability/detect-browser-type))))
                      (dispatch! [[:nav/ax.handle-navigation (.-tabId details) url]])
                      (maybe-inject-installer! dispatch! (.-tabId details) url)))))

  (.addListener js/chrome.webNavigation.onHistoryStateUpdated
                (fn [details]
                  (when (zero? (.-frameId details))
                    (maybe-inject-installer! dispatch! (.-tabId details)
                                             (.-url details))))))

(defn- register-alarm-listener! [dispatch!]
  (.addListener js/chrome.alarms.onAlarm
                (fn [alarm]
                  (when (= "ws-keepalive" (.-name alarm))
                    (dispatch! [[:alarm/ax.tick]])))))

(defn- handle-scripts-changed! [dispatch! scripts-change]
  (log/debug "Background" "Scripts changed, syncing registrations")
  ((^:async fn []
     (js-await (ensure-initialized! dispatch!))
     (js-await (registration/sync-registrations!))
     (let [all-scripts (script-utils/parse-scripts
                        (.-newValue scripts-change)
                        {:extract-manifest manifest-parser/extract-manifest})
           all-inject-urls (mapcat :script/inject all-scripts)
           ext-urls (ext-dep/extract-ext-dep-urls (vec all-inject-urls))]
       (dispatch! (if (seq ext-urls)
                    [[:ext-dep/ax.resolve-uncached-urls
                      ext-urls
                      [[:runtime/ax.re-resolve-on-change all-scripts]]]]
                    [[:runtime/ax.re-resolve-on-change all-scripts]]))))))

(defn- handle-ext-dep-cache-changed! [dispatch! changes]
  (log/debug "Background" "Ext dep cache changed, re-resolving")
  ((^:async fn []
     (js-await (ensure-initialized! dispatch!))
     (let [change (aget changes "extDepCache")
           new-cache (or (.-newValue change) {})
           all-scripts (storage/get-scripts)]
       (dispatch! [[:storage/ax.set-ext-dep-cache new-cache]
                   [:runtime/ax.re-resolve-on-change all-scripts]])))))

(defn- handle-debug-logging-changed! [changes]
  (let [change (aget changes "settings/debug-logging")
        enabled (boolean (.-newValue change))]
    (log/set-debug-enabled! enabled)))

(defn- handle-sponsor-username-changed! [dispatch! changes]
  (let [change (aget changes "sponsor/sponsored-username")
        new-username (or (.-newValue change) "PEZ")]
    ((^:async fn []
       (js-await (ensure-initialized! dispatch!))
       (js-await (update-sponsor-script-match! new-username))))))

(defn- handle-storage-change! [dispatch! changes]
  (when-let [scripts-change (.-scripts changes)]
    (handle-scripts-changed! dispatch! scripts-change))
  (when (aget changes "extDepCache")
    (handle-ext-dep-cache-changed! dispatch! changes))
  (when (aget changes "settings/debug-logging")
    (handle-debug-logging-changed! changes))
  (when (aget changes "sponsor/sponsored-username")
    (handle-sponsor-username-changed! dispatch! changes)))

(defn- register-storage-listener! [dispatch!]
  (.addListener js/chrome.storage.onChanged
                (fn [changes area]
                  (when (= area "local")
                    (handle-storage-change! dispatch! changes)))))

(defn- register-lifecycle-listeners! [dispatch!]
  (.addListener js/chrome.runtime.onInstalled
                (fn [details]
                  (log/info "Background" "onInstalled:" (.-reason details))
                  (js/chrome.storage.local.remove #js ["sponsor/sponsored-username"])
                  (ensure-initialized! dispatch!)))

  (.addListener js/chrome.runtime.onStartup
                (fn []
                  (log/info "Background" "onStartup")
                  (ensure-initialized! dispatch!))))

(defn- ^:async activate!
  [dispatch!]
  (log/info "Background" "Service worker started")
  (test-logger/install-global-error-handlers! "background" js/self)
  (js-await (bg-icon/prune-icon-states! dispatch!))
  (add-on-message-handler dispatch!)
  (register-tab-listeners! dispatch!)
  (register-navigation-listeners! dispatch!)
  (register-alarm-listener! dispatch!)
  (register-storage-listener! dispatch!)
  (register-lifecycle-listeners! dispatch!)
  (ensure-initialized! dispatch!)
  (log/info "Background" "Listeners registered"))

;; ============================================================
;; Uniflow Dispatch - placed here after all helpers
;; ============================================================

(defn- ^:async init-effect!
  "Perform the initialization effect: load storage, sync registrations,
   resolve uncached ext deps, and log startup."
  [dispatch! resolve reject]
  (try
    (js-await (test-logger/init-test-mode!))
    (js-await (storage/init!))
    (js-await (dispatch! [[:storage/ax.set-ext-dep-cache (storage/get-ext-dep-cache)]]))
    (js-await (js/Promise.
               (fn [res]
                 (js/chrome.storage.local.get
                  #js ["settings/debug-logging"]
                  (fn [result]
                    (let [enabled (boolean (aget result "settings/debug-logging"))]
                      (log/set-debug-enabled! enabled)
                      (res true)))))))
    (js-await (registration/sync-registrations!))
    (let [all-scripts (storage/get-scripts)
          all-inject-urls (mapcat :script/inject all-scripts)
          ext-urls (ext-dep/extract-ext-dep-urls (vec all-inject-urls))]
      (when (seq ext-urls)
        (dispatch! [[:ext-dep/ax.resolve-uncached-urls ext-urls]])))
    (log/info "Background" "Initialization complete")
    (js-await (test-logger/log-event! "EXTENSION_STARTED"
                                      {:version (.-version (.getManifest js/chrome.runtime))}))
    (resolve true)
    (catch :default err
      (log/error "Background" "Initialization failed:" err)
      (dispatch! [[:init/ax.clear-promise]])
      (reject err))))

(defn- ^:async gather-auto-connect-context!
  "Gather navigation context for auto-connect decision."
  [dispatch! tab-id url history]
  (js-await (ensure-initialized! dispatch!))
  (js-await (test-logger/log-event! "NAVIGATION_STARTED" {:tab-id tab-id :url url}))
  (let [{:keys [enabled?]} (js-await (get-auto-connect-settings))
        auto-reconnect? (js-await (get-auto-reconnect-setting))
        auto-connect-level (js-await (get-auto-connect-level enabled?))
        saved-port (js-await (get-saved-ws-port tab-id))
        in-history? (bg-utils/tab-in-history? history tab-id)
        history-port (when in-history? (bg-utils/get-history-port history tab-id))]
    {:nav/tab-id tab-id
     :nav/url url
     :nav/auto-connect-enabled? (boolean enabled?)
     :nav/auto-reconnect-enabled? (boolean auto-reconnect?)
     :nav/auto-connect-level auto-connect-level
     :nav/in-history? in-history?
     :nav/history-port history-port
     :nav/saved-port saved-port}))

(defn- ^:async handle-permission-granted-effect!
  "Handle permission-granted effect: initialize, process navigation, maybe inject installer."
  [dispatch! tab-id icon-state]
  (try
    (js-await (ensure-initialized! dispatch!))
    (let [tab (js-await (js/chrome.tabs.get tab-id))
          url (.-url tab)
          scriptable? (and url
                           (:scriptable? (page-scriptability/check-page-scriptability
                                          url (page-scriptability/detect-browser-type))))]
      (when scriptable?
        (js-await (process-navigation! dispatch! tab-id url icon-state))
        (js-await (maybe-inject-installer! dispatch! tab-id url))))
    (catch :default err
      (log/warn "Background" "Permission-granted handling failed:" (.-message err)))))

(defn- ^:async connect-tab-effect! [dispatch! tab-id ws-port icon-state]
  (try
    (js-await (connect-tab! dispatch! tab-id ws-port icon-state))
    {:success true}
    (catch :default err
      {:success false :error (.-message err)})))

(defn- ^:async check-status-effect! [tab-id]
  (try
    (let [status (js-await (bg-inject/execute-in-page tab-id check-status-fn))]
      {:success true :status status})
    (catch :default err
      {:success false :error (.-message err)})))

(defn- ^:async find-by-url-pattern-effect! [url-pattern]
  (try
    (let [found-tab-id (js-await (find-tab-id-by-url-pattern! url-pattern))]
      (if found-tab-id
        {:success true :tabId found-tab-id}
        {:success false :error "No tab found"}))
    (catch :default err
      {:success false :error (.-message err)})))

(defn- ^:async nav-connect-effect! [dispatch! tab-id port icon-state]
  (try
    (js-await (test-logger/log-event! "NAV_AUTO_CONNECT" {:tab-id tab-id :port port}))
    (js-await (connect-tab! dispatch! tab-id port icon-state))
    (log/info "Background:AutoConnect" "Successfully connected REPL to tab:" tab-id)
    {:success true}
    (catch :default err
      (log/warn "Background:AutoConnect" "Failed to connect REPL:" (.-message err))
      {:success false :error (.-message err)})))

(defn- ^:async gather-reconnect-context! [tab-id history]
  (let [{:keys [enabled?]} (js-await (get-auto-connect-settings))
        auto-connect-level (js-await (get-auto-connect-level enabled?))
        in-history? (bg-utils/tab-in-history? history tab-id)
        history-port (when in-history? (bg-utils/get-history-port history tab-id))
        saved-port (js-await (get-saved-ws-port tab-id))]
    {:visibility/tab-id tab-id
     :visibility/auto-connect-level auto-connect-level
     :visibility/history-port history-port
     :visibility/saved-port saved-port}))

(defn- ^:async route-effect-by-namespace
  "Route effects to domain-specific effect modules by namespace."
  [dispatch! effect args]
  (let [ns (utils/kw-namespace effect)]
    (case ns
      "ws" (ws-effects/perform-effect! dispatch! effect args)
      "icon" (icon-effects/perform-effect! dispatch! effect args)
      "alarm" (alarm-effects/perform-effect! dispatch! effect args)
      "storage" (storage-effects/perform-effect! dispatch! effect args)
      "fs" (fs-effects/perform-effect! dispatch! effect args)
      "sponsor" (sponsor-effects/perform-effect! dispatch! effect args)
      "banner" (banner-effects/perform-effect! dispatch! effect args)
      "runtime" (runtime-effects/perform-effect! dispatch! effect args)
      "msg" (msg-effects/perform-effect! dispatch! effect args)
      "script" (script-effects/perform-effect! dispatch! effect args)
      "ext-dep" (ext-dep-effects/perform-effect! dispatch! effect args)
      :uf/unhandled-fx)))

(defn ^:async perform-effect! [dispatch! [effect & args]]
  (case effect
    :init/fx.await-promise
    (js-await (first args))

    :init/fx.initialize
    (let [[resolve reject] args]
      (js-await (init-effect! dispatch! resolve reject)))

    :repl/fx.connect-tab
    (let [[tab-id ws-port icon-state] args]
      (js-await (connect-tab-effect! dispatch! tab-id ws-port icon-state)))

    :page/fx.check-status
    (js-await (check-status-effect! (first args)))

    :tabs/fx.find-by-url-pattern
    (js-await (find-by-url-pattern-effect! (first args)))

    :nav/fx.gather-auto-connect-context
    (let [[tab-id url history] args]
      (js-await (gather-auto-connect-context! dispatch! tab-id url history)))

    :nav/fx.connect
    (let [[tab-id port icon-state] args]
      (js-await (nav-connect-effect! dispatch! tab-id port icon-state)))

    :nav/fx.process-navigation
    (let [[tab-id url icon-state] args]
      (js-await (process-navigation! dispatch! tab-id url icon-state)))

    :visibility/fx.gather-reconnect-context
    (let [[tab-id history] args]
      (js-await (gather-reconnect-context! tab-id history)))

    :msg/fx.handle-permission-granted
    (let [[tab-id icon-state] args]
      (js-await (handle-permission-granted-effect! dispatch! tab-id icon-state)))

    (js-await (route-effect-by-namespace dispatch! effect args))))

(defn dispatch!
  "Dispatch background actions through Uniflow."
  [actions]
  (event-handler/dispatch! !state bg-actions/handle-action perform-effect! actions))

(activate! dispatch!)