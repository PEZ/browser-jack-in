(ns script-utils.fields-test
  "Tests for script field derivation, merging, and parsing."
  (:require ["vitest" :refer [describe test expect]]
            [manifest-parser :as mp]
            [script-utils :as script-utils]))

;; ============================================================
;; derive-script-fields tests
;; ============================================================

(defn- test-derive-fields-from-manifest []
  (let [code "^{:epupp/script-name \"derived.cljs\"\n  :epupp/auto-run-match \"https://example.com/*\"\n  :epupp/description \"Example\"\n  :epupp/run-at \"document-end\"\n  :epupp/inject \"scittle://reagent.js\"}\n(ns derived)"
        script {:script/id "script-1" :script/code code}
        manifest (mp/extract-manifest code)
        derived (script-utils/derive-script-fields script manifest)]
    (-> (expect (:script/name derived))
        (.toBe "derived.cljs"))
    (-> (expect (:script/match derived))
        (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/description derived))
        (.toBe "Example"))
    (-> (expect (:script/run-at derived))
        (.toBe "document-end"))
    (-> (expect (:script/inject derived))
        (.toEqual ["scittle://reagent.js"]))))

(defn- test-derive-manifest-without-auto-run-clears-match []
  (let [code "^{:epupp/script-name \"manual.cljs\"}\n(ns manual)"
        script {:script/id "script-2"
                :script/code code
                :script/match ["https://old.example/*"]}
        manifest (mp/extract-manifest code)
        derived (script-utils/derive-script-fields script manifest)]
    (-> (expect (:script/match derived))
        (.toEqual []))))

(defn- test-derive-nil-manifest-preserves-existing-fields []
  (let [script {:script/id "script-3"
                :script/code "(ns no-manifest)"
                :script/name "old.cljs"
                :script/description "Old"
                :script/match ["https://old.example/*"]
                :script/run-at "document-start"
                :script/inject ["scittle://reagent.js"]}
        derived (script-utils/derive-script-fields script nil)]
    (-> (expect (:script/name derived))
        (.toBe "old.cljs"))
    (-> (expect (:script/match derived))
        (.toEqual ["https://old.example/*"]))
    (-> (expect (:script/run-at derived))
        (.toBe "document-start"))
    (-> (expect (:script/inject derived))
        (.toEqual ["scittle://reagent.js"]))))

(defn- test-derive-library-true-from-manifest []
  (let [code "^{:epupp/script-name \"my_lib.cljs\"\n  :epupp/library? true}\n(ns my-lib)"
        script {:script/id "lib-1" :script/code code}
        manifest (mp/extract-manifest code)
        derived (script-utils/derive-script-fields script manifest)]
    (-> (expect (:script/library? derived))
        (.toBe true))))

(defn- test-derive-no-library-when-omitted []
  (let [code "^{:epupp/script-name \"regular.cljs\"}\n(ns regular)"
        script {:script/id "reg-1" :script/code code}
        manifest (mp/extract-manifest code)
        derived (script-utils/derive-script-fields script manifest)]
    (-> (expect (:script/library? derived))
        (.toBeUndefined))))

(describe "derive-script-fields"
          (fn []
            (test "derives fields from manifest" test-derive-fields-from-manifest)
            (test "manifest without auto-run-match clears match" test-derive-manifest-without-auto-run-clears-match)
            (test "nil manifest preserves existing fields" test-derive-nil-manifest-preserves-existing-fields)))

(describe "derive-script-fields library? flow"
  (fn []
    (test "manifest with library? true sets :script/library?" test-derive-library-true-from-manifest)
    (test "manifest without library? does not set :script/library?" test-derive-no-library-when-omitted)))

;; ============================================================
;; parse-scripts tests
;; ============================================================

