(ns script-utils.name-test
  "Tests for script name normalization, validation, and conflict detection."
  (:require ["vitest" :refer [describe test expect]]
            [script-utils :as script-utils]))

;; ============================================================
;; Helpers for deterministic property-style tests
;; ============================================================

(defn- lcg-next [seed]
  (mod (+ (* seed 1664525) 1013904223) 4294967296))

(defn- lcg-rand-int [seed n]
  (let [next (lcg-next seed)]
    [next (mod next n)]))

(defn- rand-char [seed]
  (let [chars "abcdefghijklmnopqrstuvwxyz0123456789_"
        [seed idx] (lcg-rand-int seed (.-length chars))]
    [seed (.charAt chars idx)]))

(defn- gen-segment [seed]
  (let [[seed len] (lcg-rand-int seed 8)
        len (+ 1 len)]
    (loop [seed seed i 0 out ""]
      (if (< i len)
        (let [[seed ch] (rand-char seed)]
          (recur seed (inc i) (str out ch)))
        [seed out]))))

(defn- gen-valid-name [seed]
  (let [[seed seg-count] (lcg-rand-int seed 3)
        seg-count (+ 1 seg-count)]
    (loop [seed seed i 0 segs []]
      (if (< i seg-count)
        (let [[seed seg] (gen-segment seed)
              seg (if (and (= i 0) (= seg "epupp")) "epuppx" seg)]
          (recur seed (inc i) (conj segs seg)))
        [seed (str (.join segs "/") ".cljs")]))))

;; ============================================================
;; normalize-script-name tests
;; ============================================================

(defn- test-normalize-spaces-to-underscores []
  (-> (expect (script-utils/normalize-script-name "My Script"))
      (.toBe "my_script.cljs")))

(defn- test-normalize-dashes-to-underscores []
  (-> (expect (script-utils/normalize-script-name "my-script"))
      (.toBe "my_script.cljs")))

(defn- test-normalize-dots-to-path-separators []
  (-> (expect (script-utils/normalize-script-name "pez.linkedin-squirrel"))
      (.toBe "pez/linkedin_squirrel.cljs")))

(defn- test-normalize-multiple-dots []
  (-> (expect (script-utils/normalize-script-name "a.b.c"))
      (.toBe "a/b/c.cljs")))

(defn- test-normalize-strips-extension []
  (-> (expect (script-utils/normalize-script-name "my_script.cljs"))
      (.toBe "my_script.cljs")))

(defn- test-normalize-mixed-separators []
  (-> (expect (script-utils/normalize-script-name "My Cool.Script-Name"))
      (.toBe "my_cool/script_name.cljs")))

(defn- test-normalize-preserves-slash []
  (-> (expect (script-utils/normalize-script-name "folder/my-script"))
      (.toBe "folder/my_script.cljs")))

(defn- test-normalize-namespace-style []
  (-> (expect (script-utils/normalize-script-name "pez.element-printing"))
      (.toBe "pez/element_printing.cljs")))

(describe "normalize-script-name"
          (fn []
            (test "spaces to underscores" test-normalize-spaces-to-underscores)
            (test "dashes to underscores" test-normalize-dashes-to-underscores)
            (test "dots to path separators" test-normalize-dots-to-path-separators)
            (test "multiple dots" test-normalize-multiple-dots)
            (test "strips .cljs extension" test-normalize-strips-extension)
            (test "mixed separators" test-normalize-mixed-separators)
            (test "preserves existing slash" test-normalize-preserves-slash)
            (test "namespace-style name" test-normalize-namespace-style)))

;; ============================================================
;; validate-script-name tests
;; ============================================================

(defn- test-validate-accepts-valid-names []
  (-> (expect (script-utils/validate-script-name "test.cljs"))
      (.toBe nil))
  (-> (expect (script-utils/validate-script-name "folder/test.cljs"))
      (.toBe nil)))

(defn- test-validate-rejects-reserved-namespace []
  (-> (expect (script-utils/validate-script-name "epupp/test.cljs"))
      (.toContain "reserved namespace")))

(defn- test-validate-rejects-leading-slash []
  (-> (expect (script-utils/validate-script-name "/test.cljs"))
      (.toContain "start with '/'")))

(defn- test-validate-rejects-leading-dot []
  (-> (expect (script-utils/validate-script-name ".hidden"))
      (.toContain "start with '.'"))
  (-> (expect (script-utils/validate-script-name "..foo"))
      (.toContain "start with '.'")))

(defn- test-validate-rejects-dot-slash-and-dot-dot-slash []
  (-> (expect (script-utils/validate-script-name "./test.cljs"))
      (.toContain "./' or '../'"))
  (-> (expect (script-utils/validate-script-name "../test.cljs"))
      (.toContain "./' or '../'"))
  (-> (expect (script-utils/validate-script-name "folder/../test.cljs"))
      (.toContain "./' or '../'")))

(defn- test-validate-property-valid-names-accepted []
  (loop [seed 1 idx 0]
    (when (< idx 200)
      (let [[seed name] (gen-valid-name seed)]
        (-> (expect (script-utils/validate-script-name name))
            (.toBe nil))
        (recur seed (inc idx))))))

