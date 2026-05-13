(ns event-handler.actions-test
  (:require ["vitest" :refer [describe test expect]]
            [event-handler :as event-handler]))

;; ============================================================
;; handle-action tests (generic action handler)
;; ============================================================

(defn- test-handles-db-ax-assoc-with-single-key-value-pair []
  (let [state {:foo 1}
        result (event-handler/handle-action state {} [:db/ax.assoc :bar 2])]
    (-> (expect (get (:uf/db result) :foo))
        (.toBe 1))
    (-> (expect (get (:uf/db result) :bar))
        (.toBe 2))))

(defn- test-handles-db-ax-assoc-with-multiple-key-value-pairs []
  (let [state {:existing "value"}
        result (event-handler/handle-action state {} [:db/ax.assoc :a 1 :b 2 :c 3])]
    (-> (expect (get (:uf/db result) :a))
        (.toBe 1))
    (-> (expect (get (:uf/db result) :b))
        (.toBe 2))
    (-> (expect (get (:uf/db result) :c))
        (.toBe 3))
    (-> (expect (get (:uf/db result) :existing))
        (.toBe "value"))))

(defn- test-returns-uf-unhandled-ax-for-unknown-action []
  (let [result (event-handler/handle-action {} {} [:unknown/action])]
    (-> (expect result)
        (.toBe :uf/unhandled-ax))))

;; ============================================================
;; handle-actions tests (action batch processing)
;; ============================================================

(defn- test-processes-empty-actions-list []
  (let [state {:initial "state"}
        result (event-handler/handle-actions
                state {} (constantly {:uf/db state}) [])]
    (-> (expect (get (:uf/db result) :initial))
        (.toBe "state"))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 0))))

(defn- inc-handler [s _uf [action & _args]]
  (case action
    :inc {:uf/db (update s :count inc)}
    :uf/unhandled-ax))

(defn- run-batch [state handler actions]
  (event-handler/handle-actions state {} handler actions))

(defn- test-processes-single-action []
  (let [result (run-batch {:count 0} inc-handler [[:inc]])]
    (-> (expect (get (:uf/db result) :count))
        (.toBe 1))))

(defn- test-chains-multiple-actions-each-sees-updated-state []
  (let [result (run-batch {:count 0} inc-handler [[:inc] [:inc] [:inc]])]
    (-> (expect (get (:uf/db result) :count))
        (.toBe 3))))

(defn- test-accumulates-effects-from-multiple-actions []
  (let [handler (fn [s _uf [action & args]]
                  (case action
                    :emit {:uf/db s :uf/fxs [[:effect (first args)]]}
                    :uf/unhandled-ax))
        result (run-batch {} handler [[:emit "a"] [:emit "b"]])]
    (-> (expect (count (:uf/fxs result)))
        (.toBe 2))))

(defn- test-filters-nil-actions []
  (let [result (run-batch {:count 0} inc-handler [nil [:inc] nil [:inc] nil])]
    (-> (expect (get (:uf/db result) :count))
        (.toBe 2))))

(defn- test-falls-back-to-generic-handler-for-unhandled-actions []
  (let [state {:foo 1}
        ;; Custom handler doesn't know :db/ax.assoc
        custom-handler (fn [_s _uf [action & _args]]
                         (case action
                           :custom {:uf/db {:custom true}}
                           :uf/unhandled-ax))
        result (event-handler/handle-actions
                state {} custom-handler [[:db/ax.assoc :bar 2]])]
    ;; Should fall back to generic handler
    (-> (expect (get (:uf/db result) :bar))
        (.toBe 2))))

(defn- test-accumulates-uf-dxs-in-batch []
  (let [handler (fn [s _uf [action & args]]
                  (case action
                    :set-dxs {:uf/db s :uf/dxs (first args)}
                    :uf/unhandled-ax))
        result (run-batch {} handler [[:set-dxs [[:first]]] [:set-dxs [[:second]]]])]
    (-> (expect (:uf/dxs result))
        (.toEqual [[:first] [:second]]))))

;; ============================================================
;; uf-data context tests
;; ============================================================

(defn- test-passes-uf-data-to-handler []
  (let [state {}
        captured-uf-data (atom nil)
        handler (fn [s uf-data [_action & _args]]
                  (reset! captured-uf-data uf-data)
                  {:uf/db s})
        uf-data {:system/now 1234567890}]
    (event-handler/handle-actions state uf-data handler [[:any-action]])
    (-> (expect (get @captured-uf-data :system/now))
        (.toBe 1234567890))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "handle-action"
          (fn []
            (test "handles :db/ax.assoc with single key-value pair" test-handles-db-ax-assoc-with-single-key-value-pair)
            (test "handles :db/ax.assoc with multiple key-value pairs" test-handles-db-ax-assoc-with-multiple-key-value-pairs)
            (test "returns :uf/unhandled-ax for unknown action" test-returns-uf-unhandled-ax-for-unknown-action)))

(describe "handle-actions"
          (fn []
            (test "processes empty actions list" test-processes-empty-actions-list)
            (test "processes single action" test-processes-single-action)
            (test "chains multiple actions - each sees updated state" test-chains-multiple-actions-each-sees-updated-state)
            (test "accumulates effects from multiple actions" test-accumulates-effects-from-multiple-actions)
            (test "filters nil actions" test-filters-nil-actions)
            (test "falls back to generic handler for unhandled actions" test-falls-back-to-generic-handler-for-unhandled-actions)
            (test "accumulates :uf/dxs in batch" test-accumulates-uf-dxs-in-batch)))

(describe "uf-data context"
          (fn []
            (test "passes uf-data to handler" test-passes-uf-data-to-handler)))
