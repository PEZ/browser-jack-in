(ns e2e.library-deps.trigger-test
  "E2E tests for manual trigger mechanisms loading epupp:// libraries.

   Tests:
   1. Popup play button loads user library via epupp://
   2. Panel eval loads user library via epupp://"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures.constants :refer [http-port]]
            [fixtures.browser :refer [launch-browser get-extension-id]]
            [fixtures.pages :refer [create-popup-page create-panel-page create-panel-page-for-tab]]
            [fixtures.messaging :refer [find-tab-id]]
            [fixtures.wait :refer [wait-for-save-status wait-for-popup-ready get-script-item]]
            [fixtures.events :refer [wait-for-event assert-no-errors! clear-test-events!]]))

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

;; =============================================================================
;; Test: Popup play button loads user library via epupp://
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

          (let [tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
            (js-await (.evaluate popup
                                 (fn [target-tab-id]
                                   (js/Promise.
                                    (fn [resolve]
                                      (js/chrome.tabs.update target-tab-id #js {:active true}
                                                             (fn [] (resolve true))))))
                                 tab-id)))

          (let [item (get-script-item popup "test/play_consumer.cljs")
                run-btn (.locator item "button.script-run")]
            (js-await (-> (expect run-btn) (.toBeVisible #js {:timeout 500})))
            (js-await (.click run-btn)))

          (js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))

          (let [result (js-await (poll-for-window-var test-page (fn [] js/window.__EPUPP_PLAY_LIB_RESULT) 5000))]
            (js-await (-> (expect result) (.toBe "Hello from play-lib, E2E!"))))

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

              (let [result (js-await (poll-for-window-var
                                      test-page
                                      (fn []
                                        (try
                                          (js/scittle.core.eval_string "(test.panel-lib/greet \"test\")")
                                          (catch :default _e nil)))
                                      5000))]
                (js-await (-> (expect result) (.toBe "Hello from panel-lib, test!"))))

              (js-await (assert-no-errors! panel))
              (js-await (.close panel)))))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test Registration
;; =============================================================================

(.describe test "Library Dependencies: manual trigger mechanisms"
           (fn []
             (test "popup play button: consumer loads user library via epupp:// inject"
                   test_popup_play_consumer_loads_user_library)

             (test "panel eval: consumer loads user library via epupp:// inject"
                   test_panel_eval_consumer_loads_user_library)))
