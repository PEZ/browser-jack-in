(ns e2e.script-document-start-test
  "E2E tests for document-start script timing.

   Coverage:
   - document-start scripts run before page scripts
   - document-start scripts are injected via registration system"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [builtin-script-count launch-browser get-extension-id create-panel-page
                              create-popup-page wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-script-count poll-until
                              assert-no-errors!]]
            [panel-save-helpers :as panel-save-helpers]))

;; =============================================================================
;; Document-start Timing
;; =============================================================================

(defn- setup-bg-console-capture
  "Set up background worker console capture. Returns bg-logs atom."
  [context]
  (let [bg-logs (atom [])
        bg-worker (aget (.serviceWorkers context) 0)]
    (.on bg-worker "console"
         (fn [msg] (swap! bg-logs conj (.text msg))))
    bg-logs))

(defn- ^:async enable-script!
  "Enable a script via popup checkbox. Verifies it starts disabled."
  [context ext-id script-filename]
  (let [popup (js-await (create-popup-page context ext-id))]
    (js-await (wait-for-popup-ready popup))
    (js-await (wait-for-script-count popup (+ builtin-script-count 1)))
    (let [script-item (.locator popup (str ".script-item:has-text(\"" script-filename "\")"))
          checkbox (.locator script-item "input[type='checkbox']")]
      (js-await (-> (expect checkbox) (.not.toBeChecked)))
      (js-await (.click checkbox))
      (js-await (-> (expect checkbox) (.toBeChecked #js {:timeout 1000}))))
    (js-await (.close popup))))

(defn- ^:async assert-registration!
  "Verify content script registration succeeded in background logs."
  [bg-logs]
  (try
    (js-await (poll-until
               #(str/includes? (str/join "\n" @bg-logs)
                               "Content scripts registered successfully")
               500 30))
    (catch :default _e nil))
  (let [bg-text (str/join "\n" @bg-logs)]
    (when (str/includes? bg-text "Sync failed")
      (throw (js/Error. (str "Registration failed! Logs:\n" bg-text))))
    (js-await (-> (expect bg-text)
                  (.toMatch "Content scripts registered successfully")))))

(defn- ^:async verify-script-ran-on-page!
  "Navigate to page and verify __EPUPP_SCRIPT_PERF was set by document-start script."
  [context]
  (let [page (js-await (.newPage context))]
    (try
      (js-await (.goto page "http://localhost:18080/timing-test.html" #js {:timeout 2000}))
      (js-await (-> (expect (.locator page "#timing-marker"))
                    (.toBeVisible #js {:timeout 2000})))
      (js-await (poll-until
                 #(.evaluate page (fn [] (= (js/typeof js/window.__EPUPP_SCRIPT_PERF) "number")))
                 2000 50))
      (let [epupp-perf (js-await (.evaluate page (fn [] js/window.__EPUPP_SCRIPT_PERF)))]
        (js-await (-> (expect epupp-perf) (.toBeDefined)))
        (js/console.log "Document-start script perf:" epupp-perf))
      (finally
        (js-await (.close page))))))

(defn- ^:async test_document_start_script_runs_before_page_script []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))
        bg-logs (setup-bg-console-capture context)]
    (try
      ;; Create and save a document-start script
      (js-await (panel-save-helpers/create-script-via-panel!
                 context ext-id
                 {:code-opts {:name "Document Start Timing Test"
                              :match "http://localhost:18080/*"
                              :run-at "document-start"
                              :code "(set! (.-__EPUPP_SCRIPT_PERF js/window) (js/performance.now))"}
                  :status "Created"}))
      ;; Enable the script (defaults to disabled for auto-run)
      (js-await (enable-script! context ext-id "document_start_timing_test.cljs"))
      ;; Verify content script registration
      (js-await (assert-registration! bg-logs))
      ;; Navigate to test page and verify script ran
      (js-await (verify-script-ran-on-page! context))
      ;; Check for errors
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_document_start_script_requires_enabled_state []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create document-start script (will be disabled by default) ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (.fill textarea ""))
        (js-await (wait-for-panel-ready panel))
        ;; Create script with document-start timing
        (let [code (panel-save-helpers/code-with-manifest
                    {:name "Disabled Doc Start Test"
                     :match "http://localhost:18080/*"
                     :run-at "document-start"
                     :code "(set! js/window.__DISABLED_SCRIPT_RAN true)"})]
          (js-await (.fill textarea code)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "Created"))
        (js-await (.close panel)))

      ;; === PHASE 2: Navigate WITHOUT enabling - script should NOT run ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 2000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toBeVisible #js {:timeout 2000})))

        ;; Check that script did NOT run (window var should be undefined)
        (let [script-ran (js-await (.evaluate page (fn [] js/window.__DISABLED_SCRIPT_RAN)))]
          (js-await (-> (expect script-ran) (.toBeUndefined))))

        (js-await (.close page)))

      ;; === PHASE 3: Check for errors ===
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(.describe test "Document-start Script Timing"
           (fn []
             (test "document-start scripts run before page scripts when enabled"
                   test_document_start_script_runs_before_page_script)

             (test "document-start scripts do not run when disabled"
                   test_document_start_script_requires_enabled_state)))
