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
            [bg-icon :as bg-icon]
            [bg-inject :as bg-inject]
            [bg-connect :as bg-connect]
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
            [background-effects.ext-dep-effects :as ext-dep-effects]
            [bg-message-handlers :as msg-handlers]
            [bg-web-installer :as web-installer]
            [bg-settings :as bg-settings]))

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

(defn- register-tab-listeners! [dispatch!]
  (.addListener js/chrome.tabs.onRemoved
                (fn [tab-id _remove-info]
                  (log/debug "Background" "Tab closed, cleaning up:" tab-id)
                  (swap! web-installer/installer-injected-tabs* disj tab-id)
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
                      (swap! web-installer/installer-injected-tabs* disj tab-id)
                      (dispatch! [[:nav/ax.handle-before-navigate tab-id]])))))

  (.addListener js/chrome.webNavigation.onCompleted
                (fn [details]
                  (let [url (.-url details)]
                    (when (and (zero? (.-frameId details))
                               (:scriptable? (page-scriptability/check-page-scriptability
                                              url (page-scriptability/detect-browser-type))))
                      (dispatch! [[:nav/ax.handle-navigation (.-tabId details) url]])
                      (web-installer/maybe-inject-installer! dispatch! ensure-initialized! (.-tabId details) url)))))

  (.addListener js/chrome.webNavigation.onHistoryStateUpdated
                (fn [details]
                  (when (zero? (.-frameId details))
                    (web-installer/maybe-inject-installer! dispatch! ensure-initialized! (.-tabId details)
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
  (msg-handlers/add-on-message-handler config ensure-initialized! dispatch!)
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
  (let [{:keys [enabled?]} (js-await (bg-settings/get-auto-connect-settings))
        auto-reconnect? (js-await (bg-settings/get-auto-reconnect-setting))
        auto-connect-level (js-await (bg-settings/get-auto-connect-level enabled?))
        saved-port (js-await (bg-settings/get-saved-ws-port tab-id))
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
        (js-await (web-installer/maybe-inject-installer! dispatch! ensure-initialized! tab-id url))))
    (catch :default err
      (log/warn "Background" "Permission-granted handling failed:" (.-message err)))))

(defn- ^:async connect-tab-effect! [dispatch! tab-id ws-port icon-state]
  (try
    (js-await (bg-connect/connect-tab! dispatch! tab-id ws-port icon-state))
    {:success true}
    (catch :default err
      {:success false :error (.-message err)})))

(defn- ^:async check-status-effect! [tab-id]
  (try
    (let [status (js-await (bg-inject/execute-in-page tab-id bg-connect/check-status-fn))]
      {:success true :status status})
    (catch :default err
      {:success false :error (.-message err)})))

(defn- ^:async find-by-url-pattern-effect! [url-pattern]
  (try
    (let [found-tab-id (js-await (bg-connect/find-tab-id-by-url-pattern! url-pattern))]
      (if found-tab-id
        {:success true :tabId found-tab-id}
        {:success false :error "No tab found"}))
    (catch :default err
      {:success false :error (.-message err)})))

(defn- ^:async nav-connect-effect! [dispatch! tab-id port icon-state]
  (try
    (js-await (test-logger/log-event! "NAV_AUTO_CONNECT" {:tab-id tab-id :port port}))
    (js-await (bg-connect/connect-tab! dispatch! tab-id port icon-state))
    (log/info "Background:AutoConnect" "Successfully connected REPL to tab:" tab-id)
    {:success true}
    (catch :default err
      (log/warn "Background:AutoConnect" "Failed to connect REPL:" (.-message err))
      {:success false :error (.-message err)})))

(defn- ^:async gather-reconnect-context! [tab-id history]
  (let [{:keys [enabled?]} (js-await (bg-settings/get-auto-connect-settings))
        auto-connect-level (js-await (bg-settings/get-auto-connect-level enabled?))
        in-history? (bg-utils/tab-in-history? history tab-id)
        history-port (when in-history? (bg-utils/get-history-port history tab-id))
        saved-port (js-await (bg-settings/get-saved-ws-port tab-id))]
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