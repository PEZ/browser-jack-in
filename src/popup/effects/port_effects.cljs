(ns popup.effects.port-effects
  (:require [popup.utils :as popup-utils]))

(defn- get-hostname [tab]
  (try
    (.-hostname (js/URL. (.-url tab)))
    (catch :default _ "default")))

(defn storage-key [tab]
  (str "ports_" (get-hostname tab)))

(defn ^:async save-ports! [_dispatch ports]
  (let [tab (js-await (popup-utils/get-active-tab))
        key (storage-key tab)]
    (js/chrome.storage.local.set
     (clj->js {key {:nreplPort (:ports/nrepl ports)
                     :wsPort (:ports/ws ports)}}))))

(defn ^:async clear-domain-ports! [_dispatch]
  (let [tab (js-await (popup-utils/get-active-tab))
        key (storage-key tab)]
    (js/chrome.storage.local.remove #js [key])))

(defn ^:async load-saved-ports! [dispatch nrepl-port ws-port]
  (let [tab (js-await (popup-utils/get-active-tab))
        key (storage-key tab)]
    (js/chrome.storage.local.get
     #js [key]
     (fn [result]
       (let [saved (aget result key)]
         (if saved
           (let [actions (cond-> []
                           (.-nreplPort saved)
                           (conj [:db/ax.assoc :ports/nrepl (str (.-nreplPort saved))])
                           (.-wsPort saved)
                           (conj [:db/ax.assoc :ports/ws (str (.-wsPort saved))]))]
             (when (seq actions)
               (dispatch actions)))
           (let [actions [[:db/ax.assoc
                           :ports/nrepl nrepl-port
                           :ports/ws ws-port]]]
             (dispatch actions))))))))

(defn ^:async init-ports! [dispatch]
  (let [tab (js-await (popup-utils/get-active-tab))
        key (storage-key tab)]
    (js/chrome.storage.local.get
     #js ["defaultNreplPort" "defaultWsPort" key]
     (fn [result]
       (let [stored-nrepl (aget result "defaultNreplPort")
             stored-ws (aget result "defaultWsPort")
             stored-defaults (when (or (some? stored-nrepl) (some? stored-ws))
                               (cond-> {}
                                 (some? stored-nrepl) (assoc :nrepl (str stored-nrepl))
                                 (some? stored-ws) (assoc :ws (str stored-ws))))
             saved (aget result key)
             domain-ports (when saved
                            (let [nrepl (.-nreplPort saved)
                                  ws (.-wsPort saved)]
                              (when (or (some? nrepl) (some? ws))
                                (cond-> {}
                                  (some? nrepl) (assoc :nrepl (str nrepl))
                                  (some? ws) (assoc :ws (str ws))))))]
         (dispatch [[:popup-connection/ax.apply-init-ports {:stored-defaults stored-defaults
                                                 :domain-ports domain-ports}]]))))))

(defn load-default-ports-setting! [dispatch]
  (js/chrome.storage.local.get
   #js ["defaultNreplPort" "defaultWsPort"]
   (fn [result]
     (let [nrepl-port (aget result "defaultNreplPort")
           ws-port (aget result "defaultWsPort")
           actions (cond-> []
                     (some? nrepl-port)
                     (conj [:db/ax.assoc :settings/default-nrepl-port (str nrepl-port)])
                     (some? ws-port)
                     (conj [:db/ax.assoc :settings/default-ws-port (str ws-port)]))]
       (when (seq actions)
         (dispatch actions))))))

(defn save-default-ports-setting! [_dispatch ports]
  (js/chrome.storage.local.set
   #js {:defaultNreplPort (:settings/default-nrepl-port ports)
        :defaultWsPort (:settings/default-ws-port ports)}))

(defn run-port-migration! [dispatch]
  (js/chrome.storage.local.get
   nil
   (fn [result]
     (let [marker (aget result "epupp_migration_ports_normalized_v1")]
       (when-not marker
         (let [all-keys (js/Object.keys result)
               port-keys (filterv #(.startsWith % "ports_") all-keys)
               defaults {:nrepl (str (or (aget result "defaultNreplPort") "3339"))
                         :ws (str (or (aget result "defaultWsPort") "3340"))}
               port-entries (reduce (fn [acc k]
                                      (let [v (aget result k)
                                            nrepl (.-nreplPort v)
                                            ws (.-wsPort v)]
                                        (assoc acc k (cond-> {}
                                                       (some? nrepl) (assoc :nrepl (str nrepl))
                                                       (some? ws) (assoc :ws (str ws))))))
                                    {}
                                    port-keys)]
           (dispatch [[:popup-connection/ax.apply-port-migration {:defaults defaults
                                                       :port-entries port-entries}]])))))))

(defn remove-storage-keys! [_dispatch keys-to-remove]
  (when (seq keys-to-remove)
    (js/chrome.storage.local.remove (clj->js keys-to-remove))))

(defn set-storage-key! [_dispatch k v]
  (js/chrome.storage.local.set (clj->js {k v})))
