(ns e2e.library-deps-test
  "E2E tests for epupp:// library dependency resolution.

   Tests that:
   1. Document-idle consumer can load user library via epupp://
   2. Transitive chains mixing scittle:// and epupp:// work
    3. Built-in epupp.ui is consumable via epupp:// in userscripts
    4. Missing library produces resolution error events
    5. Panel recognizes epupp:// URLs in manifest inject
    6. Missing library shows error indicator on popup script row
    7. Adding missing library and reloading clears the error indicator
    8. Popup play button loads user library via epupp://
    9. Panel eval loads user library via epupp://"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page create-panel-page-for-tab
                              wait-for-event
                              get-test-events-via-message wait-for-save-status
                              wait-for-popup-ready get-script-item
                              wait-for-checkbox-state find-tab-id
                              http-port
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
  "Enable a script via popup checkbox. Uses data-script-name attribute for reliable selection."
  [context ext-id script-name]
  (let [popup (js-await (create-popup-page context ext-id))]
    (js-await (wait-for-popup-ready popup))
    (let [script-item (get-script-item popup script-name)
          checkbox (.locator script-item "input[type='checkbox']")]
      (js-await (.click checkbox))
      (js-await (wait-for-checkbox-state checkbox true)))
    (js-await (.close popup))))

;; =============================================================================
;; Test: Consumer loads user library via epupp://
;; =============================================================================

