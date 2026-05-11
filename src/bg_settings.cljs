(ns bg-settings
  "Chrome storage readers for auto-connect and port settings.
   Pure async functions with no internal state.")

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
