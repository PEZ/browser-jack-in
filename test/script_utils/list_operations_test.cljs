(ns script-utils.list-operations-test
  "Tests for script list diffing and visibility filtering."
  (:require ["vitest" :refer [describe test expect]]
            [script-utils :as script-utils]))

;; ============================================================
;; diff-scripts tests
;; ============================================================

(defn- assert-diff [old-scripts new-scripts expected]
  (let [diff (script-utils/diff-scripts old-scripts new-scripts)]
    (-> (expect (:added diff))
        (.toEqual (:added expected)))
    (-> (expect (:modified diff))
        (.toEqual (:modified expected)))
    (-> (expect (:removed diff))
        (.toEqual (:removed expected)))))

(defn- test-diff-scripts-detects-added []
  (assert-diff [{:script/name "existing.cljs" :script/code "(ns existing)"}]
               [{:script/name "existing.cljs" :script/code "(ns existing)"}
                {:script/name "new.cljs" :script/code "(ns new)"}]
               {:added ["new.cljs"] :modified [] :removed []}))

(defn- test-diff-scripts-detects-removed []
  (assert-diff [{:script/name "existing.cljs" :script/code "(ns existing)"}
                {:script/name "removed.cljs" :script/code "(ns removed)"}]
               [{:script/name "existing.cljs" :script/code "(ns existing)"}]
               {:added [] :modified [] :removed ["removed.cljs"]}))

(defn- test-diff-scripts-detects-modified-code []
  (assert-diff [{:script/name "changed.cljs" :script/code "(ns old)"}]
               [{:script/name "changed.cljs" :script/code "(ns new)"}]
               {:added [] :modified ["changed.cljs"] :removed []}))

(defn- test-diff-scripts-no-changes []
  (assert-diff [{:script/name "unchanged.cljs" :script/code "(ns unchanged)"}]
               [{:script/name "unchanged.cljs" :script/code "(ns unchanged)"}]
               {:added [] :modified [] :removed []}))

(defn- test-diff-scripts-multiple-changes []
  (let [old-scripts [{:script/name "a.cljs" :script/code "(ns a)"}
                     {:script/name "b.cljs" :script/code "(ns b)"}
                     {:script/name "c.cljs" :script/code "(ns c-old)"}]
        new-scripts [{:script/name "a.cljs" :script/code "(ns a)"}
                     {:script/name "c.cljs" :script/code "(ns c-new)"}
                     {:script/name "d.cljs" :script/code "(ns d)"}]
        diff (script-utils/diff-scripts old-scripts new-scripts)]
    (-> (expect (:added diff))
        (.toEqual ["d.cljs"]))
    (-> (expect (:modified diff))
        (.toEqual ["c.cljs"]))
    (-> (expect (:removed diff))
        (.toEqual ["b.cljs"]))))

(defn- test-diff-scripts-code-vs-metadata-change []
  (let [old-scripts [{:script/name "test.cljs"
                      :script/code "(ns test)"
                      :script/description "Old description"}]
        new-scripts [{:script/name "test.cljs"
                      :script/code "(ns test)"
                      :script/description "New description"}]
        diff (script-utils/diff-scripts old-scripts new-scripts)]
    (-> (expect (:modified diff))
        (.toEqual []))))

(describe "Script list diffing"
  (fn []
    (test "Added scripts detected" test-diff-scripts-detects-added)
    (test "Removed scripts detected" test-diff-scripts-detects-removed)
    (test "Modified scripts detected (code changed)" test-diff-scripts-detects-modified-code)
    (test "No changes → empty diff" test-diff-scripts-no-changes)
    (test "Multiple simultaneous changes" test-diff-scripts-multiple-changes)
    (test "Code change vs metadata-only change" test-diff-scripts-code-vs-metadata-change)))

;; ============================================================
;; filter-visible-scripts tests
;; ============================================================

(defn- test-filter-visible-include-hidden-returns-all []
  (let [scripts [{:script/name "user.cljs" :script/builtin? false}
                 {:script/name "builtin.cljs" :script/builtin? true}]
        filtered (script-utils/filter-visible-scripts scripts true)]
    (-> (expect (count filtered))
        (.toBe 2))))

(defn- test-filter-visible-exclude-hidden-filters-builtins []
  (let [scripts [{:script/name "user.cljs" :script/builtin? false}
                 {:script/name "builtin.cljs" :script/builtin? true}]
        filtered (script-utils/filter-visible-scripts scripts false)]
    (-> (expect (count filtered))
        (.toBe 1))
    (-> (expect (:script/name (first filtered)))
        (.toBe "user.cljs"))))

(defn- test-filter-visible-empty-list []
  (let [filtered (script-utils/filter-visible-scripts [] false)]
    (-> (expect filtered)
        (.toEqual []))))

(defn- test-filter-visible-only-builtins-with-hidden-false []
  (let [scripts [{:script/name "builtin1.cljs" :script/builtin? true}
                 {:script/name "builtin2.cljs" :script/builtin? true}]
        filtered (script-utils/filter-visible-scripts scripts false)]
    (-> (expect filtered)
        (.toEqual []))))

(defn- test-filter-visible-mixed-scripts []
  (let [scripts [{:script/name "user1.cljs" :script/builtin? false}
                 {:script/name "builtin.cljs" :script/builtin? true}
                 {:script/name "user2.cljs" :script/builtin? false}]
        filtered (script-utils/filter-visible-scripts scripts false)]
    (-> (expect (count filtered))
        (.toBe 2))
    (-> (expect (mapv :script/name filtered))
        (.toEqual ["user1.cljs" "user2.cljs"]))))

(describe "Script visibility filtering"
  (fn []
    (test "include-hidden? true → returns all scripts" test-filter-visible-include-hidden-returns-all)
    (test "include-hidden? false → filters out built-ins" test-filter-visible-exclude-hidden-filters-builtins)
    (test "Empty list → returns empty" test-filter-visible-empty-list)
    (test "Only built-ins with hidden=false → returns empty" test-filter-visible-only-builtins-with-hidden-false)
    (test "Mixed scripts with hidden=false → returns only user scripts" test-filter-visible-mixed-scripts)))
