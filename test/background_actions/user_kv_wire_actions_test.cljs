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
        (.toEqual [:uf/await :storage/fx.user-kv-op {:op :get :key "my/key"}]))
    (-> (expect (:uf/dxs result))
        (.toEqual [[:user-kv/ax.op-respond identity :uf/prev-result]]))))

(defn- test-set-declares-op-await []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.set identity "k" "v"])]
    (-> (expect (first-await-fx result))
        (.toEqual [:uf/await :storage/fx.user-kv-op {:op :set :key "k" :value "v"}]))
    (-> (expect (contains? (fx-keywords result) :storage/fx.get-local-storage))
        (.toBe false))
    (-> (expect (contains? (fx-keywords result) :storage/fx.set-local-storage))
        (.toBe false))))

(defn- test-remove-declares-op-await []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.remove identity "a"])]
    (-> (expect (first-await-fx result))
        (.toEqual [:uf/await :storage/fx.user-kv-op {:op :remove :key "a"}]))
    (-> (expect (:uf/dxs result))
        (.toEqual [[:user-kv/ax.op-respond identity :uf/prev-result]]))))

(defn- test-keys-declares-op-await []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.keys identity])]
    (-> (expect (first-await-fx result))
        (.toEqual [:uf/await :storage/fx.user-kv-op {:op :keys}]))
    (-> (expect (:uf/dxs result))
        (.toEqual [[:user-kv/ax.op-respond identity :uf/prev-result]]))))

(defn- test-clear-declares-op-await []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.clear identity])]
    (-> (expect (first-await-fx result))
        (.toEqual [:uf/await :storage/fx.user-kv-op {:op :clear}]))
    (-> (expect (:uf/dxs result))
        (.toEqual [[:user-kv/ax.op-respond identity :uf/prev-result]]))))

(defn- test-op-respond-sends-prev-result []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.op-respond identity
                                             {:success true :value "42"}])]
    (-> (expect (first-send-response-fx result))
        (.toEqual [:msg/fx.send-response identity {:success true :value "42"}]))))

(defn- test-op-respond-sends-error []
  (let [result (user-kv/handle-action {} {} [:user-kv/ax.op-respond identity
                                             {:success false :error "boom"}])]
    (-> (expect (first-send-response-fx result))
        (.toEqual [:msg/fx.send-response identity {:success false :error "boom"}]))))

(describe "user-kv wire actions"
          (fn []
            (test "get declares await user-kv-op and dxs op-respond" test-get-declares-await-and-dxs)
            (test "set declares await user-kv-op" test-set-declares-op-await)
            (test "remove declares await user-kv-op" test-remove-declares-op-await)
            (test "keys declares await user-kv-op" test-keys-declares-op-await)
            (test "clear declares await user-kv-op" test-clear-declares-op-await)
            (test "op-respond sends prev-result" test-op-respond-sends-prev-result)
            (test "op-respond sends error" test-op-respond-sends-error)))
