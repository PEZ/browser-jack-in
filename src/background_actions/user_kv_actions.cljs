(ns background-actions.user-kv-actions)

(def user-kv-storage-key
  "chrome.storage.local blob key for the user key-value map."
  "epuppUserKv")

(defn coerce-bucket-key
  "Coerce a keyword or string key to a bucket string path.
   Keywords preserve namespace (:my/settings -> \"my/settings\").
   Strings pass through unchanged."
  [k]
  (str k))

(defn blob-get
  "Look up an EDN-string value in the user-KV blob map. Missing -> nil."
  [blob k]
  (get (or blob {}) (coerce-bucket-key k)))

(defn blob-set
  "Assoc an opaque EDN-string value under the coerced key."
  [blob k edn-str]
  (assoc (or blob {}) (coerce-bucket-key k) edn-str))

(defn blob-remove
  "Dissoc the coerced key. Missing key leaves the map unchanged."
  [blob k]
  (dissoc (or blob {}) (coerce-bucket-key k)))

(defn blob-keys
  "Sorted vector of string keys in the blob."
  [blob]
  (-> (or blob {}) keys sort vec))

(defn blob-clear
  "Empty user-KV blob map."
  [_blob]
  {})
