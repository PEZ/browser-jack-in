(ns e2e.library-deps-document-start-test
  "E2E tests for document-start scripts with epupp:// library dependencies.

   Tests the early loader path (userscript-loader.cljs) resolving library
   dependencies at document-start timing. Distinct from library_deps_test.cljs
   which tests the background injection pipeline (document-idle).

   Coverage:
   1. Document-start consumer loads library via epupp://
   2. Transitive chain A -> B -> consumer at document-start
   3. Missing library reports LOADER_RESOLUTION_ERROR"
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page wait-for-event
                              wait-for-save-status
                              wait-for-popup-ready
                              get-script-item wait-for-checkbox-state
                              poll-until
                              assert-no-errors! clear-test-events!]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- code-with-manifest
  "Generate test code with epupp manifest metadata including inject support."
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

(defn- setup-bg-console-capture
  "Set up background worker console capture. Returns bg-logs atom.
   Must be called BEFORE triggering registration."
  [context]
  (let [bg-logs (atom [])
        workers (.serviceWorkers context)
        bg-worker (aget workers 0)]
    (.on bg-worker "console"
         (fn [msg]
           (swap! bg-logs conj (.text msg))))
    bg-logs))

(defn- ^:async wait-for-registration
  "Wait for content scripts to be registered successfully.
   Uses bg-logs atom from setup-bg-console-capture."
  [bg-logs]
  (js-await (poll-until
             (fn []
               (let [text (str/join "\n" @bg-logs)]
                 (str/includes? text "Content scripts registered successfully")))
             3000))
  (let [bg-text (str/join "\n" @bg-logs)]
    (when (str/includes? bg-text "Sync failed")
      (throw (js/Error. (str "Registration failed! Logs:\n" bg-text))))))

;; =============================================================================
;; Test: Document-start consumer loads library via epupp://
;; =============================================================================

