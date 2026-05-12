(ns e2e.library-deps.error-test
  "E2E tests for epupp:// library dependency error handling.

   Tests:
   1. Missing library produces resolution error
   2. Panel recognizes epupp:// in manifest and warns on missing
   3. Missing library shows error indicator on popup script row
   4. Adding missing library and reloading clears the error"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page
                              wait-for-event
                              wait-for-save-status
                              wait-for-popup-ready get-script-item
                              wait-for-checkbox-state find-tab-id
                              assert-no-errors! clear-test-events!]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- code-with-manifest
  "Generate test code with epupp manifest metadata."
  [{:keys [name match description run-at inject library? code]
    :or {code "(println \"Test script\")"}}]
  (let [inject-str (when inject
                     (str "[" (str/join " " (map #(str "\"" % "\"") inject)) "]"))
        meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\""))
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

(defn- ^:async enable-script-via-popup
  "Enable a script via popup checkbox."
  [context ext-id script-name]
  (let [popup (js-await (create-popup-page context ext-id))]
    (js-await (wait-for-popup-ready popup))
    (let [script-item (get-script-item popup script-name)
          checkbox (.locator script-item "input[type='checkbox']")]
      (js-await (.click checkbox))
      (js-await (wait-for-checkbox-state checkbox true)))
    (js-await (.close popup))))

;; =============================================================================
;; Test: Missing library produces resolution error
;; =============================================================================

(defn- ^:async test_missing_library_produces_error []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name "test/bad_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://nonexistent_lib.cljs"]
                            :code "(ns test.bad-consumer)\n\n(set! (.-__BAD_CONSUMER_RAN js/window) true)"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/bad_consumer.cljs"))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "RESOLUTION_ERROR" 10000))]
          (js-await (-> (expect (.-event event)) (.toBe "RESOLUTION_ERROR")))
          (let [data (.-data event)]
            (js-await (-> (expect (.-message data))
                          (.toContain "not found"))))

          (let [consumer-ran (js-await (.evaluate page (fn [] (js/Boolean (.-__BAD_CONSUMER_RAN js/window)))))]
            (js-await (-> (expect consumer-ran) (.toBe false))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Panel recognizes epupp:// in manifest inject
;; =============================================================================

(defn- ^:async test_panel_shows_epupp_inject_in_manifest []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            code (code-with-manifest
                  {:name "test/panel_epupp.cljs"
                   :match "http://localhost:18080/*"
                   :inject ["scittle://replicant.js" "epupp://some_lib.cljs"]
                   :code "(ns test.panel-epupp)"})]
        (js-await (.fill (.locator panel "#code-area") code))

        (js-await (-> (expect (.locator panel "[data-e2e-property='requires']"))
                      (.toContainText "2 libraries" #js {:timeout 3000})))

        (js-await (-> (expect (.locator panel ".manifest-warning:has-text(\"not found\")"))
                      (.toBeVisible #js {:timeout 3000})))

        (js-await (.close panel)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Missing library marks the failing script row in popup
;; =============================================================================

(defn- ^:async test_missing_dep_shows_error_indicator_in_popup []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name "test/error_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://missing_lib.cljs"]
                            :code "(ns test.error-consumer)\n\n(println \"Should not run\")"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/error_consumer.cljs"))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (find-tab-id popup "http://localhost:18080/*"))]
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';")))
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_tab_id = " tab-id ";")))
          (js-await (.reload popup))
          (js-await (wait-for-popup-ready popup))
          (js-await (wait-for-event popup "RESOLUTION_ERROR" 10000))

          (let [script-row (get-script-item popup "test/error_consumer.cljs")
                error-indicator (.locator script-row "[data-e2e='script-error']")]
            (js-await (-> (expect error-indicator)
                          (.toBeVisible #js {:timeout 2000}))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Adding the missing library and reloading clears the error
;; =============================================================================

(defn- ^:async test_adding_library_clears_error_indicator []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; PHASE 1: Set up consumer with missing dep
      (let [consumer-code (code-with-manifest
                           {:name "test/fixable_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://test/fixable_lib.cljs"]
                            :code "(ns test.fixable-consumer)\n\n(println \"Consumer loaded\")"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/fixable_consumer.cljs"))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; PHASE 2: Navigate to trigger error
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Verify error indicator appears
        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (find-tab-id popup "http://localhost:18080/*"))]
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';")))
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_tab_id = " tab-id ";")))
          (js-await (.reload popup))
          (js-await (wait-for-popup-ready popup))
          (js-await (wait-for-event popup "RESOLUTION_ERROR" 10000))

          (let [script-row (get-script-item popup "test/fixable_consumer.cljs")
                error-indicator (.locator script-row "[data-e2e='script-error']")]
            (js-await (-> (expect error-indicator)
                          (.toBeVisible #js {:timeout 2000}))))
          (js-await (.close popup)))

        ;; PHASE 3: Save the missing library
        (let [lib-code (code-with-manifest
                        {:name "test/fixable_lib.cljs"
                         :code "(ns test.fixable-lib)\n\n(defn hello [] \"fixed\")"})]
          (js-await (save-script-via-panel context ext-id lib-code)))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (clear-test-events! popup))
          (js-await (.close popup)))

        ;; PHASE 4: Navigate again - error should clear
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (find-tab-id popup "http://localhost:18080/*"))]
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';")))
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_tab_id = " tab-id ";")))
          (js-await (.reload popup))
          (js-await (wait-for-popup-ready popup))
          (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))

          (let [script-row (get-script-item popup "test/fixable_consumer.cljs")
                error-indicator (.locator script-row "[data-e2e='script-error']")]
            (js-await (-> (expect error-indicator)
                          (.not.toBeVisible))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test Registration
;; =============================================================================

(.describe test "Library Dependencies: error handling"
           (fn []
             (test "missing epupp:// library produces resolution error"
                   test_missing_library_produces_error)

             (test "panel shows epupp:// URLs in manifest and warns on missing"
                   test_panel_shows_epupp_inject_in_manifest)

             (test "missing library marks the failing script row in popup"
                   test_missing_dep_shows_error_indicator_in_popup)

             (test "adding the library and reloading clears the error mark"
                   test_adding_library_clears_error_indicator)))
