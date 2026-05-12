(ns event-handler.fx-pipeline-test
  (:require ["vitest" :refer [describe test expect]]
            [event-handler :as event-handler]))

;; ============================================================
;; await-fx? tests
;; ============================================================

(defn- test-await-fx-returns-true-for-effects-with-uf-await-sentinel []
  (-> (expect (event-handler/await-fx? [:uf/await :fx.something 1 2]))
      (.toBe true)))

(defn- test-await-fx-returns-false-for-regular-effects []
  (-> (expect (event-handler/await-fx? [:fx.something 1 2]))
      (.toBe false)))

(defn- test-await-fx-returns-false-for-non-vector-inputs []
  (-> (expect (event-handler/await-fx? nil))
      (.toBe false))
  (-> (expect (event-handler/await-fx? "string"))
      (.toBe false))
  (-> (expect (event-handler/await-fx? {:map true}))
      (.toBe false)))

;; ============================================================
;; unwrap-fx tests
;; ============================================================

(defn- test-unwrap-fx-removes-uf-await-sentinel-from-await-effects []
  (let [result (event-handler/unwrap-fx [:uf/await :fx.something 1 2])]
    (-> (expect (first result))
        (.toBe :fx.something))
    (-> (expect (count result))
        (.toBe 3))))

(defn- test-unwrap-fx-returns-regular-effects-unchanged []
  (let [fx [:fx.something 1 2]
        result (event-handler/unwrap-fx fx)]
    (-> (expect (first result))
        (.toBe :fx.something))
    (-> (expect (count result))
        (.toBe 3))))

;; ============================================================
;; replace-prev-result tests
;; ============================================================

(defn- test-replace-prev-result-substitutes-uf-prev-result-with-provided-value []
  (let [fx [:fx.use-result :uf/prev-result :other-arg]
        result (event-handler/replace-prev-result fx {:data "from-previous"})]
    (-> (expect (first result))
        (.toBe :fx.use-result))
    (-> (expect (get (second result) :data))
        (.toBe "from-previous"))
    (-> (expect (nth result 2))
        (.toBe :other-arg))))

(defn- test-replace-prev-result-leaves-effects-without-uf-prev-result-unchanged []
  (let [fx [:fx.normal :arg1 :arg2]
        result (event-handler/replace-prev-result fx {:ignored "data"})]
    (-> (expect (first result))
        (.toBe :fx.normal))
    (-> (expect (second result))
        (.toBe :arg1))
    (-> (expect (nth result 2))
        (.toBe :arg2))))

(defn- test-replace-prev-result-handles-multiple-uf-prev-result-occurrences []
  (let [fx [:fx.multi :uf/prev-result :other :uf/prev-result]
        result (event-handler/replace-prev-result fx "replaced")]
    (-> (expect (second result))
        (.toBe "replaced"))
    (-> (expect (nth result 3))
        (.toBe "replaced"))))

;; ============================================================
;; replace-prev-result-in-actions tests (dxs substitution)
;; ============================================================

(defn- test-replace-prev-result-in-actions-substitutes-in-all-actions []
  (let [actions [[:ax.first :uf/prev-result]
                 [:ax.second :uf/prev-result :other-arg]]
        result (event-handler/replace-prev-result-in-actions actions {:data "context"})]
    (-> (expect (second (first result)))
        (.toEqual {:data "context"}))
    (-> (expect (second (second result)))
        (.toEqual {:data "context"}))))

(defn- test-replace-prev-result-in-actions-leaves-actions-without-placeholder-unchanged []
  (let [actions [[:ax.no-placeholder :arg1 :arg2]
                 [:ax.another "string"]]
        result (event-handler/replace-prev-result-in-actions actions {:ignored "data"})]
    (-> (expect (first result))
        (.toEqual [:ax.no-placeholder :arg1 :arg2]))
    (-> (expect (second result))
        (.toEqual [:ax.another "string"]))))

(defn- test-replace-prev-result-in-actions-handles-empty-actions []
  (let [result (event-handler/replace-prev-result-in-actions [] "any-value")]
    (-> (expect result)
        (.toEqual []))))

(defn- test-replace-prev-result-in-actions-handles-nil-prev-result []
  (let [actions [[:ax.uses-result :uf/prev-result]]
        result (event-handler/replace-prev-result-in-actions actions nil)]
    (-> (expect (second (first result)))
        (.toBeNull))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "await-fx?"
          (fn []
            (test "returns true for effects with :uf/await sentinel" test-await-fx-returns-true-for-effects-with-uf-await-sentinel)
            (test "returns false for regular effects" test-await-fx-returns-false-for-regular-effects)
            (test "returns false for non-vector inputs" test-await-fx-returns-false-for-non-vector-inputs)))

(describe "unwrap-fx"
          (fn []
            (test "removes :uf/await sentinel from await effects" test-unwrap-fx-removes-uf-await-sentinel-from-await-effects)
            (test "returns regular effects unchanged" test-unwrap-fx-returns-regular-effects-unchanged)))

(describe "replace-prev-result"
          (fn []
            (test "substitutes :uf/prev-result with provided value" test-replace-prev-result-substitutes-uf-prev-result-with-provided-value)
            (test "leaves effects without :uf/prev-result unchanged" test-replace-prev-result-leaves-effects-without-uf-prev-result-unchanged)
            (test "handles multiple :uf/prev-result occurrences" test-replace-prev-result-handles-multiple-uf-prev-result-occurrences)))

(describe "replace-prev-result-in-actions"
          (fn []
            (test "substitutes :uf/prev-result in all actions" test-replace-prev-result-in-actions-substitutes-in-all-actions)
            (test "leaves actions without placeholder unchanged" test-replace-prev-result-in-actions-leaves-actions-without-placeholder-unchanged)
            (test "handles empty actions" test-replace-prev-result-in-actions-handles-empty-actions)
            (test "handles nil prev-result" test-replace-prev-result-in-actions-handles-nil-prev-result)))
