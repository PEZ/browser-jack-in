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

(defn- handle-get [_state [send-response key]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-op {:op :get :key key}]]
   :uf/dxs [[:user-kv/ax.op-respond send-response :uf/prev-result]]})

(defn- handle-set [_state [send-response key value]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-op {:op :set :key key :value value}]]
   :uf/dxs [[:user-kv/ax.op-respond send-response :uf/prev-result]]})

(defn- handle-remove [_state [send-response key]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-op {:op :remove :key key}]]
   :uf/dxs [[:user-kv/ax.op-respond send-response :uf/prev-result]]})

(defn- handle-keys [_state [send-response]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-op {:op :keys}]]
   :uf/dxs [[:user-kv/ax.op-respond send-response :uf/prev-result]]})

(defn- handle-clear [_state [send-response]]
  {:uf/fxs [[:uf/await :storage/fx.user-kv-op {:op :clear}]]
   :uf/dxs [[:user-kv/ax.op-respond send-response :uf/prev-result]]})

(defn- handle-op-respond [_state [send-response result]]
  {:uf/fxs [[:msg/fx.send-response send-response result]]})

(def ^:private action-handlers
  {:user-kv/ax.get handle-get
   :user-kv/ax.set handle-set
   :user-kv/ax.remove handle-remove
   :user-kv/ax.keys handle-keys
   :user-kv/ax.clear handle-clear
   :user-kv/ax.op-respond handle-op-respond})

(defn handle-action [state _uf-data [action & args]]
  (if-let [handler (get action-handlers action)]
    (handler state args)
    :uf/unhandled-ax))
