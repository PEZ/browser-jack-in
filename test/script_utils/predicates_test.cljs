(ns script-utils.predicates-test
  "Tests for script predicate functions and section classification."
  (:require ["vitest" :refer [describe test expect]]
            [script-utils :as script-utils]))

;; ============================================================
;; special-script? predicate tests
;; ============================================================

(defn- test-special-script-true-when-flag-set []
  (let [script {:script/name "installer.cljs" :script/special? true}]
    (-> (expect (script-utils/special-script? script))
        (.toBe true))))

(defn- test-special-script-false-when-flag-absent []
  (let [script {:script/name "regular.cljs"}]
    (-> (expect (script-utils/special-script? script))
        (.toBe false))))

(defn- test-special-script-false-when-flag-false []
  (let [script {:script/name "regular.cljs" :script/special? false}]
    (-> (expect (script-utils/special-script? script))
        (.toBe false))))

(defn- test-special-script-false-when-flag-nil []
  (let [script {:script/name "regular.cljs" :script/special? nil}]
    (-> (expect (script-utils/special-script? script))
        (.toBe false))))

(describe "special-script? predicate"
  (fn []
    (test "returns true when :script/special? is true" test-special-script-true-when-flag-set)
    (test "returns false when :script/special? is absent" test-special-script-false-when-flag-absent)
    (test "returns false when :script/special? is false" test-special-script-false-when-flag-false)
    (test "returns false when :script/special? is nil" test-special-script-false-when-flag-nil)))

;; ============================================================
;; library-script? predicate tests
;; ============================================================

(defn- test-library-script-true-when-flag-set []
  (let [script {:script/name "my_lib.cljs" :script/library? true}]
    (-> (expect (script-utils/library-script? script))
        (.toBe true))))

(defn- test-library-script-false-when-flag-absent []
  (let [script {:script/name "regular.cljs"}]
    (-> (expect (script-utils/library-script? script))
        (.toBe false))))

(defn- test-library-script-false-when-flag-false []
  (let [script {:script/name "regular.cljs" :script/library? false}]
    (-> (expect (script-utils/library-script? script))
        (.toBe false))))

(defn- test-library-script-false-when-flag-nil []
  (let [script {:script/name "regular.cljs" :script/library? nil}]
    (-> (expect (script-utils/library-script? script))
        (.toBe false))))

(describe "library-script? predicate"
  (fn []
    (test "returns true when :script/library? is true" test-library-script-true-when-flag-set)
    (test "returns false when :script/library? is absent" test-library-script-false-when-flag-absent)
    (test "returns false when :script/library? is false" test-library-script-false-when-flag-false)
    (test "returns false when :script/library? is nil" test-library-script-false-when-flag-nil)))

;; ============================================================
;; Library section classification tests
;; ============================================================

(defn- assert-section-classification [script expected-library? expected-manual?]
  (let [is-library-section? (and (script-utils/library-script? script)
                                 (not (script-utils/special-script? script))
                                 (empty? (:script/match script)))
        is-manual-section? (and (not (script-utils/special-script? script))
                                (not (script-utils/library-script? script))
                                (empty? (:script/match script)))]
    (-> (expect is-library-section?)
        (.toBe expected-library?))
    (-> (expect is-manual-section?)
        (.toBe expected-manual?))))

(defn- test-classify-library-only-no-match []
  (assert-section-classification
   {:script/name "my_lib.cljs"
    :script/library? true
    :script/match []}
   true
   false))

(defn- test-classify-library-with-match-goes-to-matching []
  (let [script {:script/name "lib_with_match.cljs"
                :script/library? true
                :script/match ["https://example.com/*"]}
        is-library-section? (and (script-utils/library-script? script)
                                 (not (script-utils/special-script? script))
                                 (empty? (:script/match script)))
        has-match? (seq (:script/match script))]
    (-> (expect is-library-section?)
        (.toBe false))
    (-> (expect has-match?)
        (.toBeTruthy))))

(defn- test-classify-non-library-no-match-goes-to-manual []
  (assert-section-classification
   {:script/name "manual.cljs"
    :script/match []}
   false
   true))

(describe "Library section classification"
  (fn []
    (test "library-only script (no match) goes to libraries section" test-classify-library-only-no-match)
    (test "library with match patterns goes to matching, not libraries" test-classify-library-with-match-goes-to-matching)
    (test "non-library script (no match) goes to manual section" test-classify-non-library-no-match-goes-to-manual)))
