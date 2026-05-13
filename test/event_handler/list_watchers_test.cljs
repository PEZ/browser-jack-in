(ns event-handler.list-watchers-test
  (:require ["vitest" :refer [describe test expect]]
            [event-handler :as event-handler]))

;; ============================================================
;; get-list-watcher-actions tests (list change detection)
;; ============================================================

(defn- test-get-list-watcher-actions-returns-empty-when-no-watchers-declared []
  (let [old-state {:items [1 2 3]}
        new-state {:items [1 2]}
        result (event-handler/get-list-watcher-actions old-state new-state)]
    (-> (expect (count result))
        (.toBe 0))))

(defn- items-watcher-result [old-items new-items]
  (let [watcher {:uf/list-watchers {:items {:id-fn identity :on-change :ax.changed}}}]
    (event-handler/get-list-watcher-actions
     (assoc watcher :items old-items)
     (assoc watcher :items new-items))))

(defn- test-get-list-watcher-actions-returns-empty-when-watched-list-unchanged []
  (-> (expect (count (items-watcher-result [1 2 3] [1 2 3])))
      (.toBe 0)))

(defn- assert-items-watcher-single-change [old-items new-items assertions-fn]
  (let [result (items-watcher-result old-items new-items)
        [action-key payload] (first result)]
    (-> (expect (count result))
        (.toBe 1))
    (-> (expect action-key)
        (.toBe :ax.changed))
    (assertions-fn payload)))

(defn- test-get-list-watcher-actions-detects-added-items []
  (assert-items-watcher-single-change
   [1 2] [1 2 3]
   (fn [payload]
     (-> (expect (contains? (:added payload) 3))
         (.toBe true))
     (-> (expect (count (:removed payload)))
         (.toBe 0)))))

(defn- test-get-list-watcher-actions-detects-removed-items []
  (assert-items-watcher-single-change
   [1 2 3] [1 2]
   (fn [payload]
     (-> (expect (count (:added payload)))
         (.toBe 0))
     (-> (expect (contains? (:removed payload) 3))
         (.toBe true)))))

(defn- test-get-list-watcher-actions-detects-both-added-and-removed []
  (assert-items-watcher-single-change
   [1 2 3] [2 3 4]
   (fn [payload]
     (-> (expect (contains? (:added payload) 4))
         (.toBe true))
     (-> (expect (contains? (:removed payload) 1))
         (.toBe true)))))

(defn- test-get-list-watcher-actions-uses-id-fn-for-complex-items []
  (let [old-state {:uf/list-watchers {:scripts {:id-fn :script/id :on-change :ax.scripts-changed}}
                   :scripts [{:script/id "a" :name "Script A"}
                             {:script/id "b" :name "Script B"}]}
        new-state {:uf/list-watchers {:scripts {:id-fn :script/id :on-change :ax.scripts-changed}}
                   :scripts [{:script/id "b" :name "Script B"}
                             {:script/id "c" :name "Script C"}]}
        result (event-handler/get-list-watcher-actions old-state new-state)
        [action-key payload] (first result)]
    (-> (expect (count result))
        (.toBe 1))
    (-> (expect action-key)
        (.toBe :ax.scripts-changed))
    (-> (expect (contains? (:added payload) "c"))
        (.toBe true))
    (-> (expect (contains? (:removed payload) "a"))
        (.toBe true))))

(defn- test-get-list-watcher-actions-handles-multiple-watchers-independently []
  (let [old-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}
                                      :tags {:id-fn identity :on-change :ax.tags-changed}}
                   :items [1 2]
                   :tags ["a" "b"]}
        new-state {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}
                                      :tags {:id-fn identity :on-change :ax.tags-changed}}
                   :items [1 2 3]
                   :tags ["b" "c"]}
        result (event-handler/get-list-watcher-actions old-state new-state)]
    (-> (expect (count result))
        (.toBe 2))))

(defn- test-get-list-watcher-actions-treats-nil-lists-as-empty []
  (assert-items-watcher-single-change
   nil [1 2]
   (fn [payload]
     (-> (expect (contains? (:added payload) 1))
         (.toBe true))
     (-> (expect (contains? (:added payload) 2))
         (.toBe true)))))

;; ============================================================
;; dispatch! list-watchers integration tests
;; ============================================================

