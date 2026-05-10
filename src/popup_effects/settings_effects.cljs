(ns popup-effects.settings-effects)

(defn load-auto-connect-level! [dispatch]
  (js/chrome.storage.local.get
   #js ["autoConnectLevel" "autoConnectRepl"]
   (fn [result]
     (let [level (.-autoConnectLevel result)]
       (if (some? level)
         (dispatch [[:db/ax.assoc :settings/auto-connect-level level]])
         (let [legacy (.-autoConnectRepl result)
               migrated (if legacy "all-pages" "off")]
           (dispatch [[:db/ax.assoc :settings/auto-connect-level migrated]])))))))

(defn save-auto-connect-level! [_dispatch level]
  (js/chrome.storage.local.set #js {:autoConnectLevel level})
  (js/chrome.storage.local.remove #js ["autoConnectRepl"]))

(defn load-auto-reconnect-setting! [dispatch]
  (js/chrome.storage.local.get
   #js ["autoReconnectRepl"]
   (fn [result]
     (let [enabled (if (some? (.-autoReconnectRepl result))
                     (.-autoReconnectRepl result)
                     true)]
       (dispatch [[:db/ax.assoc :settings/auto-reconnect-repl enabled]])))))

(defn save-auto-reconnect-setting! [_dispatch enabled]
  (js/chrome.storage.local.set #js {:autoReconnectRepl enabled}))

(defn load-fs-sync-status! [dispatch]
  (js/chrome.runtime.sendMessage
   #js {:type "get-fs-sync-status"}
   (fn [response]
     (when response
       (dispatch [[:db/ax.assoc :fs/sync-tab-id (.-fsSyncTabId response)]])))))

(defn toggle-fs-sync! [_dispatch tab-id enabled]
  (js/chrome.runtime.sendMessage
   #js {:type "toggle-fs-sync" :tabId tab-id :enabled enabled}
   (fn [_response]
     (when js/chrome.runtime.lastError nil))))

(defn load-debug-logging-setting! [dispatch]
  (js/chrome.storage.local.get
   #js ["settings/debug-logging"]
   (fn [result]
     (let [enabled (if (some? (aget result "settings/debug-logging"))
                     (aget result "settings/debug-logging")
                     false)]
       (dispatch [[:db/ax.assoc :settings/debug-logging enabled]])))))

(defn save-debug-logging-setting! [_dispatch enabled]
  (js/chrome.storage.local.set (clj->js {"settings/debug-logging" enabled})))
