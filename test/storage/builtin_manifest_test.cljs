(ns storage.builtin-manifest-test
  "Tests for building built-in scripts from manifest metadata."
  (:require ["vitest" :refer [describe test expect]]
            [storage :as storage]))

;; ============================================================
;; Test Functions
;; ============================================================

(defn- test-build-bundled-complete-manifest-all-fields []
  (let [bundled {:script/id "builtin-1"
                 :path "userscripts/test.cljs"
                 :name "test.cljs"}
        code "{:epupp/script-name \"complete.cljs\"
 :epupp/description \"A complete script\"
 :epupp/auto-run-match [\"https://example.com/*\" \"https://test.com/*\"]
 :epupp/run-at \"document-start\"
 :epupp/inject [\"scittle://reagent.js\" \"scittle://re-frame.js\"]}

(ns test-script)
(println \"hello\")"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/id result))
        (.toBe "builtin-1"))
    (-> (expect (:script/code result))
        (.toBe code))
    (-> (expect (:script/builtin? result))
        (.toBe true))
    (-> (expect (:script/name result))
        (.toBe "complete.cljs"))
    (-> (expect (:script/description result))
        (.toBe "A complete script"))
    (-> (expect (:script/match result))
        (.toEqual ["https://example.com/*" "https://test.com/*"]))
    (-> (expect (:script/run-at result))
        (.toBe "document-start"))
    (-> (expect (:script/inject result))
        (.toEqual ["scittle://reagent.js" "scittle://re-frame.js"]))))

(defn- test-build-bundled-minimal-manifest-only-script-name []
  (let [bundled {:script/id "builtin-2"
                 :path "userscripts/minimal.cljs"
                 :name "minimal.cljs"}
        code "{:epupp/script-name \"minimal.cljs\"}

(ns minimal)
(println \"minimal\")"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/id result))
        (.toBe "builtin-2"))
    (-> (expect (:script/code result))
        (.toBe code))
    (-> (expect (:script/builtin? result))
        (.toBe true))
    (-> (expect (:script/name result))
        (.toBe "minimal.cljs"))
    (-> (expect (contains? result :script/description))
        (.toBe false))
    (-> (expect (:script/match result))
        (.toEqual []))
    (-> (expect (contains? result :script/inject))
        (.toBe false))))

(defn- test-build-bundled-manifest-with-string-inject []
  (let [bundled {:script/id "builtin-3"
                 :path "userscripts/string-inject.cljs"
                 :name "string-inject.cljs"}
        code "{:epupp/script-name \"string-inject.cljs\"
 :epupp/inject \"scittle://reagent.js\"}

(ns string-inject)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/inject result))
        (.toEqual ["scittle://reagent.js"]))))

(defn- test-build-bundled-manifest-with-array-inject []
  (let [bundled {:script/id "builtin-4"
                 :path "userscripts/array-inject.cljs"
                 :name "array-inject.cljs"}
        code "{:epupp/script-name \"array-inject.cljs\"
 :epupp/inject [\"scittle://reagent.js\" \"scittle://pprint.js\"]}

(ns array-inject)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/inject result))
        (.toEqual ["scittle://reagent.js" "scittle://pprint.js"]))))

(defn- test-build-bundled-manifest-with-match-patterns []
  (let [bundled {:script/id "builtin-5"
                 :path "userscripts/with-match.cljs"
                 :name "with-match.cljs"}
        code "{:epupp/script-name \"with-match.cljs\"
 :epupp/auto-run-match \"https://github.com/*\"}

(ns with-match)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/match result))
        (.toEqual ["https://github.com/*"]))))

(defn- test-build-bundled-manifest-without-match-manual-only []
  (let [bundled {:script/id "builtin-6"
                 :path "userscripts/manual-only.cljs"
                 :name "manual-only.cljs"}
        code "{:epupp/script-name \"manual-only.cljs\"
 :epupp/description \"Manual execution only\"}

(ns manual-only)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/match result))
        (.toEqual []))))

(defn- test-build-bundled-invalid-run-at-defaults-to-document-idle []
  (let [bundled {:script/id "builtin-7"
                 :path "userscripts/invalid-run-at.cljs"
                 :name "invalid-run-at.cljs"}
        code "{:epupp/script-name \"invalid-run-at.cljs\"
 :epupp/run-at \"invalid-timing\"}

(ns invalid-run-at)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/run-at result))
        (.toBe "document-idle"))))

(defn- test-build-bundled-uses-fallback-name-when-no-manifest []
  (let [bundled {:script/id "builtin-8"
                 :path "userscripts/no-manifest.cljs"
                 :name "fallback-name.cljs"}
        code "(ns no-manifest)
(println \"no manifest at all\")"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/name result))
        (.toBe "fallback-name.cljs"))
    (-> (expect (:script/builtin? result))
        (.toBe true))
    (-> (expect (contains? result :script/inject))
        (.toBe false))))

