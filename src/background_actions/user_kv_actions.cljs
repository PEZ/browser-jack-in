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

(defn- read-error-response [send-response read-result]
  {:uf/fxs [[:msg/fx.send-response send-response
             {:success false :error (or (:error read-result) "Read failed")}]]})

(defn- handle-get [_state [send-response key]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-read]]
   :uf/dxs [[:user-kv/ax.get-ready send-response key :uf/prev-result]]})

(defn- handle-get-ready [_state [send-response key read-result]]
  (if (:success read-result)
    {:uf/fxs [[:msg/fx.send-response send-response
               {:success true :value (blob-get (:blob read-result) key)}]]}
    (read-error-response send-response read-result)))

(defn- handle-set [_state [send-response key value]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-read]]
   :uf/dxs [[:user-kv/ax.set-ready send-response key value :uf/prev-result]]})

(defn- handle-set-ready [_state [send-response key value read-result]]
  (if (:success read-result)
    (let [new-blob (blob-set (:blob read-result) key value)]
      {:uf/fxs [[:uf/await :storage/fx.user-kv-write new-blob]]
       :uf/dxs [[:user-kv/ax.write-respond send-response {:success true :value value} :uf/prev-result]]})
    (read-error-response send-response read-result)))

(defn- handle-remove [_state [send-response key]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-read]]
   :uf/dxs [[:user-kv/ax.remove-ready send-response key :uf/prev-result]]})

(defn- handle-remove-ready [_state [send-response key read-result]]
  (if (:success read-result)
    (let [new-blob (blob-remove (:blob read-result) key)]
      {:uf/fxs [[:uf/await :storage/fx.user-kv-write new-blob]]
       :uf/dxs [[:user-kv/ax.write-respond send-response {:success true} :uf/prev-result]]})
    (read-error-response send-response read-result)))

(defn- handle-keys [_state [send-response]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-read]]
   :uf/dxs [[:user-kv/ax.keys-ready send-response :uf/prev-result]]})

(defn- handle-keys-ready [_state [send-response read-result]]
  (if (:success read-result)
    {:uf/fxs [[:msg/fx.send-response send-response
               {:success true :keys (blob-keys (:blob read-result))}]]}
    (read-error-response send-response read-result)))

(defn- handle-clear [_state [send-response]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-read]]
   :uf/dxs [[:user-kv/ax.clear-ready send-response :uf/prev-result]]})

(defn- handle-clear-ready [_state [send-response read-result]]
  (if (:success read-result)
    (let [new-blob (blob-clear (:blob read-result))]
      {:uf/fxs [[:uf/await :storage/fx.user-kv-write new-blob]]
       :uf/dxs [[:user-kv/ax.write-respond send-response {:success true} :uf/prev-result]]})
    (read-error-response send-response read-result)))

(defn- handle-write-respond [_state [send-response ok-payload write-result]]
  (if (:success write-result)
    {:uf/fxs [[:msg/fx.send-response send-response ok-payload]]}
    {:uf/fxs [[:msg/fx.send-response send-response
               {:success false :error (or (:error write-result) "Write failed")}]]}))

(def ^:private action-handlers
  {:user-kv/ax.get handle-get
   :user-kv/ax.get-ready handle-get-ready
   :user-kv/ax.set handle-set
   :user-kv/ax.set-ready handle-set-ready
   :user-kv/ax.remove handle-remove
   :user-kv/ax.remove-ready handle-remove-ready
   :user-kv/ax.keys handle-keys
   :user-kv/ax.keys-ready handle-keys-ready
   :user-kv/ax.clear handle-clear
   :user-kv/ax.clear-ready handle-clear-ready
   :user-kv/ax.write-respond handle-write-respond})

(defn handle-action [state _uf-data [action & args]]
  (if-let [handler (get action-handlers action)]
    (handler state args)
    :uf/unhandled-ax))
