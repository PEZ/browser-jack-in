(ns e2e.library-deps.loading-test
  "E2E tests for happy-path epupp:// library dependency loading.

   Tests:
   1. Consumer loads user library via epupp://
   2. Transitive chains mixing scittle:// and epupp://
   3. Built-in epupp.ui is consumable via epupp://"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures.browser :refer [launch-browser get-extension-id]]
            [fixtures.pages :refer [create-popup-page create-panel-page]]
            [fixtures.wait :refer [wait-for-save-status wait-for-popup-ready get-script-item
                                   wait-for-checkbox-state]]
            [fixtures.events :refer [wait-for-event get-test-events-via-message
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
;; Test: Consumer loads user library via epupp://
;; =============================================================================

(defn- ^:async poll-for-window-var
  "Poll page via expr-fn until a non-nil value is returned.
   Throws after timeout-ms milliseconds. Returns the result."
  [page expr-fn timeout-ms]
  (loop [start (.now js/Date)]
    (let [result (js-await (.evaluate page expr-fn))]
      (cond
        (some? result) result
        (> (- (.now js/Date) start) timeout-ms)
        (throw (js/Error. (str "Timeout after " timeout-ms "ms polling page")))
        :else
        (do
          (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
          (recur start))))))

(defn- ^:async test_consumer_loads_epupp_library []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [lib-code (code-with-manifest
                      {:name "test/lib.cljs"
                       :code "(ns test.lib)\n\n(defn greet [who]\n  (str \"Hello, \" who \"!\"))"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      (let [consumer-code (code-with-manifest
                           {:name "test/consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://test/lib.cljs"]
                            :code "(ns test.consumer\n  (:require [test.lib :as lib]))\n\n(set! (.-__EPUPP_CONSUMER_RESULT js/window) (lib/greet \"E2E\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/consumer.cljs"))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))]
          (js-await (-> (expect (.-event event)) (.toBe "EXECUTE_PLAN_COMPLETE")))

          (let [result (js-await (poll-for-window-var page (fn [] js/window.__EPUPP_CONSUMER_RESULT) 5000))]
            (js-await (-> (expect result) (.toBe "Hello, E2E!"))))

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
      (let [lib-code (code-with-manifest
                      {:name "test/render_lib.cljs"
                       :inject ["scittle://replicant.js"]
                       :code "(ns test.render-lib\n  (:require [replicant.dom :as r]))\n\n(defn render-msg [el msg]\n  (r/render el [:div {:id \"replicant-output\"} msg]))"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      (let [consumer-code (code-with-manifest
                           {:name "test/render_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://test/render_lib.cljs"]
                            :code "(ns test.render-consumer\n  (:require [test.render-lib :as lib]))\n\n(let [el (doto (js/document.createElement \"div\")\n           (set! -id \"replicant-container\")\n           (->> (.appendChild js/document.body)))]\n  (lib/render-msg el \"Transitive OK\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/render_consumer.cljs"))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 15000))]
          (js-await (-> (expect (.-event event)) (.toBe "EXECUTE_PLAN_COMPLETE")))

          (js-await (-> (expect (.locator page "#replicant-output"))
                        (.toContainText "Transitive OK" #js {:timeout 5000})))

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
;; Test Registration
;; =============================================================================

(.describe test "Library Dependencies: happy-path loading"
           (fn []
             (test "consumer loads user library via epupp:// inject"
                   test_consumer_loads_epupp_library)

             (test "transitive chain: consumer -> epupp:// library -> scittle:// vendor"
                   test_transitive_scittle_and_epupp_chain)

             (test "built-in epupp.ui is available to userscripts via epupp://"
                   test_epupp_ui_library_available_to_userscripts)))