(defn- test-build-bundled-manifest-with-string-match []
  (let [bundled {:script/id "builtin-9"
                 :path "userscripts/string-match.cljs"
                 :name "string-match.cljs"}
        code "{:epupp/script-name \"string-match.cljs\"
 :epupp/auto-run-match \"https://example.com/*\"}

(ns string-match)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/match result))
        (.toEqual ["https://example.com/*"]))))

(defn- test-build-bundled-manifest-with-empty-match-array []
  (let [bundled {:script/id "builtin-10"
                 :path "userscripts/empty-match.cljs"
                 :name "empty-match.cljs"}
        code "{:epupp/script-name \"empty-match.cljs\"
 :epupp/auto-run-match []}

(ns empty-match)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/match result))
        (.toEqual []))))

(defn- test-build-bundled-always-enabled-propagated []
  (let [bundled {:script/id "builtin-sponsor"
                 :path "userscripts/sponsor.cljs"
                 :name "sponsor.cljs"
                 :always-enabled? true}
        code "{:epupp/script-name \"sponsor.cljs\"
 :epupp/auto-run-match \"https://github.com/sponsors/PEZ*\"}

(ns epupp.sponsor)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/always-enabled? result))
        (.toBe true))))

(defn- test-build-bundled-without-always-enabled []
  (let [bundled {:script/id "builtin-normal"
                 :path "userscripts/normal.cljs"
                 :name "normal.cljs"}
        code "{:epupp/script-name \"normal.cljs\"}

(ns normal)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (contains? result :script/always-enabled?))
        (.toBe false))))

(defn- test-build-bundled-special-flags-propagated []
  (let [bundled {:script/id "builtin-installer"
                 :path "userscripts/epupp/web_userscript_installer.cljs"
                 :name "epupp/web_userscript_installer.cljs"
                 :special? true
                 :web-installer-scan true}
        code "{:epupp/script-name \"epupp/web_userscript_installer.cljs\"
 :epupp/description \"Web Userscript Installer\"}

(ns epupp.web-installer)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/special? result))
        (.toBe true))
    (-> (expect (:script/web-installer-scan result))
        (.toBe true))))

(defn- test-build-bundled-internal-helpers-library []
  (let [bundled {:script/id "epupp-builtin-internal-helpers"
                 :path "userscripts/epupp/internal/helpers.cljs"
                 :name "epupp/internal/helpers.cljs"
                 :always-enabled? true}
        code "{:epupp/script-name \"epupp/internal/helpers.cljs\"
   :epupp/description \"Internal helpers for built-in Epupp scripts: bridge messaging and manifest parsing\"}

  (ns epupp.internal.helpers)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (:script/id result))
        (.toBe "epupp-builtin-internal-helpers"))
    (-> (expect (:script/name result))
        (.toBe "epupp/internal/helpers.cljs"))
    (-> (expect (:script/description result))
        (.toBe "Internal helpers for built-in Epupp scripts: bridge messaging and manifest parsing"))
    (-> (expect (:script/always-enabled? result))
        (.toBe true))
    (-> (expect (:script/match result))
        (.toEqual []))))

(defn- test-build-bundled-without-special-flags []
  (let [bundled {:script/id "builtin-normal"
                 :path "userscripts/normal.cljs"
                 :name "normal.cljs"}
        code "{:epupp/script-name \"normal.cljs\"}

(ns normal)"
        result (storage/build-bundled-script bundled code)]
    (-> (expect (contains? result :script/special?))
        (.toBe false))
    (-> (expect (contains? result :script/web-installer-scan))
        (.toBe false))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "Built-in script building from manifest"
          (fn []
            (test "complete manifest with all fields" test-build-bundled-complete-manifest-all-fields)
            (test "minimal manifest (only script-name)" test-build-bundled-minimal-manifest-only-script-name)
            (test "manifest with string inject" test-build-bundled-manifest-with-string-inject)
            (test "manifest with array inject" test-build-bundled-manifest-with-array-inject)
            (test "manifest with match patterns" test-build-bundled-manifest-with-match-patterns)
            (test "manifest without match (manual-only)" test-build-bundled-manifest-without-match-manual-only)
            (test "invalid run-at defaults to document-idle" test-build-bundled-invalid-run-at-defaults-to-document-idle)
            (test "uses fallback name when no manifest" test-build-bundled-uses-fallback-name-when-no-manifest)
            (test "manifest with string match" test-build-bundled-manifest-with-string-match)
            (test "manifest with empty match array" test-build-bundled-manifest-with-empty-match-array)
            (test "always-enabled? propagated from catalog" test-build-bundled-always-enabled-propagated)
            (test "without always-enabled? omits the key" test-build-bundled-without-always-enabled)
            (test "special flags propagated from catalog" test-build-bundled-special-flags-propagated)
            (test "internal helpers library manifest builds expected built-in fields" test-build-bundled-internal-helpers-library)
            (test "without special flags omits the keys" test-build-bundled-without-special-flags)))
