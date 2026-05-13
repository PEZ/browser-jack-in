(ns e2e.ext-dep.auto-run-test
  "E2E tests for external dependency injection via auto-run.

   Tests:
   1. Consumer loads ext-dep from pre-populated cache
   2. Missing ext-dep cache produces resolution error
   3. Consumer loads ext-dep from gist raw URL cache (auto-run)"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              wait-for-event clear-test-events!
                              assert-no-errors! send-runtime-message http-port]]
            [e2e.ext-dep-helpers :refer [ext-dep-url ext-dep-lib-code
                                         gist-raw-url pez-test-lib-code
                                         code-with-manifest save-script-via-panel
                                         enable-script-via-popup set-ext-dep-cache!
                                         make-ext-dep-cache poll-for-window-property!]]))

(defn- ^:async test_consumer_loads_ext_dep_from_cache []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name "test/ext_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject [ext-dep-url]
                            :code "(ns test.ext-consumer\n  (:require [ext.lib :as lib]))\n\n(set! (.-__EPUPP_EXT_DEP_RESULT js/window) (lib/greet \"E2E\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/ext_consumer.cljs"))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (set-ext-dep-cache! popup (make-ext-dep-cache ext-dep-url ext-dep-lib-code)))
        (js-await (.close popup)))

      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))]
          (js-await (-> (expect (.-event event)) (.toBe "EXECUTE_PLAN_COMPLETE")))

          (let [result (try
                         (js-await (poll-for-window-property! page "__EPUPP_EXT_DEP_RESULT" 5000))
                         (catch :default _e
                           (let [response (js-await (send-runtime-message popup "e2e/get-test-events" nil))
                                 events (when response (.-events response))
                                 event-summary (when (and events (pos? (.-length events)))
                                                 (.map events (fn [e] (str (.-event e) " " (js/JSON.stringify (.-data e))))))]
                             (throw (js/Error. (str "Timeout waiting for __EPUPP_EXT_DEP_RESULT. Events: "
                                                    (if event-summary (.join event-summary " | ") "NONE")))))))]
            (js-await (-> (expect result) (.toBe "Hello from ext-dep, E2E!"))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_missing_ext_dep_cache_produces_error []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [uncached-url "https://raw.githubusercontent.com/test-owner/test-repo/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/missing.cljs"
            consumer-code (code-with-manifest
                           {:name "test/ext_missing_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject [uncached-url]
                            :code "(ns test.ext-missing-consumer)\n\n(set! (.-__EXT_DEP_MISSING_RAN js/window) true)"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/ext_missing_consumer.cljs"))

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
                          (.toContain "not in cache"))))

          (let [consumer-ran (js-await (.evaluate page (fn [] (js/Boolean (.-__EXT_DEP_MISSING_RAN js/window)))))]
            (js-await (-> (expect consumer-ran) (.toBe false))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_consumer_loads_ext_dep_from_gist_cache []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name "test/gist_consumer.cljs"
                            :match (str "http://localhost:" http-port "/*")
                            :inject [gist-raw-url]
                            :code "(ns test.gist-consumer\n  (:require [pez.test-lib :as lib]))\n\n(set! (.-__EPUPP_GIST_RESULT js/window) (lib/greeting \"Gist\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/gist_consumer.cljs"))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (set-ext-dep-cache! popup (make-ext-dep-cache gist-raw-url pez-test-lib-code)))
        (js-await (.close popup)))

      (let [page (js-await (.newPage context))]
        (js-await (.goto page (str "http://localhost:" http-port "/basic.html") #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))]
          (js-await (-> (expect (.-event event)) (.toBe "EXECUTE_PLAN_COMPLETE")))

          (let [result (js-await (poll-for-window-property! page "__EPUPP_GIST_RESULT" 5000))]
            (js-await (-> (expect result) (.toBe "Hello from pez.test-lib, Gist!"))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "External Dependencies: auto-run injection from cache"
           (fn []
             (test "consumer loads ext-dep library from pre-populated cache"
                   test_consumer_loads_ext_dep_from_cache)

             (test "missing ext-dep cache produces resolution error"
                   test_missing_ext_dep_cache_produces_error)

             (test "consumer loads ext-dep from gist raw URL cache (auto-run)"
                   test_consumer_loads_ext_dep_from_gist_cache)))
