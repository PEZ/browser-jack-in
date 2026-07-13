(ns background-effects.storage-effects
  (:require [storage :as storage]
            [background-actions.user-kv-actions :as user-kv]))

(defn- ^:async user-kv-read!
  []
  (try
    (let [result (js-await (js/chrome.storage.local.get #js [user-kv/user-kv-storage-key]))
          blob (or (aget result user-kv/user-kv-storage-key) {})]
      {:success true :blob blob})
    (catch :default err
      {:success false :error (.-message err) :blob {}})))

(defn- ^:async user-kv-write!
  [blob]
  (try
    (js-await (js/Promise.
               (fn [resolve]
                 (js/chrome.storage.local.set
                  (js-obj user-kv/user-kv-storage-key blob)
                  resolve))))
    {:success true}
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

    :storage/fx.user-kv-read
    (user-kv-read!)

    :storage/fx.user-kv-write
    (let [[blob] args]
      (user-kv-write! blob))

    :storage/fx.persist-ext-dep-cache!
    (let [[cache] args]
      (storage/persist-ext-dep-cache! cache))))
