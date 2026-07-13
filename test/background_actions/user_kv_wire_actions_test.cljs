(ns background-actions.user-kv-wire-actions-test
  (:require ["vitest" :refer [describe test expect]]
            [background-actions.user-kv-actions :as user-kv]))

(defn- first-await-fx [result]
  (first (filter #(= :uf/await (first %)) (:uf/fxs result))))

(defn- first-send-response-fx [result]
  (first (filter #(= :msg/fx.send-response (first %)) (:uf/fxs result))))

(defn- fx-keywords [result]
  (set (map first (:uf/fxs result))))

(defn- test-get-declares-await-and-dxs []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.get identity "my/key"])]
    (-> (expect (first-await-fx result))
        (.toEqual [:uf/await :storage/fx.user-kv-read]))
    (-> (expect (:uf/dxs result))
        (.toEqual [[:user-kv/ax.get-ready identity "my/key" :uf/prev-result]]))))

(defn- test-get-ready-success-sends-value []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.get-ready identity "my/key"
                                             {:success true :blob {"my/key" "42"}}])]
    (-> (expect (first-send-response-fx result))
        (.toEqual [:msg/fx.send-response identity {:success true :value "42"}]))))

(defn- test-get-ready-read-failure-sends-error []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.get-ready identity "my/key"
                                             {:success false :error "boom" :blob {}}])]
    (-> (expect (first-send-response-fx result))
        (.toEqual [:msg/fx.send-response identity {:success false :error "boom"}]))))

(defn- test-set-ready-awaits-user-kv-write []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.set-ready identity "k" "v"
                                             {:success true :blob {}}])]
    (-> (expect (first-await-fx result))
        (.toEqual [:uf/await :storage/fx.user-kv-write {"k" "v"}]))
    (-> (expect (:uf/dxs result))
        (.toEqual [[:user-kv/ax.write-respond identity {:success true :value "v"} :uf/prev-result]]))
    (-> (expect (contains? (fx-keywords result) :storage/fx.get-local-storage))
        (.toBe false))
    (-> (expect (contains? (fx-keywords result) :storage/fx.set-local-storage))
        (.toBe false))))

(defn- test-remove-ready-awaits-write-with-key-removed []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.remove-ready identity "a"
                                              {:success true :blob {"a" "1" "b" "2"}}])]
    (-> (expect (first-await-fx result))
        (.toEqual [:uf/await :storage/fx.user-kv-write {"b" "2"}]))))

(defn- test-keys-ready-returns-sorted-keys []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.keys-ready identity
                                             {:success true :blob {"z" "1" "a" "2"}}])]
    (-> (expect (first-send-response-fx result))
        (.toEqual [:msg/fx.send-response identity {:success true :keys ["a" "z"]}]))))

(defn- test-clear-ready-awaits-empty-blob-write []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.clear-ready identity
                                             {:success true :blob {"a" "1"}}])]
    (-> (expect (first-await-fx result))
        (.toEqual [:uf/await :storage/fx.user-kv-write {}]))
    (-> (expect (:uf/dxs result))
        (.toEqual [[:user-kv/ax.write-respond identity {:success true} :uf/prev-result]]))))

(defn- test-write-respond-success-sends-payload []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.write-respond identity {:success true} {:success true}])]
    (-> (expect (first-send-response-fx result))
        (.toEqual [:msg/fx.send-response identity {:success true}]))))

(defn- test-write-respond-failure-sends-error []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.write-respond identity {:success true}
                                             {:success false :error "disk full"}])]
    (-> (expect (first-send-response-fx result))
        (.toEqual [:msg/fx.send-response identity {:success false :error "disk full"}]))))

(describe "user-kv wire actions"
          (fn []
            (test "get declares await user-kv-read and dxs get-ready" test-get-declares-await-and-dxs)
            (test "get-ready success sends value" test-get-ready-success-sends-value)
            (test "get-ready read failure sends error" test-get-ready-read-failure-sends-error)
            (test "set-ready awaits user-kv-write with updated blob" test-set-ready-awaits-user-kv-write)
            (test "remove-ready awaits write with key removed" test-remove-ready-awaits-write-with-key-removed)
            (test "keys-ready returns sorted keys" test-keys-ready-returns-sorted-keys)
            (test "clear-ready awaits empty blob write" test-clear-ready-awaits-empty-blob-write)
            (test "write-respond success sends ok payload" test-write-respond-success-sends-payload)
            (test "write-respond failure sends error" test-write-respond-failure-sends-error)))