(defn- test-parse-derives-fields-when-extractor-provided []
  (let [code "^{:epupp/script-name \"derived.cljs\"\n  :epupp/auto-run-match \"https://example.com/*\"}\n(ns derived)"
        js-scripts #js [#js {:id "script-1"
                             :code code
                             :enabled false
                             :created "2026-01-01T00:00:00.000Z"
                             :modified "2026-01-02T00:00:00.000Z"
                             :builtin false}]
        scripts (script-utils/parse-scripts js-scripts {:extract-manifest mp/extract-manifest})
        script (first scripts)]
    (-> (expect (:script/name script))
        (.toBe "derived.cljs"))
    (-> (expect (:script/match script))
        (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/enabled script))
        (.toBe false))))

(describe "parse-scripts"
          (fn []
            (test "derives fields when extractor is provided" test-parse-derives-fields-when-extractor-provided)))

;; ============================================================
;; normalize-and-merge-script tests
;; ============================================================

(defn- test-normalize-merge-new-script-with-manifest []
  (let [code "^{:epupp/script-name \"My Script\"\n  :epupp/auto-run-match \"https://example.com/*\"\n  :epupp/description \"Test\"\n  :epupp/run-at \"document-end\"\n  :epupp/inject \"scittle://reagent.js\"}\n(ns my-script)"
        manifest (mp/extract-manifest code)
        script {:script/id "script-1" :script/code code}
        result (script-utils/normalize-and-merge-script
                 script nil manifest
                 {:now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:error result)) (.toBeUndefined))
    (-> (expect (:script/name s)) (.toBe "my_script.cljs"))
    (-> (expect (:script/match s)) (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/description s)) (.toBe "Test"))
    (-> (expect (:script/run-at s)) (.toBe "document-end"))
    (-> (expect (:script/inject s)) (.toEqual ["scittle://reagent.js"]))
    (-> (expect (:script/enabled s)) (.toBe false))
    (-> (expect (:script/created s)) (.toBe "2026-01-01T00:00:00.000Z"))
    (-> (expect (:script/modified s)) (.toBe "2026-01-01T00:00:00.000Z"))))

(defn- test-normalize-merge-new-script-without-manifest []
  (let [script {:script/id "script-1"
                :script/code "(ns no-manifest)"
                :script/name "my-test.cljs"
                :script/match ["https://example.com/*"]
                :script/run-at "document-start"}
        result (script-utils/normalize-and-merge-script
                 script nil nil
                 {:now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:error result)) (.toBeUndefined))
    (-> (expect (:script/name s)) (.toBe "my_test.cljs"))
    (-> (expect (:script/match s)) (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/run-at s)) (.toBe "document-start"))
    (-> (expect (:script/enabled s)) (.toBe false))
    (-> (expect (:script/created s)) (.toBe "2026-01-01T00:00:00.000Z"))))

(defn- normalize-with-existing [script manifest]
  (let [existing {:script/id "script-1"
                  :script/name "test.cljs"
                  :script/code "(ns old)"
                  :script/enabled true
                  :script/match ["https://example.com/*"]
                  :script/created "2025-01-01T00:00:00.000Z"}]
    (script-utils/normalize-and-merge-script
     script existing manifest
     {:now-iso "2026-01-01T00:00:00.000Z"})))

(defn- test-normalize-merge-update-preserves-enabled []
  (let [code "^{:epupp/script-name \"test.cljs\"\n  :epupp/auto-run-match \"https://example.com/*\"}\n(ns test)"
        manifest (mp/extract-manifest code)
        result (normalize-with-existing {:script/id "script-1" :script/code code} manifest)
        s (:script result)]
    (-> (expect (:script/enabled s)) (.toBe true))
    (-> (expect (:script/created s)) (.toBe "2025-01-01T00:00:00.000Z"))
    (-> (expect (:script/modified s)) (.toBe "2026-01-01T00:00:00.000Z"))))

(defn- test-normalize-merge-manifest-revokes-auto-run []
  (let [code "^{:epupp/script-name \"test.cljs\"}\n(ns test)"
        manifest (mp/extract-manifest code)
        result (normalize-with-existing {:script/id "script-1" :script/code code} manifest)
        s (:script result)]
    (-> (expect (:script/match s)) (.toEqual []))
    (-> (expect (:script/enabled s)) (.toBe false))))

