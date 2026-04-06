(ns e2e.ext-dep-test
  "E2E tests for external dependency (ext-dep) injection via HTTPS URLs.

   Tests that:
   1. Consumer loads ext-dep from pre-populated cache
   2. Missing ext-dep cache produces resolution error"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page wait-for-event
                              wait-for-save-status
                              wait-for-popup-ready get-script-item
                              wait-for-checkbox-state send-runtime-message
                              assert-no-errors! clear-test-events!]]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ext-dep-url
  "https://raw.githubusercontent.com/test-owner/test-repo/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/lib.cljs")

(def ext-dep-lib-code
  "{:epupp/script-name \"ext/lib.cljs\"\n :epupp/library? true}\n\n(ns ext.lib)\n\n(defn greet [who]\n  (str \"Hello from ext-dep, \" who \"!\"))")

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- code-with-manifest
  "Generate test code with epupp manifest metadata."
  [{:keys [name match description run-at inject code]
    :or {code "(println \"Test script\")"}}]
  (let [inject-str (when inject
                     (str "[" (str/join " " (map #(str "\"" % "\"") inject)) "]"))
        meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\""))
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

(defn- ^:async set-ext-dep-cache!
  "Pre-populate the ext-dep cache in chrome.storage via e2e/set-storage message."
  [popup cache-obj]
  (js-await (send-runtime-message popup "e2e/set-storage"
                                  #js {:key "extDepCache" :value cache-obj})))

;; =============================================================================
;; Test: Consumer loads ext-dep from pre-populated cache
;; =============================================================================

(defn- ^:async test_consumer_loads_ext_dep_from_cache []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save a consumer script that injects an ext-dep library
      (let [consumer-code (code-with-manifest
                           {:name "test/ext_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject [ext-dep-url]
                            :code "(ns test.ext-consumer\n  (:require [ext.lib :as lib]))\n\n(set! (.-__EPUPP_EXT_DEP_RESULT js/window) (lib/greet \"E2E\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; Enable the consumer script
      (js-await (enable-script-via-popup context ext-id "test/ext_consumer.cljs"))

      ;; Clear test events before navigation
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))

        ;; Set the ext-dep cache AFTER all save/enable operations
        ;; (those trigger persist! which writes ALL keys including extDepCache:{} to storage)
        (let [cache-obj (js-obj ext-dep-url
                                #js {"cache/code" ext-dep-lib-code
                                     "cache/url" ext-dep-url
                                     "cache/inject" #js []
                                     "cache/fetched-at" 1700000000000
                                     "cache/schema-version" 1})]
          (js-await (set-ext-dep-cache! popup cache-obj)))
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
              (let [result (js-await (.evaluate page (fn [] js/window.__EPUPP_EXT_DEP_RESULT)))]
                (if (some? result)
                  (js-await (-> (expect result) (.toBe "Hello from ext-dep, E2E!")))
                  (do
                    (when (> (- (.now js/Date) start) 5000)
                      ;; Dump diagnostic events before failing
                      (let [response (js-await (send-runtime-message popup "e2e/get-test-events" nil))
                            events (when response (.-events response))
                            event-summary (when (and events (pos? (.-length events)))
                                            (.map events (fn [e] (str (.-event e) " " (js/JSON.stringify (.-data e))))))]
                        (throw (js/Error. (str "Timeout waiting for __EPUPP_EXT_DEP_RESULT. Events: "
                                               (if event-summary (.join event-summary " | ") "NONE"))))))
                    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
                    (recur))))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Missing ext-dep cache produces resolution error
;; =============================================================================

(defn- ^:async test_missing_ext_dep_cache_produces_error []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Save consumer that references an ext-dep URL with no cache entry
      (let [uncached-url "https://raw.githubusercontent.com/test-owner/test-repo/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/missing.cljs"
            consumer-code (code-with-manifest
                           {:name "test/ext_missing_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject [uncached-url]
                            :code "(ns test.ext-missing-consumer)\n\n(set! (.-__EXT_DEP_MISSING_RAN js/window) true)"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; Enable the consumer (no cache populated intentionally)
      (js-await (enable-script-via-popup context ext-id "test/ext_missing_consumer.cljs"))

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
          (js-await (-> (expect (.-event event)) (.toBe "RESOLUTION_ERROR")))
          (let [data (.-data event)]
            (js-await (-> (expect (.-message data))
                          (.toContain "not in cache"))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test Registration
;; =============================================================================

(.describe test "External Dependencies: ext-dep injection from cache"
           (fn []
             (test "consumer loads ext-dep library from pre-populated cache"
                   test_consumer_loads_ext_dep_from_cache)

             (test "missing ext-dep cache produces resolution error"
                   test_missing_ext_dep_cache_produces_error)))