(defn- ^:async test_document_start_consumer_with_library []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))
        bg-logs (setup-bg-console-capture context)]
    (try
      ;; === PHASE 1: Save library and consumer ===
      (let [lib-code (code-with-manifest
                      {:name "test/ds_lib.cljs"
                       :code "(ns test.ds-lib)\n\n(set! (.-__DS_LIB_LOADED js/window) true)"})]
        (js-await (save-script-via-panel context ext-id lib-code)))

      (let [consumer-code (code-with-manifest
                           {:name "test/ds_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :run-at "document-start"
                            :inject ["epupp://test/ds_lib.cljs"]
                            :code "(ns test.ds-consumer\n  (:require [test.ds-lib]))\n\n(set! (.-__DS_CONSUMER_RAN js/window) true)"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; === PHASE 2: Enable consumer and wait for registration ===
      (js-await (enable-script-via-popup context ext-id "test/ds_consumer.cljs"))
      (js-await (wait-for-registration bg-logs))

      ;; Clear test events before navigation
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; === PHASE 3: Navigate and verify both scripts ran ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Wait for LOADER_RUN event
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-event popup "LOADER_RUN" 5000))

          ;; Poll for library global
          (js-await (poll-until
                     (^:async fn []
                       (js-await (.evaluate page (fn [] (= true js/window.__DS_LIB_LOADED)))))
                     5000))

          ;; Poll for consumer global
          (js-await (poll-until
                     (^:async fn []
                       (js-await (.evaluate page (fn [] (= true js/window.__DS_CONSUMER_RAN)))))
                     5000))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Transitive chain at document-start (A -> B -> consumer)
;; =============================================================================

(defn- ^:async test_document_start_transitive_chain []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))
        bg-logs (setup-bg-console-capture context)]
    (try
      ;; === PHASE 1: Save chain A -> B -> consumer ===
      ;; Library A: no deps
      (let [lib-a-code (code-with-manifest
                        {:name "test/ds_chain_a.cljs"
                         :code "(ns test.ds-chain-a)\n\n(set! (.-__DS_CHAIN_A js/window) true)"})]
        (js-await (save-script-via-panel context ext-id lib-a-code)))

      ;; Library B: depends on A
      (let [lib-b-code (code-with-manifest
                        {:name "test/ds_chain_b.cljs"
                         :inject ["epupp://test/ds_chain_a.cljs"]
                         :code "(ns test.ds-chain-b\n  (:require [test.ds-chain-a]))\n\n(set! (.-__DS_CHAIN_B js/window) true)"})]
        (js-await (save-script-via-panel context ext-id lib-b-code)))

      ;; Consumer: depends on B, document-start timing
      (let [consumer-code (code-with-manifest
                           {:name "test/ds_chain_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :run-at "document-start"
                            :inject ["epupp://test/ds_chain_b.cljs"]
                            :code "(ns test.ds-chain-consumer\n  (:require [test.ds-chain-b]))\n\n(set! (.-__DS_CHAIN_CONSUMER js/window) true)"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; === PHASE 2: Enable consumer and wait for registration ===
      (js-await (enable-script-via-popup context ext-id "test/ds_chain_consumer.cljs"))
      (js-await (wait-for-registration bg-logs))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; === PHASE 3: Navigate and verify all three globals ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-event popup "LOADER_RUN" 5000))

          ;; Poll until all three globals are set
          (js-await (poll-until
                     (^:async fn []
                       (js-await (.evaluate page
                                            (fn [] (and (= true js/window.__DS_CHAIN_A)
                                                        (= true js/window.__DS_CHAIN_B)
                                                        (= true js/window.__DS_CHAIN_CONSUMER))))))
                     5000))

          ;; Verify each individually for clear error messages
          (let [a (js-await (.evaluate page (fn [] js/window.__DS_CHAIN_A)))
                b (js-await (.evaluate page (fn [] js/window.__DS_CHAIN_B)))
                c (js-await (.evaluate page (fn [] js/window.__DS_CHAIN_CONSUMER)))]
            (js-await (-> (expect a) (.toBe true)))
            (js-await (-> (expect b) (.toBe true)))
            (js-await (-> (expect c) (.toBe true))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Missing library at document-start reports error
;; =============================================================================

(defn- ^:async test_document_start_missing_library []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))
        bg-logs (setup-bg-console-capture context)]
    (try
      ;; === PHASE 1: Save consumer with missing dep ===
      ;; Consumer requires nonexistent lib - Scittle will fail on the require
      (let [consumer-code (code-with-manifest
                           {:name "test/ds_bad_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :run-at "document-start"
                            :inject ["epupp://test/ds_nonexistent.cljs"]
                            :code "(ns test.ds-bad-consumer\n  (:require [test.ds-nonexistent]))\n\n(set! (.-__DS_BAD_CONSUMER_RAN js/window) true)"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      ;; === PHASE 2: Enable and wait for registration ===
      (js-await (enable-script-via-popup context ext-id "test/ds_bad_consumer.cljs"))
      (js-await (wait-for-registration bg-logs))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      ;; === PHASE 3: Navigate and verify error ===
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          ;; Wait for LOADER_RESOLUTION_ERROR event
          (let [error-event (js-await (wait-for-event popup "LOADER_RESOLUTION_ERROR" 5000))]
            (js-await (-> (expect (.-event error-event)) (.toBe "LOADER_RESOLUTION_ERROR")))
            ;; Verify error message contains "not found"
            (let [messages (.-messages (.-data error-event))]
              (js-await (-> (expect (.join messages " ")) (.toContain "not found")))))

          ;; Verify consumer did NOT run (Scittle fails on missing require)
          ;; Absence assertion: wait, then check global is undefined
          (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 1000))))
          (let [consumer-ran (js-await (.evaluate page (fn [] js/window.__DS_BAD_CONSUMER_RAN)))]
            (js-await (-> (expect consumer-ran) (.toBeUndefined))))

          ;; Note: skip assert-no-errors! - Scittle error from missing require is expected
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test Registration
;; =============================================================================

(.describe test "Library Dependencies: document-start epupp:// resolution"
           (fn []
             (test "document-start consumer loads library via epupp:// inject"
                   test_document_start_consumer_with_library)

             (test "document-start transitive chain: consumer -> B -> A"
                   test_document_start_transitive_chain)

             (test "document-start missing library reports error and consumer does not run"
                   test_document_start_missing_library)))
