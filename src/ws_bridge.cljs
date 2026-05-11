(ns ws-bridge
  "WebSocket bridge wrapper for page context.
   Runs in MAIN world and communicates with content script bridge via postMessage.")

(defn- bridge-log
  "Send log messages via postMessage to content_bridge for routing through log namespace."
  [level & args]
  (.postMessage js/window
                #js {:source "epupp-page"
                     :type "log"
                     :level level
                     :subsystem "WsBridge"
                     :messages (to-array args)}
                "*"))

;; Centralized state with namespaced keys
(def !state (atom {:bridge/ready? false
                   :ws/message-handler nil}))

(defn- handle-bridge-ready [event]
  (when (= (.-source event) js/window)
    (let [msg (.-data event)]
      (when (and msg
                 (= "epupp-bridge" (.-source msg))
                 (= "bridge-ready" (.-type msg)))
        (bridge-log :debug "Bridge is ready")
        (swap! !state assoc :bridge/ready? true)))))

(defn- handle-ws-bridge-event!
  "Handle a single WebSocket bridge event, updating ws-obj state and calling callbacks."
  [ws-obj msg-type msg]
  (case msg-type
    "ws-open"
    (do
      (bridge-log :debug "WebSocket OPEN")
      (set! (.-readyState ws-obj) 1)
      (when-let [onopen (.-onopen ws-obj)]
        (onopen)))

    "ws-message"
    (when-let [onmessage (.-onmessage ws-obj)]
      (onmessage #js {:data (.-data msg)}))

    "ws-error"
    (do
      (bridge-log :error "WebSocket ERROR")
      (set! (.-readyState ws-obj) 3)
      (when-let [onerror (.-onerror ws-obj)]
        (onerror (js/Error. (or (.-error msg) "WebSocket error")))))

    "ws-close"
    (do
      (bridge-log :debug "WebSocket CLOSED")
      (set! (.-readyState ws-obj) 3)
      (when-let [onclose (.-onclose ws-obj)]
        (onclose)))

    nil))

(defn- make-bridge-message-handler
  "Create a message event handler that routes bridge messages to ws-obj."
  [ws-obj]
  (fn [event]
    (when (= (.-source event) js/window)
      (let [msg (.-data event)]
        (when (and msg (= "epupp-bridge" (.-source msg)))
          (handle-ws-bridge-event! ws-obj (.-type msg) msg))))))

(defn- init-ws-obj!
  "Initialize a WebSocket-like object with properties and constants."
  [url]
  (let [ws-obj (js-obj)]
    (set! (.-url ws-obj) url)
    (set! (.-readyState ws-obj) 0)
    (set! (.-onopen ws-obj) nil)
    (set! (.-onmessage ws-obj) nil)
    (set! (.-onerror ws-obj) nil)
    (set! (.-onclose ws-obj) nil)
    (set! (.-CONNECTING ws-obj) 0)
    (set! (.-OPEN ws-obj) 1)
    (set! (.-CLOSING ws-obj) 2)
    (set! (.-CLOSED ws-obj) 3)
    ws-obj))

(defn- attach-ws-methods!
  "Attach send and close methods to a ws-obj."
  [ws-obj]
  (set! (.-send ws-obj)
        (fn [data]
          (when (= 1 (.-readyState ws-obj))
            (.postMessage js/window
                          #js {:source "epupp-page"
                               :type "ws-send"
                               :data data}
                          "*"))))
  (set! (.-close ws-obj)
        (fn []
          (set! (.-readyState ws-obj) 3)
          (when-let [handler (:ws/message-handler @!state)]
            (.removeEventListener js/window "message" handler)
            (swap! !state assoc :ws/message-handler nil))
          (when-let [onclose (.-onclose ws-obj)]
            (onclose)))))

(defn bridged-websocket [url]
  (bridge-log :debug "Creating bridged WebSocket for:" url)
  ;; Clean up any existing message handler from previous connection
  (when-let [old-handler (:ws/message-handler @!state)]
    (bridge-log :debug "Removing old message handler")
    (.removeEventListener js/window "message" old-handler)
    (swap! !state assoc :ws/message-handler nil))
  (let [ws-obj (init-ws-obj! url)
        port (if-let [match (.match url #":(\d+)/")]
               (aget match 1)
               "1340")
        message-handler (make-bridge-message-handler ws-obj)]
    (swap! !state assoc :ws/message-handler message-handler)
    (.addEventListener js/window "message" message-handler)
    (attach-ws-methods! ws-obj)
    ;; Request connection through bridge
    (.postMessage js/window
                  #js {:source "epupp-page"
                       :type "ws-connect"
                       :port port}
                  "*")
    ws-obj))

;; Initialize - guard against multiple injections
(when-not js/window.__browserJackInWSBridge
  (set! js/window.__browserJackInWSBridge true)
  (bridge-log :debug "Installing WebSocket bridge")

  ;; Wait for bridge ready signal
  (.addEventListener js/window "message" handle-bridge-ready)

  ;; Store original WebSocket
  (set! (.-_OriginalWebSocket js/window) js/WebSocket)

  ;; Override WebSocket for nREPL URLs only
  (set! js/WebSocket
        (fn [url protocols]
          (if (and (string? url) (.includes url "/_nrepl"))
            (do
              (bridge-log :debug "Intercepting nREPL WebSocket:" url)
              (let [ws (bridged-websocket url)]
            ;; Store reference for Scittle's usage
                (set! (.-ws_nrepl js/window) ws)
                ws))
            (new (.-_OriginalWebSocket js/window) url protocols))))

  ;; Copy static properties
  (set! (.-CONNECTING js/WebSocket) 0)
  (set! (.-OPEN js/WebSocket) 1)
  (set! (.-CLOSING js/WebSocket) 2)
  (set! (.-CLOSED js/WebSocket) 3)

  (bridge-log :debug "WebSocket bridge installed"))