(defn- test-dispatch-triggers-watcher-actions-when-list-items-are-added []
  (let [!state (atom {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}}
                      :items [1 2]
                      :change-log []})
        ax-handler (fn [state _uf [action & args]]
                     (case action
                       :ax.add-item
                       {:uf/db (update state :items conj (first args))}
                       :ax.items-changed
                       {:uf/db (update state :change-log conj (first args))}
                       :uf/unhandled-ax))
        ex-handler (fn [_dispatch _fx] :uf/unhandled-fx)]
    (event-handler/dispatch! !state ax-handler ex-handler [[:ax.add-item 3]])
    (-> (expect (contains? (set (:items @!state)) 3))
        (.toBe true))
    ;; Watcher should have been triggered
    (-> (expect (count (:change-log @!state)))
        (.toBe 1))
    (-> (expect (contains? (:added (first (:change-log @!state))) 3))
        (.toBe true))))

(defn- test-dispatch-triggers-watcher-actions-when-list-items-are-removed []
  (let [!state (atom {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}}
                      :items [1 2 3]
                      :removed-ids #{}})
        ax-handler (fn [state _uf [action & args]]
                     (case action
                       :ax.remove-item
                       {:uf/db (update state :items (fn [items]
                                                      (vec (remove #(= % (first args)) items))))}
                       :ax.items-changed
                       {:uf/db (update state :removed-ids into (:removed (first args)))}
                       :uf/unhandled-ax))
        ex-handler (fn [_dispatch _fx] :uf/unhandled-fx)]
    (event-handler/dispatch! !state ax-handler ex-handler [[:ax.remove-item 2]])
    ;; Item removed
    (-> (expect (contains? (set (:items @!state)) 2))
        (.toBe false))
    ;; Watcher triggered with removed ID
    (-> (expect (contains? (:removed-ids @!state) 2))
        (.toBe true))))

(defn- test-dispatch-watcher-action-can-schedule-effects []
  (let [!state (atom {:uf/list-watchers {:items {:id-fn identity :on-change :ax.items-changed}}
                      :items [1]})
        effects-log (atom [])
        ax-handler (fn [state _uf [action & args]]
                     (case action
                       :ax.add-item
                       {:uf/db (update state :items conj (first args))}
                       :ax.items-changed
                       {:uf/db state
                        :uf/fxs [[:fx.animate-entry (first args)]]}
                       :uf/unhandled-ax))
        ex-handler (fn [_dispatch [fx & args]]
                     (case fx
                       :fx.animate-entry
                       (swap! effects-log conj [:animate (first args)])
                       :uf/unhandled-fx))]
    (event-handler/dispatch! !state ax-handler ex-handler [[:ax.add-item 2]])
    ;; Effect should have been scheduled from watcher action
    (-> (expect (count @effects-log))
        (.toBe 1))
    (-> (expect (first (first @effects-log)))
        (.toBe :animate))))

;; ============================================================
;; Shadow list watcher tests
;; ============================================================

(defn- shadow-watcher-result [source shadow]
  (let [state {:uf/list-watchers {:scripts/list {:id-fn :script/id
                                                 :shadow-path :ui/scripts-shadow
                                                 :on-change :ax.sync}}
               :scripts/list source
               :ui/scripts-shadow shadow}]
    (event-handler/get-list-watcher-actions state state)))

(defn- assert-shadow-single-change [source shadow assertions-fn]
  (let [result (shadow-watcher-result source shadow)
        [action-key payload] (first result)]
    (-> (expect (count result))
        (.toBe 1))
    (-> (expect action-key)
        (.toBe :ax.sync))
    (assertions-fn payload)))

(defn- test-shadow-list-watcher-detects-items-in-source-but-not-in-shadow []
  (assert-shadow-single-change
   [{:script/id "a"} {:script/id "b"} {:script/id "c"}]
   [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}
    {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}]
   (fn [payload]
     (-> (expect (count (:added-items payload)))
         (.toBe 1))
     (-> (expect (:script/id (first (:added-items payload))))
         (.toBe "c"))
     (-> (expect (count (:removed-ids payload)))
         (.toBe 0)))))

(defn- test-shadow-list-watcher-detects-items-in-shadow-but-not-in-source []
  (assert-shadow-single-change
   [{:script/id "a"}]
   [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}
    {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}]
   (fn [payload]
     (-> (expect (count (:added-items payload)))
         (.toBe 0))
     (-> (expect (contains? (:removed-ids payload) "b"))
         (.toBe true)))))

(defn- test-shadow-list-watcher-returns-empty-when-shadow-matches-source []
  (-> (expect (count (shadow-watcher-result
                      [{:script/id "a"} {:script/id "b"}]
                      [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}
                       {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}])))
      (.toBe 0)))

(defn- test-shadow-list-watcher-ignores-items-already-marked-as-leaving []
  (-> (expect (count (shadow-watcher-result
                      [{:script/id "a"}]
                      [{:item {:script/id "a"} :ui/entering? false :ui/leaving? false}
                       {:item {:script/id "b"} :ui/entering? false :ui/leaving? true}])))
      (.toBe 0)))

(defn- test-shadow-list-watcher-treats-nil-shadow-as-empty []
  (let [result (shadow-watcher-result [{:script/id "a"} {:script/id "b"}] nil)
        [_action-key payload] (first result)]
    (-> (expect (count result))
        (.toBe 1))
    (-> (expect (count (:added-items payload)))
        (.toBe 2))))

;; ============================================================
;; Content change detection tests
;; ============================================================

(defn- test-content-change-detection-detects-content-changes-for-items-with-same-id []
  (let [result (shadow-watcher-result
                [{:script/id "a" :script/code "updated"}
                 {:script/id "b" :script/code "original"}]
                [{:item {:script/id "a" :script/code "original"} :ui/entering? false :ui/leaving? false}
                 {:item {:script/id "b" :script/code "original"} :ui/entering? false :ui/leaving? false}])]
    (-> (expect (count result))
        (.toBe 1))
    (let [[_action-key payload] (first result)]
      (-> (expect (count (:added-items payload)))
          (.toBe 0))
      (-> (expect (count (:removed-ids payload)))
          (.toBe 0)))))

(defn- test-content-change-detection-does-not-fire-when-content-is-identical []
  (-> (expect (count (shadow-watcher-result
                      [{:script/id "a" :script/code "same"} {:script/id "b"}]
                      [{:item {:script/id "a" :script/code "same"} :ui/entering? false :ui/leaving? false}
                       {:item {:script/id "b"} :ui/entering? false :ui/leaving? false}])))
      (.toBe 0)))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "get-list-watcher-actions"
          (fn []
            (test "returns empty when no watchers declared" test-get-list-watcher-actions-returns-empty-when-no-watchers-declared)
            (test "returns empty when watched list unchanged" test-get-list-watcher-actions-returns-empty-when-watched-list-unchanged)
            (test "detects added items" test-get-list-watcher-actions-detects-added-items)
            (test "detects removed items" test-get-list-watcher-actions-detects-removed-items)
            (test "detects both added and removed" test-get-list-watcher-actions-detects-both-added-and-removed)
            (test "uses id-fn for complex items" test-get-list-watcher-actions-uses-id-fn-for-complex-items)
            (test "handles multiple watchers independently" test-get-list-watcher-actions-handles-multiple-watchers-independently)
            (test "treats nil lists as empty" test-get-list-watcher-actions-treats-nil-lists-as-empty)))

(describe "dispatch! with list-watchers"
          (fn []
            (test "triggers watcher actions when list items are added" test-dispatch-triggers-watcher-actions-when-list-items-are-added)
            (test "triggers watcher actions when list items are removed" test-dispatch-triggers-watcher-actions-when-list-items-are-removed)
            (test "watcher action can schedule effects" test-dispatch-watcher-action-can-schedule-effects)))

(describe "get-list-watcher-actions with shadow-path"
          (fn []
            (test "detects items in source but not in shadow (additions)" test-shadow-list-watcher-detects-items-in-source-but-not-in-shadow)
            (test "detects items in shadow but not in source (removals)" test-shadow-list-watcher-detects-items-in-shadow-but-not-in-source)
            (test "returns empty when shadow matches source" test-shadow-list-watcher-returns-empty-when-shadow-matches-source)
            (test "ignores items already marked as leaving in shadow" test-shadow-list-watcher-ignores-items-already-marked-as-leaving)
            (test "treats nil shadow as empty (all items are additions)" test-shadow-list-watcher-treats-nil-shadow-as-empty)))

(describe "get-list-watcher-actions content change detection"
          (fn []
            (test "detects content changes for items with same ID" test-content-change-detection-detects-content-changes-for-items-with-same-id)
            (test "does not fire when content is identical" test-content-change-detection-does-not-fire-when-content-is-identical)))
