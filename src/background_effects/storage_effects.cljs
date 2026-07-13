(ns background-effects.storage-effects
  (:require [storage :as storage]
            [background-actions.user-kv-actions :as user-kv]))

(def ^:private !user-kv-chain (atom (js/Promise.resolve)))

(defn- enqueue-user-kv! [thunk]
  (let [result (atom nil)]
    (swap! !user-kv-chain
           (fn [chain]
             (let [next (-> chain
                            (.catch (fn [_] nil))
                            (.then (fn [_] (thunk))))]
               (reset! result next)
               next)))
    @result))

(defn- ^:async user-kv-read-blob! []
  (let [result (js-await (js/chrome.storage.local.get #js [user-kv/user-kv-storage-key]))]
    (or (aget result user-kv/user-kv-storage-key) {})))

(defn- ^:async user-kv-write-blob! [blob]
  (let [plain (js/JSON.parse (js/JSON.stringify blob))]
    (js-await (js/chrome.storage.local.set
               (js-obj user-kv/user-kv-storage-key plain)))))

(defn- ^:async user-kv-op-set! [blob op-map]
  (let [new-blob (user-kv/blob-set blob (:key op-map) (:value op-map))]
    (js-await (user-kv-write-blob! new-blob))
    {:success true :value (:value op-map)}))

(defn- ^:async user-kv-op-remove! [blob op-map]
  (let [new-blob (user-kv/blob-remove blob (:key op-map))]
    (js-await (user-kv-write-blob! new-blob))
    {:success true}))

(defn- ^:async user-kv-op-clear! [_blob _op-map]
  (js-await (user-kv-write-blob! {}))
  {:success true})

(defn- ^:async run-user-kv-op! [op-map]
  (try
    (let [blob (js-await (user-kv-read-blob!))]
      (case (:op op-map)
        :get {:success true :value (user-kv/blob-get blob (:key op-map))}
        :set (js-await (user-kv-op-set! blob op-map))
        :remove (js-await (user-kv-op-remove! blob op-map))
        :keys {:success true :keys (user-kv/blob-keys blob)}
        :clear (js-await (user-kv-op-clear! blob op-map))))
    (catch :default err
      {:success false :error (.-message err)})))

(defn ^:async perform-effect! [_dispatch! effect args]
  (case effect
    :storage/fx.get-local-storage
    (let [[key] args]
      (try
        (let [result (js-await (js/chrome.storage.local.get #js [key]))]
          {:success true :key key :value (aget result key)})
        (catch :default err
          {:success false :key key :error (.-message err)})))

    :storage/fx.set-local-storage
    (let [[key value] args]
      (try
        (js-await (js/Promise.
                   (fn [resolve]
                     (js/chrome.storage.local.set
                      (js-obj key value)
                      resolve))))
        (when (= "extDepCache" key)
          (swap! storage/!db assoc
                 :storage/ext-dep-cache (or value {})))
        {:success true :key key :value value}
        (catch :default err
          {:success false :error (.-message err)})))

    :storage/fx.persist!
    (storage/persist!)

    :storage/fx.user-kv-op
    (let [[op-map] args]
      (js-await (enqueue-user-kv! #(run-user-kv-op! op-map))))

    :storage/fx.persist-ext-dep-cache!
    (let [[cache] args]
      (storage/persist-ext-dep-cache! cache))))