(defn- test-validate-property-reserved-namespace-rejected []
  (loop [seed 2 idx 0]
    (when (< idx 200)
      (let [[seed name] (gen-valid-name seed)
            bad-name (str "epupp/" name)
            err (script-utils/validate-script-name bad-name)]
        (-> (expect err) (.toContain "reserved namespace"))
        (recur seed (inc idx))))))

(defn- test-validate-property-leading-slash-rejected []
  (loop [seed 3 idx 0]
    (when (< idx 200)
      (let [[seed name] (gen-valid-name seed)
            bad-name (str "/" name)
            err (script-utils/validate-script-name bad-name)]
        (-> (expect err) (.toContain "start with '/'"))
        (recur seed (inc idx))))))

(defn- test-validate-property-path-traversal-rejected []
  (loop [seed 4 idx 0]
    (when (< idx 200)
      (let [[seed name] (gen-valid-name seed)
            bad-name (str "foo/../" name)
            err (script-utils/validate-script-name bad-name)]
        (-> (expect err) (.toContain "./' or '../'"))
        (recur seed (inc idx))))))

(describe "validate-script-name"
          (fn []
            (test "accepts valid names" test-validate-accepts-valid-names)
            (test "rejects reserved namespace" test-validate-rejects-reserved-namespace)
            (test "rejects leading slash" test-validate-rejects-leading-slash)
            (test "rejects leading dot" test-validate-rejects-leading-dot)
            (test "rejects dot-slash and dot-dot-slash" test-validate-rejects-dot-slash-and-dot-dot-slash)
            (test "property: valid names are accepted" test-validate-property-valid-names-accepted)
            (test "property: reserved namespace is rejected" test-validate-property-reserved-namespace-rejected)
            (test "property: leading slash is rejected" test-validate-property-leading-slash-rejected)
            (test "property: path traversal is rejected" test-validate-property-path-traversal-rejected)))

;; ============================================================
;; Name conflict detection tests
;; ============================================================

(defn- test-detect-name-conflict-new-script-unique-name []
  (let [scripts [{:script/name "existing.cljs"}
                 {:script/name "another.cljs"}]
        conflict (script-utils/detect-name-conflict scripts "unique_name" nil)]
    (-> (expect conflict)
        (.toBe nil))))

(defn- test-detect-name-conflict-new-script-existing-name []
  (let [scripts [{:script/name "existing.cljs"}
                 {:script/name "another.cljs"}]
        conflict (script-utils/detect-name-conflict scripts "existing.cljs" nil)]
    (-> (expect conflict)
        (.not.toBe nil))
    (-> (expect (:script/name conflict))
        (.toBe "existing.cljs"))))

(defn- test-detect-name-conflict-rename-to-unique []
  (let [scripts [{:script/name "current.cljs"}
                 {:script/name "other.cljs"}]
        conflict (script-utils/detect-name-conflict scripts "New Name" "current.cljs")]
    (-> (expect conflict)
        (.toBe nil))))

(defn- test-detect-name-conflict-rename-to-existing []
  (let [scripts [{:script/name "current.cljs"}
                 {:script/name "other.cljs"}]
        conflict (script-utils/detect-name-conflict scripts "other.cljs" "current.cljs")]
    (-> (expect conflict)
        (.not.toBe nil))
    (-> (expect (:script/name conflict))
        (.toBe "other.cljs"))))

(defn- test-detect-name-conflict-rename-to-same-name []
  (let [scripts [{:script/name "current.cljs"}
                 {:script/name "other.cljs"}]
        conflict (script-utils/detect-name-conflict scripts "current.cljs" "current.cljs")]
    (-> (expect conflict)
        (.toBe nil))))

(defn- test-detect-name-conflict-case-insensitive []
  (let [scripts [{:script/name "my_script.cljs"}
                 {:script/name "other.cljs"}]
        conflict (script-utils/detect-name-conflict scripts "My Script" nil)]
    (-> (expect conflict)
        (.not.toBe nil))
    (-> (expect (:script/name conflict))
        (.toBe "my_script.cljs"))))

(defn- test-detect-name-conflict-normalization-with-spaces []
  (let [scripts [{:script/name "my_cool_script.cljs"}]
        conflict (script-utils/detect-name-conflict scripts "My Cool Script" nil)]
    (-> (expect conflict)
        (.not.toBe nil))))

(defn- test-detect-name-conflict-empty-scripts-list []
  (let [conflict (script-utils/detect-name-conflict [] "any_name" nil)]
    (-> (expect conflict)
        (.toBe nil))))

(describe "Name conflict detection"
  (fn []
    (test "new script with unique name → no conflict" test-detect-name-conflict-new-script-unique-name)
    (test "new script with existing name → conflict" test-detect-name-conflict-new-script-existing-name)
    (test "rename to unique name → no conflict" test-detect-name-conflict-rename-to-unique)
    (test "rename to existing name → conflict" test-detect-name-conflict-rename-to-existing)
    (test "rename to same name → no conflict" test-detect-name-conflict-rename-to-same-name)
    (test "case insensitive matching → conflict" test-detect-name-conflict-case-insensitive)
    (test "normalization with spaces → conflict" test-detect-name-conflict-normalization-with-spaces)
    (test "empty scripts list → no conflict" test-detect-name-conflict-empty-scripts-list)))