(defn- test-normalize-merge-name-validation-error []
  (let [script {:script/id "script-1"
                :script/code "(ns test)"
                :script/name "epupp/reserved.cljs"}
        result (script-utils/normalize-and-merge-script
                 script nil nil
                 {:now-iso "2026-01-01T00:00:00.000Z"})]
    (-> (expect (:error result)) (.toContain "reserved namespace"))))

(defn- normalize-new-script [script opts]
  (-> (script-utils/normalize-and-merge-script
       script nil nil
       (merge {:now-iso "2026-01-01T00:00:00.000Z"} opts))
      :script))

(defn- test-normalize-merge-builtin-bypasses-normalization []
  (let [script {:script/id "builtin-1"
                :script/code "(ns builtin)"
                :script/name "Builtin Name"
                :script/builtin? true
                :script/match ["<all_urls>"]}
        s (normalize-new-script script {:is-builtin? true})]
    (-> (expect (:script/name s)) (.toBe "Builtin Name"))))

(defn- test-normalize-merge-always-enabled []
  (let [script {:script/id "script-1"
                :script/code "(ns test)"
                :script/name "test.cljs"
                :script/always-enabled? true
                :script/match []}
        s (normalize-new-script script {})]
    (-> (expect (:script/enabled s)) (.toBe true))))

(defn- test-normalize-merge-no-manifest-falls-back-to-existing-match []
  (let [result (normalize-with-existing {:script/id "script-1" :script/code "(ns updated-code)"} nil)
        s (:script result)]
    (-> (expect (:script/match s)) (.toEqual ["https://example.com/*"]))
    (-> (expect (:script/enabled s)) (.toBe true))))

(defn- test-normalize-merge-rejects-epupp-dot-bypass []
  (let [code "^{:epupp/script-name \"epupp.sneaky\"}\n(ns epupp.sneaky)"
        manifest (mp/extract-manifest code)
        script {:script/id "script-1" :script/code code}
        result (script-utils/normalize-and-merge-script
                 script nil manifest
                 {:now-iso "2026-01-01T00:00:00.000Z"})]
    (-> (expect (:error result))
        (.toBe "Cannot create scripts in reserved namespace: epupp/"))))

(defn- test-normalize-merge-namespace-style-name []
  (let [code "^{:epupp/script-name \"pez.my-cool-script\"\n  :epupp/auto-run-match \"https://example.com/*\"}\n(ns pez.my-cool-script)"
        manifest (mp/extract-manifest code)
        script {:script/id "script-1" :script/code code}
        result (script-utils/normalize-and-merge-script
                 script nil manifest
                 {:now-iso "2026-01-01T00:00:00.000Z"})
        s (:script result)]
    (-> (expect (:error result)) (.toBeUndefined))
    (-> (expect (:script/name s)) (.toBe "pez/my_cool_script.cljs"))))

(describe "normalize-and-merge-script"
          (fn []
            (test "new script with manifest derives all fields" test-normalize-merge-new-script-with-manifest)
            (test "new script without manifest uses own fields" test-normalize-merge-new-script-without-manifest)
            (test "update preserves existing enabled state" test-normalize-merge-update-preserves-enabled)
            (test "manifest without auto-run revokes and disables" test-normalize-merge-manifest-revokes-auto-run)
            (test "returns error on invalid name" test-normalize-merge-name-validation-error)
            (test "builtin bypasses name normalization" test-normalize-merge-builtin-bypasses-normalization)
            (test "always-enabled stays enabled" test-normalize-merge-always-enabled)
            (test "no manifest falls back to existing match" test-normalize-merge-no-manifest-falls-back-to-existing-match)
            (test "rejects epupp. dot bypass of reserved namespace" test-normalize-merge-rejects-epupp-dot-bypass)
            (test "namespace-style name normalizes correctly" test-normalize-merge-namespace-style-name)))
