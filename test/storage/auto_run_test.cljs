(ns storage.auto-run-test
  "Tests for auto-run match extraction and revocation logic."
  (:require ["vitest" :refer [describe test expect]]
            [manifest-parser :as mp]))

;; ============================================================
;; Helper Functions
;; ============================================================

(defn extract-auto-run-from-manifest
  "Extract auto-run match from manifest, handling the key scenarios:
   1. Manifest present with match → use match
   2. Manifest present without match → explicit empty (revocation)
   3. No manifest → preserve existing (no change)

   Returns {:match [...] :has-manifest? bool :explicit-empty? bool}"
  [code existing-match]
  (let [manifest (try (mp/extract-manifest code)
                      (catch :default _ nil))
        has-manifest? (some? manifest)]
    (cond
      ;; Manifest has auto-run-match key
      (and has-manifest? (js/Object.hasOwn manifest "auto-run-match"))
      (let [match-value (get manifest "auto-run-match")
            normalized (cond
                         (nil? match-value) []
                         (string? match-value) (if (empty? match-value) [] [match-value])
                         (js/Array.isArray match-value) (vec match-value)
                         :else [])]
        {:match normalized
         :has-manifest? true
         :explicit-empty? (empty? normalized)})

      ;; Manifest present but no auto-run-match key → revocation
      has-manifest?
      {:match []
       :has-manifest? true
       :explicit-empty? true}

      ;; No manifest → preserve existing
      :else
      {:match (or existing-match [])
       :has-manifest? false
       :explicit-empty? false})))

;; ============================================================
;; Test Functions
;; ============================================================

(defn- get-manifest-and-match [code]
  (let [manifest (mp/extract-manifest code)]
    #js {:manifest manifest :match (aget manifest "auto-run-match")}))

(defn- check-full-result [result expected-match expected-has-manifest? expected-explicit-empty?]
  (-> (expect (:match result))
      (.toEqual expected-match))
  (-> (expect (:has-manifest? result))
      (.toBe expected-has-manifest?))
  (-> (expect (:explicit-empty? result))
      (.toBe expected-explicit-empty?)))

(defn- test-manifest-with-auto-run-match-has-match []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match \"https://example.com/*\"}
(ns test)"
        result (get-manifest-and-match code)]
    (-> (expect (.-match result))
        (.toBe "https://example.com/*"))))

(defn- test-manifest-without-auto-run-match-is-nil []
  (let [code "^{:epupp/script-name \"test.cljs\"}
(ns test)"
        result (get-manifest-and-match code)]
    (-> (expect (.-manifest result))
        (.toBeTruthy))
    (-> (expect (.-match result))
        (.toBeUndefined))))

(defn- test-no-manifest-returns-nil []
  (let [code "(defn foo [] 42)"
        manifest (mp/extract-manifest code)]
    (-> (expect manifest)
        (.toBeUndefined))))

(defn- test-manifest-with-empty-vector-match []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match []}
(ns test)"
        result (get-manifest-and-match code)]
    (-> (expect (js/Array.isArray (.-match result)))
        (.toBe true))
    (-> (expect (.-length (.-match result)))
        (.toBe 0))))

(defn- test-extract-manifest-with-match-returns-match []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match \"https://example.com/*\"}
(ns test)"
        result (extract-auto-run-from-manifest code nil)]
    (check-full-result result ["https://example.com/*"] true false)))

(defn- test-extract-manifest-with-vector-match-returns-vector []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match [\"https://github.com/*\" \"https://gist.github.com/*\"]}
(ns test)"
        result (extract-auto-run-from-manifest code nil)]
    (-> (expect (:match result))
        (.toEqual ["https://github.com/*" "https://gist.github.com/*"]))))

(defn- test-extract-manifest-without-auto-run-match-revokes []
  (let [code "^{:epupp/script-name \"test.cljs\"}
(ns test)"
        existing-match ["https://old-pattern.com/*"]
        result (extract-auto-run-from-manifest code existing-match)]
    (check-full-result result [] true true)))

(defn- test-extract-no-manifest-preserves-existing-match []
  (let [code "(defn foo [] 42)"
        existing-match ["https://preserve-me.com/*"]
        result (extract-auto-run-from-manifest code existing-match)]
    (check-full-result result ["https://preserve-me.com/*"] false false)))

(defn- test-extract-no-manifest-no-existing-empty-match []
  (let [code "(defn foo [] 42)"
        result (extract-auto-run-from-manifest code nil)]
    (-> (expect (:match result))
        (.toEqual []))))

(defn- test-extract-manifest-with-empty-vector-explicit-empty []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match []}
(ns test)"
        existing-match ["https://should-be-cleared.com/*"]
        result (extract-auto-run-from-manifest code existing-match)]
    (-> (expect (:match result))
        (.toEqual []))
    (-> (expect (:explicit-empty? result))
        (.toBe true))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "Auto-run revocation logic"
          (fn []
            (test "manifest with auto-run-match → script should have match" test-manifest-with-auto-run-match-has-match)
            (test "manifest without auto-run-match → match should be nil" test-manifest-without-auto-run-match-is-nil)
            (test "no manifest (plain code) → manifest is nil" test-no-manifest-returns-nil)
            (test "manifest with empty vector match → match is empty" test-manifest-with-empty-vector-match)))

(describe "extract-auto-run-from-manifest helper"
          (fn []
            (test "manifest with match → returns match" test-extract-manifest-with-match-returns-match)
            (test "manifest with vector match → returns vector" test-extract-manifest-with-vector-match-returns-vector)
            (test "manifest without auto-run-match → revokes (empty match)" test-extract-manifest-without-auto-run-match-revokes)
            (test "no manifest → preserves existing match" test-extract-no-manifest-preserves-existing-match)
            (test "no manifest and no existing → empty match" test-extract-no-manifest-no-existing-empty-match)
            (test "manifest with empty vector match → explicit empty" test-extract-manifest-with-empty-vector-explicit-empty)))
