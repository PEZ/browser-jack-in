{:epupp/script-name "epupp/storage.cljs"
 :epupp/description "User key-value storage. Persist EDN data in the extension."
 :epupp/library? true
 :epupp/inject ["epupp://epupp/internal/helpers.cljs"]}

(ns epupp.storage
  (:require [epupp.internal.helpers :as helpers]
            [clojure.edn :as edn]))

(defn- coerce-bucket-key
  "Keyword → namespaced string path; string → identity."
  [k]
  (if (string? k)
    k
    (let [ns (namespace k)
          nm (name k)]
      (if ns (str ns "/" nm) nm))))

(defn- ensure-success!
  "Throw on transport/storage failure. response may be nil on timeout."
  [response op]
  (when-not (and response (.-success response))
    (throw (js/Error. (or (when response (.-error response))
                          (str "epupp.storage/" op " failed"))))))

(defn- response-field [response field]
  (aget response field))

(defn- ^:async fetch-value
  [k]
  (let [response (await (helpers/send-and-receive
                         "storage-get"
                         "storage-get-response"
                         {:key (coerce-bucket-key k)}
                         5000))]
    (ensure-success! response "get")
    (let [v (response-field response "value")]
      (when (some? v)
        (edn/read-string v)))))

(defn- ^:async store-value!
  [k value]
  (let [edn-str (pr-str value)
        response (await (helpers/send-and-receive
                         "storage-set"
                         "storage-set-response"
                         {:key (coerce-bucket-key k)
                          :value edn-str}
                         5000))]
    (ensure-success! response "set!")
    value))

(defn- ^:async delete-value!
  [k]
  (let [response (await (helpers/send-and-receive
                         "storage-remove"
                         "storage-remove-response"
                         {:key (coerce-bucket-key k)}
                         5000))]
    (ensure-success! response "remove!")
    nil))

(defn- ^:async list-keys
  []
  (let [response (await (helpers/send-and-receive
                         "storage-keys"
                         "storage-keys-response"
                         {}
                         5000))]
    (ensure-success! response "keys")
    (mapv keyword (js->clj (or (response-field response "keys") #js [])))))

(defn- ^:async clear-bucket!
  []
  (let [response (await (helpers/send-and-receive
                         "storage-clear"
                         "storage-clear-response"
                         {}
                         5000))]
    (ensure-success! response "clear!")
    nil))

(defn ^:async get
  "Fetch a value by key. Returns nil if missing.
   No FS sync required. Values must be EDN-readable.
   Shared chrome.storage.local quota."
  [k]
  (await (fetch-value k)))

(defn ^:async set!
  "Store a value under key. Returns the stored Clojure value.
   No FS sync required. Value must be EDN-readable.
   Encoded with pr-str at this boundary. Shared chrome.storage.local quota."
  [k value]
  (await (store-value! k value)))

(defn ^:async remove!
  "Remove a key. Idempotent. Returns nil.
   No FS sync required. Shared chrome.storage.local quota."
  [k]
  (await (delete-value! k)))

(defn ^:async keys
  "Return a vector of keyword keys in the user bucket.
   No FS sync required. Shared chrome.storage.local quota."
  []
  (await (list-keys)))

(defn ^:async clear!
  "Clear the user bucket only (epuppUserKv). Does not touch scripts/settings.
   No FS sync required. Returns nil."
  []
  (await (clear-bucket!)))
