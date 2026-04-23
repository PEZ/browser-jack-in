(ns e2e.capture-tools-api-test
  "E2E tests for the epupp.tools capture script API.
   Exercises capture-visible, capture-selector, and error paths
   via nREPL evaluation in the page's Scittle context."
  (:require ["@playwright/test" :refer [test expect chromium]]
            [fixtures :refer [extension-path get-extension-id http-port ws-port-1
                              send-runtime-message assert-no-errors!]]
            [fs-write-helpers :refer [eval-in-browser sleep wait-for-script-tag]]))

(def !context (atom nil))
(def !ext-id (atom nil))

(defn- ^:async wait-for-atom
  "Poll an nREPL-defined atom until its value is not :pending."
  [atom-name timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [result (js-await (eval-in-browser (str "(= :pending @" atom-name ")")))]
        (if (and (.-success result)
                 (= (first (.-values result)) "false"))
          true
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. (str "Timeout waiting for " atom-name)))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async check-eval
  "Evaluate code via nREPL and assert the value equals expected."
  [code expected]
  (let [result (js-await (eval-in-browser code))]
    (-> (expect (.-success result)) (.toBe true))
    (-> (expect (first (.-values result))) (.toBe expected))))

(defn- ^:async setup! []
  (let [ctx (js-await (.launchPersistentContext
                        chromium ""
                        #js {:headless false
                             :args #js ["--no-sandbox"
                                        (str "--disable-extensions-except=" extension-path)
                                        (str "--load-extension=" extension-path)]}))]
    (reset! !context ctx)
    (let [ext-id (js-await (get-extension-id ctx))]
      (reset! !ext-id ext-id)
      (let [page (js-await (.newPage ctx))]
        (js-await (.goto page (str "http://localhost:" http-port "/basic.html")))
        (js-await (.waitForLoadState page "domcontentloaded"))
        (let [popup (js-await (.newPage ctx))]
          (js-await (.goto popup
                           (str "chrome-extension://" ext-id "/popup.html")
                           #js {:waitUntil "networkidle"}))
          (let [find-result (js-await (send-runtime-message
                                       popup "e2e/find-tab-id"
                                       #js {:urlPattern "http://localhost:*/*"}))]
            (when-not (and find-result (.-success find-result))
              (throw (js/Error. (str "Could not find test tab: " (.-error find-result)))))
            (let [connect-result (js-await (send-runtime-message
                                            popup "connect-tab"
                                            #js {:tabId (.-tabId find-result)
                                                 :wsPort ws-port-1}))]
              (when-not (and connect-result (.-success connect-result))
                (throw (js/Error. (str "Connection failed: " (.-error connect-result)))))
              (js-await (.close popup))
              (js-await (wait-for-script-tag "scittle" 5000))
              (let [req-result (js-await (eval-in-browser "(require '[epupp.tools :as tools])"))]
                (when-not (.-success req-result)
                  (throw (js/Error. (str "Failed to require epupp.tools: " (.-error req-result)))))))))))))

(defn- ^:async teardown! []
  (when @!context
    (try
      (let [popup (js-await (.newPage @!context))]
        (js-await (.goto popup (str "chrome-extension://" @!ext-id "/popup.html")
                         #js {:waitUntil "networkidle"}))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (catch :default _ nil))
    (js-await (.close @!context))))

;; =============================================================================
;; Test functions
;; =============================================================================

(defn- ^:async test_capture_visible_returns_image_data []
  (let [code "(def !cv-result (atom :pending))
              (defn ^:async run-cv []
                (try
                  (let [r (await (tools/capture-visible))]
                    (reset! !cv-result
                            {:success (:success r)
                             :has-url (some? (:dataUrl r))
                             :url-ok (boolean (and (:dataUrl r)
                                                   (.startsWith (:dataUrl r) \"data:image/\")))}))
                  (catch :default e
                    (reset! !cv-result {:error (.-message e)}))))
              (run-cv)
              :started"
        setup (js-await (eval-in-browser code))]
    (-> (expect (.-success setup)) (.toBe true))
    (js-await (wait-for-atom "!cv-result" 15000))
    (js-await (check-eval "(boolean (:success @!cv-result))" "true"))
    (js-await (check-eval "(boolean (:has-url @!cv-result))" "true"))
    (js-await (check-eval "(boolean (:url-ok @!cv-result))" "true"))))

(defn- ^:async test_capture_selector_body_returns_image_data []
  (let [code "(def !cs-result (atom :pending))
              (defn ^:async run-cs []
                (try
                  (let [r (await (tools/capture-selector \"body\"))]
                    (reset! !cs-result
                            {:success (:success r)
                             :has-url (some? (:dataUrl r))
                             :url-ok (boolean (and (:dataUrl r)
                                                   (.startsWith (:dataUrl r) \"data:image/\")))}))
                  (catch :default e
                    (reset! !cs-result {:error (.-message e)}))))
              (run-cs)
              :started"
        setup (js-await (eval-in-browser code))]
    (-> (expect (.-success setup)) (.toBe true))
    (js-await (wait-for-atom "!cs-result" 15000))
    (js-await (check-eval "(boolean (:success @!cs-result))" "true"))
    (js-await (check-eval "(boolean (:has-url @!cs-result))" "true"))
    (js-await (check-eval "(boolean (:url-ok @!cs-result))" "true"))))

(defn- ^:async test_capture_selector_missing_element_throws []
  (let [code "(def !cs-err (atom :pending))
              (defn ^:async run-cs-err []
                (try
                  (let [r (await (tools/capture-selector \"#does-not-exist-at-all\"))]
                    (reset! !cs-err {:unexpected r}))
                  (catch :default e
                    (reset! !cs-err {:error (.-message e)}))))
              (run-cs-err)
              :started"
        setup (js-await (eval-in-browser code))]
    (-> (expect (.-success setup)) (.toBe true))
    (js-await (wait-for-atom "!cs-err" 5000))
    (js-await (check-eval "(some? (:error @!cs-err))" "true"))
    (let [result (js-await (eval-in-browser "(:error @!cs-err)"))]
      (-> (expect (.-success result)) (.toBe true))
      (let [value (first (.-values result))]
        (-> (expect value) (.toContain "no element matches"))
        (-> (expect value) (.toContain "#does-not-exist-at-all"))))))

;; =============================================================================
;; Test registration
;; =============================================================================

(.describe test "Capture Tools API"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (.setTimeout test 60000)
                           (js-await (setup!))))
             (.afterAll test (fn [] (teardown!)))

             (test "Capture Tools API: capture-visible returns image data URL"
                   test_capture_visible_returns_image_data)

             (test "Capture Tools API: capture-selector body returns image data URL"
                   test_capture_selector_body_returns_image_data)

             (test "Capture Tools API: capture-selector missing element throws"
                   test_capture_selector_missing_element_throws)))
