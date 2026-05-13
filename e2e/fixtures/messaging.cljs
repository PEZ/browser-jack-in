(ns fixtures.messaging
  "Runtime message helpers for communicating with the background worker in E2E tests."
  )

(defn ^:async send-runtime-message
  "Send a message to the background worker via chrome.runtime.sendMessage.
   ext-page must be an extension page (popup or panel) that has access to chrome.runtime.
   Returns the response from the background worker."
  [ext-page msg-type data]
  (js-await
   (.evaluate ext-page
              (fn [opts]
                (js/Promise.
                 (fn [resolve]
                   (js/chrome.runtime.sendMessage
                    (js/Object.assign #js {:type (.-type opts)} (.-data opts))
                    resolve))))
              #js {:type msg-type :data (or data #js {})})))

(defn- success? [result]
  (and result (.-success result)))

(defn- require-success!
  "Assert a runtime message response succeeded. Returns the result, throws on failure."
  [result error-context]
  (if (success? result)
    result
    (throw (js/Error. (str error-context ": " (or (.-error result) "unknown error"))))))

(defn ^:async clear-fs-scripts
  "Clear stored scripts except built-ins.
   Leaves built-ins intact to avoid races with sync-builtin-scripts!."
  [ext-page]
  (let [scripts-result (js-await (send-runtime-message ext-page "e2e/get-storage" #js {:key "scripts"}))
        scripts (or (.-value scripts-result) #js [])
        builtins (.filter scripts
                          (fn [s]
                            (let [id (.-id s)]
                              (and id (.startsWith id "epupp-builtin-")))))
        _ (js-await (send-runtime-message ext-page "e2e/set-storage" #js {:key "scripts" :value builtins}))]
    true))

(defn ^:async find-tab-id
  "Find a tab matching the given URL pattern. Returns tab ID or throws."
  [ext-page url-pattern]
  (let [result (js-await (send-runtime-message ext-page "e2e/find-tab-id"
                                               #js {:urlPattern url-pattern}))]
    (.-tabId (require-success! result (str "Could not find tab matching: " url-pattern)))))

(defn ^:async connect-tab
  "Connect the REPL to a specific tab via WebSocket port. Returns true on success."
  [ext-page tab-id ws-port]
  (let [result (js-await (send-runtime-message ext-page "connect-tab"
                                               #js {:tabId tab-id :wsPort ws-port}))]
    (.-success (require-success! result "Connection failed"))))

(defn ^:async activate-tab
  "Activate a tab and focus its window via background message."
  [ext-page tab-id]
  (let [result (js-await (send-runtime-message ext-page "e2e/activate-tab" #js {:tabId tab-id}))]
    (.-success (require-success! result "Failed to activate tab"))))

(defn ^:async update-icon
  "Force icon update for a tab via background message (logs ICON_STATE_CHANGED)."
  [ext-page tab-id]
  (let [result (js-await (send-runtime-message ext-page "e2e/update-icon" #js {:tabId tab-id}))]
    (.-success (require-success! result "Failed to update icon"))))

(defn ^:async get-icon-display-state
  "Get the current display icon state for a tab.
   Returns state string (\"disconnected\", \"injected\", \"connected\") or throws."
  [ext-page tab-id]
  (let [result (js-await (send-runtime-message ext-page "e2e/get-icon-display-state" #js {:tabId tab-id}))]
    (.-state (require-success! result "Failed to get icon state"))))

(defn ^:async get-connections
  "Get active REPL connections from background worker.
   Returns a vector of connection maps on success."
  [ext-page]
  (let [result (js-await (send-runtime-message ext-page "get-connections" #js {}))]
    (.-connections (require-success! result "get-connections failed"))))

(defn ^:async wait-for-connection
  "Wait for WebSocket connection to be established after connect-tab.
   Polls get-connections until count is at least 1, or timeout.
   Returns the connection count."
  [ext-page timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [current-count (.-length (js-await (get-connections ext-page)))]
        (cond
          (pos? current-count) current-count
          (> (- (.now js/Date) start) (or timeout-ms 5000))
          (throw (js/Error. (str "Timeout waiting for connection. Count: " current-count)))
          :else (do
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
                  (recur)))))))

(defn ^:async clear-storage
  "Clear extension storage to ensure clean test state"
  [page]
  (js-await (.evaluate page "() => chrome.storage.local.clear()")))