(defn- ^:async test_consumer_loads_epupp_library []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save a library script (no auto-run match - loaded as dependency only)
      (let [lib-code (code-with-manifest
                      {:name "test/lib.cljs"
                       :code "(ns test.lib)\n\n(defn greet [who]\n  (str \"Hello, \" who \"!\"))"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      ;; Save a consumer script that depends on the library
      (let [consumer-code (code-with-manifest
                           {:name "test/consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://test/lib.cljs"]
                            :code "(ns test.consumer\n  (:require [test.lib :as lib]))\n\n(set! (.-__EPUPP_CONSUMER_RESULT js/window) (lib/greet \"E2E\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; Enable the consumer script (library doesn't need enabling - loaded as dep)
      (js-await (enable-script-via-popup context ext-id "test/consumer.cljs"))

      ;; Clear test events before navigation
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; Navigate to matching page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for plan execution to complete
        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))]
          (js-await (-> (expect (.-event event)) (.toBe "EXECUTE_PLAN_COMPLETE")))

          ;; Poll for the consumer result (Scittle eval timing)
          (let [start (.now js/Date)]
            (loop []
              (let [result (js-await (.evaluate page (fn [] js/window.__EPUPP_CONSUMER_RESULT)))]
                (if (some? result)
                  ;; Found the result - verify it
                  (js-await (-> (expect result) (.toBe "Hello, E2E!")))
                  ;; Not yet - poll or timeout
                  (do
                    (when (> (- (.now js/Date) start) 5000)
                      (throw (js/Error. "Timeout waiting for __EPUPP_CONSUMER_RESULT")))
                    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                    (recur))))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Transitive chain mixing scittle:// and epupp://
;; =============================================================================

(defn- ^:async test_transitive_scittle_and_epupp_chain []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Library script that uses Scittle's replicant
      (let [lib-code (code-with-manifest
                      {:name "test/render_lib.cljs"
                       :inject ["scittle://replicant.js"]
                       :code "(ns test.render-lib\n  (:require [replicant.dom :as r]))\n\n(defn render-msg [el msg]\n  (r/render el [:div {:id \"replicant-output\"} msg]))"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      ;; Consumer script that uses the library (transitive: consumer -> lib -> replicant)
      (let [consumer-code (code-with-manifest
                           {:name "test/render_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://test/render_lib.cljs"]
                            :code "(ns test.render-consumer\n  (:require [test.render-lib :as lib]))\n\n(let [el (doto (js/document.createElement \"div\")\n           (set! -id \"replicant-container\")\n           (->> (.appendChild js/document.body)))]\n  (lib/render-msg el \"Transitive OK\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; Enable the consumer script
      (js-await (enable-script-via-popup context ext-id "test/render_consumer.cljs"))

      ;; Clear test events
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; Navigate and verify
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for plan execution
        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 15000))]
          (js-await (-> (expect (.-event event)) (.toBe "EXECUTE_PLAN_COMPLETE")))

          ;; Verify replicant rendered content via the library
          (js-await (-> (expect (.locator page "#replicant-output"))
                        (.toContainText "Transitive OK" #js {:timeout 5000})))

          ;; Check that NAMESPACES_VERIFIED event fired (vendor replicant loaded)
          (let [events (js-await (get-test-events-via-message popup))
                ns-event (first (filter #(= (.-event %) "NAMESPACES_VERIFIED") events))]
            (js-await (-> (expect ns-event) (.toBeTruthy))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Built-in epupp.ui is available via epupp://
;; =============================================================================

(defn- ^:async test_epupp_ui_library_available_to_userscripts []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name "test/ui_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["scittle://replicant.js" "epupp://epupp/ui.cljs"]
                            :code "(ns test.ui-consumer\n  (:require [replicant.dom :as r]\n            [epupp.ui :as ui]))\n\n(let [container (or (js/document.getElementById \"epupp-ui-consumer-root\")\n                    (doto (js/document.createElement \"div\")\n                      (set! -id \"epupp-ui-consumer-root\")\n                      (->> (.appendChild js/document.body))))]\n  (r/render container\n            [:div (ui/epupp-header :size 28)]))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/ui_consumer.cljs"))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 15000))
              root (.locator page "#epupp-ui-consumer-root")]
          (js-await (-> (expect (.-event event)) (.toBe "EXECUTE_PLAN_COMPLETE")))
          (js-await (-> (expect root)
                        (.toBeVisible #js {:timeout 5000})))
          (js-await (-> (expect root)
                        (.toContainText "Epupp")))
          (js-await (-> (expect root)
                        (.toContainText "Live Tamper your Web")))
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Missing library produces resolution error
;; =============================================================================

(defn- ^:async test_missing_library_produces_error []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save consumer that references a non-existent library
      (let [consumer-code (code-with-manifest
                           {:name "test/bad_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://nonexistent_lib.cljs"]
                            :code "(ns test.bad-consumer)\n\n(set! (.-__BAD_CONSUMER_RAN js/window) true)"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; Enable the consumer
      (js-await (enable-script-via-popup context ext-id "test/bad_consumer.cljs"))

      ;; Clear test events
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; Navigate to matching page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Check for RESOLUTION_ERROR event
        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "RESOLUTION_ERROR" 10000))]
          ;; Should have error about missing library
          (js-await (-> (expect (.-event event)) (.toBe "RESOLUTION_ERROR")))
          (let [data (.-data event)]
            (js-await (-> (expect (.-message data))
                          (.toContain "not found"))))

          (let [consumer-ran (js-await (.evaluate page (fn [] (js/Boolean (.-__BAD_CONSUMER_RAN js/window)))))]
            (js-await (-> (expect consumer-ran) (.toBe false))))

          ;; No uncaught errors (resolution errors are handled gracefully)
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
        ;; Fill in code
        (js-await (.fill (.locator panel "#code-area") code))

        ;; The manifest Requires row should show 2 libraries
        (js-await (-> (expect (.locator panel "[data-e2e-property='requires']"))
                      (.toContainText "2 libraries" #js {:timeout 3000})))

        ;; The missing-epupp warning should appear (some_lib.cljs doesn't exist)
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
      ;; Save consumer that references a non-existent library
      (let [consumer-code (code-with-manifest
                           {:name "test/error_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://missing_lib.cljs"]
                            :code "(ns test.error-consumer)\n\n(println \"Should not run\")"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; Enable the consumer
      (js-await (enable-script-via-popup context ext-id "test/error_consumer.cljs"))

      ;; Clear test events
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; Navigate to matching page to trigger injection
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Open popup and wait for error to be processed
        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (find-tab-id popup "http://localhost:18080/*"))]
          ;; Configure popup to see the test page's tab
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';")))
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_tab_id = " tab-id ";")))
          (js-await (.reload popup))
          (js-await (wait-for-popup-ready popup))
          (js-await (wait-for-event popup "RESOLUTION_ERROR" 10000))

          ;; Verify the error indicator is visible on the script row
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
      ;; === PHASE 1: Set up consumer with missing dep ===
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

      ;; === PHASE 2: Navigate to trigger error ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Verify error indicator appears
        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (find-tab-id popup "http://localhost:18080/*"))]
          ;; Configure popup to see the test page's tab
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

        ;; === PHASE 3: Save the missing library ===
        (let [lib-code (code-with-manifest
                        {:name "test/fixable_lib.cljs"
                         :code "(ns test.fixable-lib)\n\n(defn hello [] \"fixed\")"})]
          (js-await (save-script-via-panel context ext-id lib-code)))

        ;; Clear test events before second navigation
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (clear-test-events! popup))
          (js-await (.close popup)))

        ;; === PHASE 4: Navigate again - error should clear ===
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for successful execution (no error this time)
        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (find-tab-id popup "http://localhost:18080/*"))]
          ;; Configure popup to see the test page's tab
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_url = 'http://localhost:18080/basic.html';")))
          (js-await (.addInitScript popup (str "window.__scittle_tamper_test_tab_id = " tab-id ";")))
          (js-await (.reload popup))
          (js-await (wait-for-popup-ready popup))
          (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))

          ;; Verify the error indicator is gone
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
;; Test: Popup play button loads user library via epupp://
;; =============================================================================

(defn- ^:async test_popup_play_consumer_loads_user_library []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save a library script
      (let [lib-code (code-with-manifest
                      {:name "test/play_lib.cljs"
                       :library? true
                       :code "(ns test.play-lib)\n\n(defn greet [who]\n  (str \"Hello from play-lib, \" who \"!\"))"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      ;; Save consumer (with match pattern so play button shows, but NOT enabled)
      (let [consumer-code (code-with-manifest
                           {:name "test/play_consumer.cljs"
                            :match (str "http://localhost:" http-port "/*")
                            :inject ["epupp://test/play_lib.cljs"]
                            :code "(ns test.play-consumer\n  (:require [test.play-lib :as lib]))\n\n(set! (.-__EPUPP_PLAY_LIB_RESULT js/window) (lib/greet \"E2E\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; Open test page
      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html") #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        ;; Open popup, activate test tab, click play button
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (clear-test-events! popup))

          ;; Activate test tab so popup targets it
          (let [tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
            (js-await (.evaluate popup
                                 (fn [target-tab-id]
                                   (js/Promise.
                                    (fn [resolve]
                                      (js/chrome.tabs.update target-tab-id #js {:active true}
                                                             (fn [] (resolve true))))))
                                 tab-id)))

          ;; Click play button on consumer script
          (let [item (get-script-item popup "test/play_consumer.cljs")
                run-btn (.locator item "button.script-run")]
            (js-await (-> (expect run-btn) (.toBeVisible #js {:timeout 500})))
            (js-await (.click run-btn)))

          ;; Wait for injection
          (js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))

          ;; Poll for consumer result on test page
          (let [start (.now js/Date)]
            (loop []
              (let [result (js-await (.evaluate test-page (fn [] js/window.__EPUPP_PLAY_LIB_RESULT)))]
                (if (some? result)
                  (js-await (-> (expect result) (.toBe "Hello from play-lib, E2E!")))
                  (do
                    (when (> (- (.now js/Date) start) 5000)
                      (throw (js/Error. "Timeout waiting for __EPUPP_PLAY_LIB_RESULT")))
                    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                    (recur))))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Panel eval loads user library via epupp://
;; =============================================================================

(defn- ^:async test_panel_eval_consumer_loads_user_library []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save library script first
      (let [lib-code (code-with-manifest
                      {:name "test/panel_lib.cljs"
                       :library? true
                       :code "(ns test.panel-lib)\n\n(defn greet [who]\n  (str \"Hello from panel-lib, \" who \"!\"))"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      ;; Open test page
      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html") #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        ;; Find tab ID
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))
          (let [tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
            (js-await (.close popup))

            ;; Open panel for real tab
            (let [panel (js-await (create-panel-page-for-tab context ext-id tab-id))
                  consumer-code (str "{:epupp/script-name \"test/panel_consumer.cljs\"\n"
                                     " :epupp/inject [\"epupp://test/panel_lib.cljs\"]}\n\n"
                                     "(ns test.panel-consumer\n"
                                     "  (:require [test.panel-lib :as lib]))\n\n"
                                     "(set! (.-__EPUPP_PANEL_LIB_RESULT js/window) (lib/greet \"Panel\"))")]
              (js-await (.fill (.locator panel "#code-area") consumer-code))
              (js-await (.click (.locator panel "button.btn-eval")))

              ;; Poll real page for library namespace availability
              (let [start (.now js/Date)]
                (loop []
                  (let [result (js-await (.evaluate test-page
                                                    (fn []
                                                      (try
                                                        (js/scittle.core.eval_string "(test.panel-lib/greet \"test\")")
                                                        (catch :default _e nil)))))]
                    (if (some? result)
                      (js-await (-> (expect result) (.toBe "Hello from panel-lib, test!")))
                      (do
                        (when (> (- (.now js/Date) start) 5000)
                          (throw (js/Error. "Timeout: test.panel-lib namespace not available on page")))
                        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
                        (recur))))))

              (js-await (assert-no-errors! panel))
              (js-await (.close panel)))))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Popup play button loads user library via epupp://
;; =============================================================================

(defn- ^:async test_popup_play_consumer_loads_user_library []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save a library script
      (let [lib-code (code-with-manifest
                      {:name "test/play_lib.cljs"
                       :library? true
                       :code "(ns test.play-lib)\n\n(defn greet [who]\n  (str \"Hello from play-lib, \" who \"!\"))"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      ;; Save consumer (with match pattern so play button shows, but NOT enabled)
      (let [consumer-code (code-with-manifest
                           {:name "test/play_consumer.cljs"
                            :match (str "http://localhost:" http-port "/*")
                            :inject ["epupp://test/play_lib.cljs"]
                            :code "(ns test.play-consumer\n  (:require [test.play-lib :as lib]))\n\n(set! (.-__EPUPP_PLAY_LIB_RESULT js/window) (lib/greet \"E2E\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; Open test page
      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html") #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        ;; Open popup, activate test tab, click play button
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (clear-test-events! popup))

          ;; Activate test tab so popup targets it
          (let [tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
            (js-await (.evaluate popup
                                 (fn [target-tab-id]
                                   (js/Promise.
                                    (fn [resolve]
                                      (js/chrome.tabs.update target-tab-id #js {:active true}
                                                             (fn [] (resolve true))))))
                                 tab-id)))

          ;; Click play button on consumer script
          (let [item (get-script-item popup "test/play_consumer.cljs")
                run-btn (.locator item "button.script-run")]
            (js-await (-> (expect run-btn) (.toBeVisible #js {:timeout 500})))
            (js-await (.click run-btn)))

          ;; Wait for injection
          (js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))

          ;; Poll for consumer result on test page
          (let [start (.now js/Date)]
            (loop []
              (let [result (js-await (.evaluate test-page (fn [] js/window.__EPUPP_PLAY_LIB_RESULT)))]
                (if (some? result)
                  (js-await (-> (expect result) (.toBe "Hello from play-lib, E2E!")))
                  (do
                    (when (> (- (.now js/Date) start) 5000)
                      (throw (js/Error. "Timeout waiting for __EPUPP_PLAY_LIB_RESULT")))
                    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                    (recur))))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Panel eval loads user library via epupp://
;; =============================================================================

(defn- ^:async test_panel_eval_consumer_loads_user_library []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save library script first
      (let [lib-code (code-with-manifest
                      {:name "test/panel_lib.cljs"
                       :library? true
                       :code "(ns test.panel-lib)\n\n(defn greet [who]\n  (str \"Hello from panel-lib, \" who \"!\"))"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      ;; Open test page
      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html") #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        ;; Find tab ID
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))
          (let [tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
            (js-await (.close popup))

            ;; Open panel for real tab
            (let [panel (js-await (create-panel-page-for-tab context ext-id tab-id))
                  consumer-code (str "{:epupp/script-name \"test/panel_consumer.cljs\"\n"
                                     " :epupp/inject [\"epupp://test/panel_lib.cljs\"]}\n\n"
                                     "(ns test.panel-consumer\n"
                                     "  (:require [test.panel-lib :as lib]))\n\n"
                                     "(set! (.-__EPUPP_PANEL_LIB_RESULT js/window) (lib/greet \"Panel\"))")]
              (js-await (.fill (.locator panel "#code-area") consumer-code))
              (js-await (.click (.locator panel "button.btn-eval")))

              ;; Poll real page for library namespace availability
              (let [start (.now js/Date)]
                (loop []
                  (let [result (js-await (.evaluate test-page
                                                    (fn []
                                                      (try
                                                        (js/scittle.core.eval_string "(test.panel-lib/greet \"test\")")
                                                        (catch :default _e nil)))))]
                    (if (some? result)
                      (js-await (-> (expect result) (.toBe "Hello from panel-lib, test!")))
                      (do
                        (when (> (- (.now js/Date) start) 5000)
                          (throw (js/Error. "Timeout: test.panel-lib namespace not available on page")))
                        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
                        (recur))))))

              (js-await (assert-no-errors! panel))
              (js-await (.close panel)))))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test Registration
;; =============================================================================

(.describe test "Library Dependencies: epupp:// resolution"
           (fn []
             (test "consumer loads user library via epupp:// inject"
                   test_consumer_loads_epupp_library)

             (test "transitive chain: consumer -> epupp:// library -> scittle:// vendor"
                   test_transitive_scittle_and_epupp_chain)

             (test "built-in epupp.ui is available to userscripts via epupp://"
               test_epupp_ui_library_available_to_userscripts)

             (test "missing epupp:// library produces resolution error"
                   test_missing_library_produces_error)

             (test "panel shows epupp:// URLs in manifest and warns on missing"
                   test_panel_shows_epupp_inject_in_manifest)

             (test "missing library marks the failing script row in popup"
                   test_missing_dep_shows_error_indicator_in_popup)

             (test "adding the library and reloading clears the error mark"
                   test_adding_library_clears_error_indicator)

             (test "popup play button: consumer loads user library via epupp:// inject"
                   test_popup_play_consumer_loads_user_library)

             (test "panel eval: consumer loads user library via epupp:// inject"
                   test_panel_eval_consumer_loads_user_library)))
