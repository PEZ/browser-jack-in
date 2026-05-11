(ns popup.effects.ui-effects
  (:require [popup.utils :as popup-utils]
            [script-utils :as script-utils]))

(defn ^:async copy-command! [dispatch cmd]
  (js-await (js/navigator.clipboard.writeText cmd))
  (dispatch [[:banner/ax.show-system-banner "success" "browser-nrepl command copied to your clipboard." {} nil]]))

(defn reveal-script! [_dispatch script-name]
  (let [el (js/document.querySelector (str ".script-item[data-script-name='" script-name "']"))]
    (when el
      (.scrollIntoView el #js {:block "center"}))
    nil))

(defn reveal-tab! [_dispatch tab-id]
  (let [numeric-tab-id (js/parseInt tab-id 10)]
    (js/chrome.tabs.update numeric-tab-id #js {:active true}
                           (fn [_tab]
                             (when-not js/chrome.runtime.lastError
                               (js/chrome.tabs.get numeric-tab-id
                                                   (fn [tab]
                                                     (when-not js/chrome.runtime.lastError
                                                       (js/chrome.windows.update (.-windowId tab) #js {:focused true})))))))))

(defn dump-dev-log! [_dispatch]
  (js/chrome.storage.local.get
   #js ["test-events"]
   (fn [result]
     (let [events (or (aget result "test-events") #js [])]
       (js/console.log "__EPUPP_DEV_LOG__" (js/JSON.stringify events))))))

(defn log-system-banner! [_dispatch message bulk-op? bulk-final? bulk-names]
  (if (and bulk-op? bulk-final? (seq bulk-names))
    (js/console.info "[Epupp:FS]" message (clj->js {:files bulk-names}))
    (js/console.info "[Epupp:FS]" message)))

(defn ^:async check-page-scriptability! [dispatch]
  (let [tab (js-await (popup-utils/get-active-tab))
        url (.-url tab)
        browser-type (script-utils/detect-browser-type)
        scriptability (script-utils/check-page-scriptability url browser-type)]
    (dispatch [[:db/ax.assoc
                :browser/type browser-type
                :ui/page-banner (when-not (:scriptable? scriptability)
                                  {:type "info" :message (:message scriptability)})]])))

(defn check-host-permission! [dispatch]
  (if (= "safari" (script-utils/detect-browser-type))
    (dispatch [[:db/ax.assoc :permissions/host-granted? true]])
    (try
      (js/chrome.permissions.contains
       #js {:origins #js ["<all_urls>"]}
       (fn [result]
         (dispatch [[:db/ax.assoc :permissions/host-granted? (boolean result)]])))
      (catch :default _
        (dispatch [[:db/ax.assoc :permissions/host-granted? true]])))))

(defn request-host-permission! [dispatch tab-id]
  (try
    (js/chrome.permissions.request
     #js {:origins #js ["<all_urls>"]}
     (fn [granted]
       (dispatch [[:db/ax.assoc :permissions/host-granted? (boolean granted)]])
       (when (and granted tab-id)
         (js/chrome.runtime.sendMessage
          #js {:type "permission-granted" :tabId tab-id}
          (fn [_] (when js/chrome.runtime.lastError nil))))))
    (catch :default _
      nil)))
