(ns e2e.library-section-test
  "E2E tests for the Libraries popup section.

   Tests that:
   1. Built-in libraries (helpers.cljs, ui.cljs) appear in the Libraries section
   2. User library with :epupp/library? true appears in Libraries section
   3. Library with auto-run-match appears in matching section, NOT Libraries"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures.browser :refer [launch-browser get-extension-id]]
            [fixtures.pages :refer [create-popup-page create-panel-page]]
            [fixtures.wait :refer [wait-for-popup-ready wait-for-save-status]]
            [fixtures.events :refer [assert-no-errors!]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- code-with-manifest
  "Generate test code with epupp manifest metadata, including :epupp/library? support."
  [{:keys [name match description library? inject code]
    :or {code "(println \"Test script\")"}}]
  (let [inject-str (when inject
                     (str "[" (str/join " " (map #(str "\"" % "\"") inject)) "]"))
        meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     library? (conj ":epupp/library? true")
                     inject (conj (str ":epupp/inject " inject-str)))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

(defn- ^:async save-script-via-panel
  "Save a script via the panel UI. Returns after save confirmation."
  [context ext-id code]
  (let [panel (js-await (create-panel-page context ext-id))]
    (js-await (.fill (.locator panel "#code-area") code))
    (js-await (.click (.locator panel "button.btn-save")))
    (js-await (wait-for-save-status panel "Created"))
    (js-await (.close panel))))

(defn- ^:async expand-section
  "Expand a collapsible section if collapsed."
  [popup section-id]
  (let [section (.locator popup (str "[data-e2e-section=\"" section-id "\"]"))]
    (js-await (-> (expect section) (.toBeVisible #js {:timeout 1000})))
    (when (= "false" (js-await (.getAttribute section "data-e2e-expanded")))
      (js-await (.click (.locator section ".section-header"))))))

;; =============================================================================
;; Test: Built-in libraries appear in Libraries section
;; =============================================================================

(defn- ^:async test_builtin_libraries_in_libraries_section []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Libraries section starts collapsed - expand it
        (js-await (expand-section popup "libraries"))

        ;; Verify built-in libraries appear in the Libraries section
        (let [lib-section (.locator popup "[data-e2e-section=\"libraries\"]")]
          ;; helpers.cljs should be in the Libraries section
          (js-await (-> (expect (.locator lib-section ".script-item[data-script-name='epupp/internal/helpers.cljs']"))
                        (.toBeVisible #js {:timeout 2000})))
          ;; ui.cljs should be in the Libraries section
          (js-await (-> (expect (.locator lib-section ".script-item[data-script-name='epupp/ui.cljs']"))
                        (.toBeVisible #js {:timeout 2000}))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: User library with :epupp/library? appears in Libraries section
;; =============================================================================

(defn- ^:async test_user_library_in_libraries_section []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save a user script with :epupp/library? true and no auto-run-match
      (let [lib-code (code-with-manifest
                      {:name "test/user_lib.cljs"
                       :library? true
                       :code "(ns test.user-lib)\n\n(defn greet [who] (str \"Hi, \" who))"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      ;; Open popup and verify script appears in Libraries section
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (expand-section popup "libraries"))

        (let [lib-section (.locator popup "[data-e2e-section=\"libraries\"]")]
          ;; User library should appear in the Libraries section
          (js-await (-> (expect (.locator lib-section ".script-item[data-script-name='test/user_lib.cljs']"))
                        (.toBeVisible #js {:timeout 2000}))))

        ;; Should NOT appear in manual scripts section
        (js-await (expand-section popup "manual-scripts"))
        (let [manual-section (.locator popup "[data-e2e-section=\"manual-scripts\"]")]
          (js-await (-> (expect (.locator manual-section ".script-item[data-script-name='test/user_lib.cljs']"))
                        (.not.toBeVisible))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Library with auto-run-match appears in matching section, NOT Libraries
;; =============================================================================

(defn- ^:async test_library_with_match_not_in_libraries_section []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save a script with BOTH :epupp/library? true AND :epupp/auto-run-match
      (let [lib-code (code-with-manifest
                      {:name "test/hybrid_lib.cljs"
                       :library? true
                       :match "http://localhost:18080/*"
                       :code "(ns test.hybrid-lib)\n\n(defn hello [] \"hybrid\")"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      ;; Open popup and verify script placement
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        ;; Libraries section should NOT contain this script (it has a match pattern)
        (js-await (expand-section popup "libraries"))
        (let [lib-section (.locator popup "[data-e2e-section=\"libraries\"]")]
          (js-await (-> (expect (.locator lib-section ".script-item[data-script-name='test/hybrid_lib.cljs']"))
                        (.not.toBeVisible))))

        ;; Should appear in 'other' auto-run section (not matching current page)
        ;; or matching section depending on current URL - we check both
        (js-await (expand-section popup "other-scripts"))
        (js-await (expand-section popup "matching-scripts"))
        (let [other-section (.locator popup "[data-e2e-section=\"other-scripts\"]")
              matching-section (.locator popup "[data-e2e-section=\"matching-scripts\"]")
              in-other (.locator other-section ".script-item[data-script-name='test/hybrid_lib.cljs']")
              in-matching (.locator matching-section ".script-item[data-script-name='test/hybrid_lib.cljs']")
              other-count (js-await (.count in-other))
              matching-count (js-await (.count in-matching))]
          ;; Should be in exactly one of the auto-run sections
          (js-await (-> (expect (+ other-count matching-count)) (.toBe 1))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test Registration
;; =============================================================================

(.describe test "Library Section: popup categorization"
           (fn []
             (test "Library Section: built-in libraries appear in Libraries section"
                   test_builtin_libraries_in_libraries_section)

             (test "Library Section: user library with :epupp/library? appears in Libraries"
                   test_user_library_in_libraries_section)

             (test "Library Section: library with auto-run-match NOT in Libraries"
                   test_library_with_match_not_in_libraries_section)))
