(ns popup.actions-ui-shadow-list-test
  "Tests for popup shadow list sync and deferred cleanup actions"
  (:require ["vitest" :refer [describe test expect]]
            [popup.actions :as popup-actions]))

;; ============================================================
;; Shared Setup
;; ============================================================

(def uf-data {:system/now 1234567890
              :config/deps-string "{:deps {}}"})

;; ============================================================
;; Shadow List Sync Tests
;; ============================================================

(defn- scripts-shadow-after-action [state action]
  (:ui/scripts-shadow (:uf/db (popup-actions/handle-action state uf-data action))))

(defn- test-sync-scripts-shadow-updates-content-without-entering-flag []
  (let [state {:scripts/list [{:script/id "test-1" :script/code "updated code"}]
               :ui/scripts-shadow [{:item {:script/id "test-1" :script/code "old code"}
                                    :ui/entering? false
                                    :ui/leaving? false}]}
        shadow-item (first (scripts-shadow-after-action
                            state
                            [:shadow-list/ax.sync-scripts-shadow
                             {:added-items [] :removed-ids #{}}]))]
    (-> (expect (get-in shadow-item [:item :script/code]))
        (.toBe "updated code"))
    (-> (expect (:ui/entering? shadow-item))
        (.toBe false))
    (-> (expect (:ui/leaving? shadow-item))
        (.toBe false))))

(defn- test-sync-scripts-shadow-adds-new-items-with-entering-flag []
  (let [new-script {:script/id "new-1" :script/code "new code"}
        state {:scripts/list [new-script]
               :ui/scripts-shadow []}
        shadow-item (first (scripts-shadow-after-action
                            state
                            [:shadow-list/ax.sync-scripts-shadow
                             {:added-items [new-script] :removed-ids #{}}]))]
    (-> (expect (get-in shadow-item [:item :script/id]))
        (.toBe "new-1"))
    (-> (expect (:ui/entering? shadow-item))
        (.toBe true))
    (-> (expect (:ui/leaving? shadow-item))
        (.toBe false))))

(defn- test-sync-scripts-shadow-marks-removed-items-as-leaving []
  (let [state {:scripts/list []
               :ui/scripts-shadow [{:item {:script/id "to-remove"}
                                    :ui/entering? false
                                    :ui/leaving? false}]}
        shadow-item (first (scripts-shadow-after-action
                            state
                            [:shadow-list/ax.sync-scripts-shadow
                             {:added-items [] :removed-ids #{"to-remove"}}]))]
    (-> (expect (:ui/leaving? shadow-item))
        (.toBe true))))

;; ============================================================
;; Shadow List Deferred Cleanup Tests
;; ============================================================

(defn- find-deferred-dispatch-fx [result action-name]
  (let [defer-fxs (filter #(= :uf/fx.defer-dispatch (first %)) (:uf/fxs result))]
    (some #(when (= action-name (first (first (second %)))) %) defer-fxs)))

(defn- test-sync-scripts-shadow-schedules-clear-entering []
  (let [new-script {:script/id "new-1" :script/code "new code"}
        state {:scripts/list [new-script]
               :ui/scripts-shadow []}
        result (popup-actions/handle-action
                state uf-data
                [:shadow-list/ax.sync-scripts-shadow
                 {:added-items [new-script] :removed-ids #{}}])
        clear-entering-fx (find-deferred-dispatch-fx result :shadow-list/ax.clear-entering-scripts)]
    (-> (expect clear-entering-fx)
        (.toBeTruthy))
    (let [[_fx-name _actions delay] clear-entering-fx]
      (-> (expect delay)
          (.toBe 50)))))

(defn- test-sync-scripts-shadow-schedules-remove-leaving []
  (let [state {:scripts/list []
               :ui/scripts-shadow [{:item {:script/id "to-remove"}
                                    :ui/entering? false
                                    :ui/leaving? false}]}
        result (popup-actions/handle-action
                state uf-data
                [:shadow-list/ax.sync-scripts-shadow
                 {:added-items [] :removed-ids #{"to-remove"}}])
        remove-leaving-fx (find-deferred-dispatch-fx result :shadow-list/ax.remove-leaving-scripts)]
    (-> (expect remove-leaving-fx)
        (.toBeTruthy))
    (let [[_fx-name _actions delay] remove-leaving-fx]
      (-> (expect delay)
          (.toBe 250)))))

(defn- make-shadow-item [id entering? leaving?]
  {:item {:script/id id}
   :ui/entering? entering?
   :ui/leaving? leaving?})

(defn- test-clear-entering-scripts-removes-flag []
  (let [state {:ui/scripts-shadow [(make-shadow-item "new-1" true false)
                                   (make-shadow-item "old-1" false false)]}
        shadow (scripts-shadow-after-action state [:shadow-list/ax.clear-entering-scripts #{"new-1"}])
        new-item (first shadow)
        old-item (second shadow)]
    (-> (expect (:ui/entering? new-item))
        (.toBe false))
    (-> (expect (:ui/entering? old-item))
        (.toBe false))))

(defn- test-remove-leaving-scripts-removes-items []
  (let [state {:ui/scripts-shadow [(make-shadow-item "leaving-1" false true)
                                   (make-shadow-item "staying-1" false false)]}
        shadow (scripts-shadow-after-action state [:shadow-list/ax.remove-leaving-scripts #{"leaving-1"}])]
    (-> (expect (count shadow))
        (.toBe 1))
    (-> (expect (get-in shadow [0 :item :script/id]))
        (.toBe "staying-1"))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "Shadow list sync"
          (fn []
            (test "updates content without entering flag for unchanged membership" test-sync-scripts-shadow-updates-content-without-entering-flag)
            (test "adds new items with entering flag" test-sync-scripts-shadow-adds-new-items-with-entering-flag)
            (test "marks removed items as leaving" test-sync-scripts-shadow-marks-removed-items-as-leaving)))

(describe "Shadow list deferred cleanup"
          (fn []
            (test "schedules clear-entering after delay" test-sync-scripts-shadow-schedules-clear-entering)
            (test "schedules remove-leaving after delay" test-sync-scripts-shadow-schedules-remove-leaving)
            (test "clear-entering removes entering flag" test-clear-entering-scripts-removes-flag)
            (test "remove-leaving removes items from shadow" test-remove-leaving-scripts-removes-items)))
