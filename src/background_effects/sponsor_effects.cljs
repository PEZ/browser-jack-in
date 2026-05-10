(ns background-effects.sponsor-effects
  (:require [storage :as storage]
            [background-utils :as bg-utils]))

(defn ^:async perform-effect! [_dispatch! effect args]
  (case effect
    :sponsor/fx.handle-status-result
    (let [[{:keys [pending? tab-url send-response]}] args]
      ((^:async fn []
         (try
           (let [storage-result (js-await (js/chrome.storage.local.get #js ["sponsor/sponsored-username"]))
                 username (or (aget storage-result "sponsor/sponsored-username") "PEZ")]
             (if (and pending?
                      (bg-utils/sponsor-url-matches? tab-url username))
               (do (swap! storage/!db assoc
                          :sponsor/status true
                          :sponsor/checked-at (js/Date.now))
                   (js-await (storage/persist!))
                   (send-response #js {:success true}))
               (send-response #js {:success false
                                   :error (if pending? "URL mismatch" "No pending sponsor check")})))
           (catch :default err
             (send-response #js {:success false :error (.-message err)}))))))))
