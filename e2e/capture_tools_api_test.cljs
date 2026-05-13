(ns e2e.capture-tools-api-test
  "E2E tests for the epupp.tools capture script API.
   Exercises capture-visible, capture-selector, and error paths
   via nREPL evaluation in the page's Scittle context."
  (:require ["@playwright/test" :refer [test expect chromium]]
            [fixtures.constants :refer [extension-path http-port ws-port-1]]
            [fixtures.browser :refer [get-extension-id]]
            [fixtures.messaging :refer [send-runtime-message]]
            [fixtures.events :refer [assert-no-errors!]]
            [fs-write-helpers :refer [eval-in-browser sleep wait-for-script-tag]]))

(def !context (atom nil))
(def !ext-id (atom nil))

(defn- ^:async wait-for-atom
  "Poll an nREPL-defined atom until its value is not :pending."
  [atom-name timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [result (js-await (eval-in-browser (str "(= :pending @" atom-name ")")))]
        (cond
          (and (.-success result)
               (= (first (.-values result)) "false"))
          true

          (> (- (.now js/Date) start) timeout-ms)
          (throw (js/Error. (str "Timeout waiting for " atom-name)))

          :else
          (do
            (js-await (sleep 20))
            (recur)))))))

(defn- ^:async check-eval
  "Evaluate code via nREPL and assert the value equals expected."
  [code expected]
  (let [result (js-await (eval-in-browser code))]
    (-> (expect (.-success result)) (.toBe true))
    (-> (expect (first (.-values result))) (.toBe expected))))

(defn- assert-success! [result error-prefix]
  (when-not (and result (.-success result))
    (throw (js/Error. (str error-prefix (.-error result))))))

(defn- ^:async connect-test-tab! [ctx ext-id]
  (let [popup (js-await (.newPage ctx))]
    (js-await (.goto popup
                     (str "chrome-extension://" ext-id "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (let [find-result (js-await (send-runtime-message
                                  popup "e2e/find-tab-id"
                                  #js {:urlPattern "http://localhost:*/*"}))]
      (assert-success! find-result "Could not find test tab: ")
      (let [connect-result (js-await (send-runtime-message
                                       popup "connect-tab"
                                       #js {:tabId (.-tabId find-result)
                                            :wsPort ws-port-1}))]
        (assert-success! connect-result "Connection failed: ")
        (js-await (.close popup))))))

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
        (js-await (connect-test-tab! ctx ext-id))
        (js-await (wait-for-script-tag "scittle" 5000))
        (let [req-result (js-await (eval-in-browser "(require '[epupp.tools :as tools])"))]
          (assert-success! req-result "Failed to require epupp.tools: "))))))

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

(defn- ^:async run-capture-test!
  "Run a capture API call in the page, wait for result, and assert success."
  [atom-name fn-name capture-expr]
  (let [code (str "(def " atom-name " (atom :pending))\n"
                  "(defn ^:async " fn-name " []\n"
                  "  (try\n"
                  "    (let [r (await " capture-expr ")]\n"
                  "      (reset! " atom-name "\n"
                  "              {:success (:success r)\n"
                  "               :has-url (some? (:dataUrl r))\n"
                  "               :url-ok (boolean (and (:dataUrl r)\n"
                  "                                     (.startsWith (:dataUrl r) \"data:image/\")))}))\n"
                  "    (catch :default e\n"
                  "      (reset! " atom-name " {:error (.-message e)}))))\n"
                  "(" fn-name ")\n"
                  ":started")
        setup (js-await (eval-in-browser code))]
    (-> (expect (.-success setup)) (.toBe true))
    (js-await (wait-for-atom atom-name 15000))
    (js-await (check-eval (str "(boolean (:success @" atom-name "))") "true"))
    (js-await (check-eval (str "(boolean (:has-url @" atom-name "))") "true"))
    (js-await (check-eval (str "(boolean (:url-ok @" atom-name "))") "true"))))

(defn- ^:async test_capture_visible_returns_image_data []
  (js-await (run-capture-test! "!cv-result" "run-cv" "(tools/capture-visible)")))

(defn- ^:async test_capture_selector_body_returns_image_data []
  (js-await (run-capture-test! "!cs-result" "run-cs" "(tools/capture-selector \"body\")")))

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
