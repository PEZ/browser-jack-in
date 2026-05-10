(ns popup-effects.sponsor-effects)

(defn check-sponsor! [_dispatch username]
  (js/chrome.tabs.create #js {:url (str "https://github.com/sponsors/" username) :active true}))

(defn load-sponsor-status! [dispatch]
  (js/chrome.storage.local.get
   #js ["sponsorStatus" "sponsorCheckedAt"]
   (fn [result]
     (let [status (boolean (.-sponsorStatus result))
           checked-at (.-sponsorCheckedAt result)]
       (dispatch [[:db/ax.assoc
                   :sponsor/status status
                   :sponsor/checked-at checked-at]])))))

(defn set-dev-sponsor-username! [_dispatch username]
  (js/chrome.storage.local.set
   (js-obj "sponsor/sponsored-username" username)))

(defn reset-sponsor-status! [_dispatch]
  (js/chrome.storage.local.remove
   #js ["sponsorStatus" "sponsorCheckedAt"]))

(defn load-dev-sponsor-username! [dispatch]
  (js/chrome.storage.local.get
   #js ["sponsor/sponsored-username"]
   (fn [result]
     (let [username (aget result "sponsor/sponsored-username")]
       (when username
         (dispatch [[:db/ax.assoc :sponsor/sponsored-username username]]))))))
