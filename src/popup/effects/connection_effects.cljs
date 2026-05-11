(ns popup.effects.connection-effects
  (:require [popup.utils :as popup-utils]))

(def connect-cancel-signal #js {:cancelled false})

(defn- send-connect-tab-message [tab port]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.runtime.sendMessage
      #js {:type "connect-tab"
           :tabId (.-id tab)
           :wsPort port}
      (fn [response]
        (if js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))
          (resolve response)))))))

(defn- retry-connect! [dispatch tab port tab-title tab-favicon]
  (js/Promise.
   (fn [resolve]
     (letfn [(attempt []
               (if (.-cancelled connect-cancel-signal)
                 (resolve)
                 (-> (send-connect-tab-message tab port)
                     (.then (fn [resp]
                              (if (and resp (.-success resp))
                                (do (dispatch [[:connection/ax.connect-finished]
                                               [:banner/ax.show-system-banner "success" (str "Connected to \"" tab-title "\"") {:favicon tab-favicon} "connection"]])
                                    (resolve))
                                (js/setTimeout attempt 1500))))
                     (.catch (fn [_err]
                               (js/setTimeout attempt 1500))))))]
       (attempt)))))

(defn ^:async connect! [dispatch port]
  (let [tab (js-await (popup-utils/get-active-tab))
        tab-title (or (.-title tab) "tab")
        tab-favicon (.-favIconUrl tab)]
    (set! (.-cancelled connect-cancel-signal) false)
    (dispatch [[:banner/ax.show-system-banner "info" (str "Waiting for server on :" port "...") {:favicon tab-favicon} "connection"]])
    (js-await (retry-connect! dispatch tab port tab-title tab-favicon))))

(defn ^:async check-status! [_dispatch _ws-port]
  (let [tab (js-await (popup-utils/get-active-tab))]
    (try
      (js/Promise.
       (fn [resolve reject]
         (js/chrome.runtime.sendMessage
          #js {:type "check-status"
               :tabId (.-id tab)}
          (fn [response]
            (if js/chrome.runtime.lastError
              (reject (js/Error. (.-message js/chrome.runtime.lastError)))
              (resolve response))))))
      (catch :default _err
        nil))))

(defn disconnect-tab! [_dispatch tab-id]
  (let [numeric-tab-id (js/parseInt tab-id 10)]
    (js/chrome.runtime.sendMessage
     #js {:type "disconnect-tab" :tabId numeric-tab-id})))

(defn ^:async load-current-url! [dispatch]
  (let [tab (js-await (popup-utils/get-active-tab))]
    (dispatch [[:db/ax.assoc
                :scripts/current-url (.-url tab)
                :scripts/current-tab-id (.-id tab)]
               [:runtime-status/ax.load-runtime-status]])))

(defn load-connections! [dispatch]
  (js/chrome.runtime.sendMessage
   #js {:type "get-connections"}
   (fn [response]
     (when (and response (.-success response))
       (let [connections (.-connections response)]
         (dispatch [[:db/ax.assoc :repl/connections connections]]))))))

(defn load-runtime-status! [dispatch tab-id]
  (when tab-id
    (js/chrome.runtime.sendMessage
     #js {:type "get-runtime-status" :tabId tab-id}
     (fn [response]
       (when (and response (.-success response))
         (dispatch [[:runtime-status/ax.handle-runtime-status
                     {:tab-id tab-id
                      :errors (.-errors response)}]]))))))
