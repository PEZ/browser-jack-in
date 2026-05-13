(ns e2e.popup-connection-test
  "E2E tests for popup REPL connection tracking and status."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures.constants :refer [ws-port-1]]
            [fixtures.browser :refer [launch-browser get-extension-id]]
            [fixtures.pages :refer [create-popup-page]]
            [fixtures.wait :refer [wait-for-popup-ready]]
            [fixtures.messaging :refer [wait-for-connection find-tab-id connect-tab]]
            [fixtures.events :refer [assert-no-errors!]]))

;; =============================================================================
;; Popup User Journey: Connection Tracking and Management
;; =============================================================================

(defn- ^:async test_connection_tracking_displays_connected_tabs []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Navigate to a test page
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Open popup and connect
        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Initially no connections
          (let [no-conn-msg (.locator popup ".no-connections")]
            (js-await (-> (expect no-conn-msg)
                          (.toBeVisible))))

          ;; Find and connect the page
          (let [tab-id (js-await (find-tab-id popup "http://localhost:18080/basic.html"))]
            (js-await (connect-tab popup tab-id ws-port-1))

            ;; Wait for connection event then reload popup
            (js-await (wait-for-connection popup 5000))
            (js-await (.reload popup))
            (js-await (wait-for-popup-ready popup))

            ;; Should now show 1 connected tab
            (let [connected-items (.locator popup ".connected-tab-item")]
              (js-await (-> (expect connected-items)
                            (.toHaveCount 1))))

            ;; Connected tab should show port number
            (let [port-elem (.locator popup ".connected-tab-port")]
              (js-await (-> (expect port-elem)
                            (.toContainText ":12346"))))

            ;; Tab should have a reveal or disconnect button
            (let [action-btns (.locator popup ".reveal-tab-btn, .disconnect-tab-btn")]
              (js-await (-> (expect action-btns)
                            (.toBeVisible)))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Popup User Journey: Connection Status Feedback
;; =============================================================================

(defn- ^:async test_connection_retry_shows_waiting_and_can_cancel []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Click Connect - no server running, so retry loop starts
          (let [connect-btn (.locator popup "#connect")]
            (js-await (.click connect-btn)))

          ;; Should show "Waiting for server" in system banner
          (let [waiting-banner (.locator popup ".system-banner:has-text(\"Waiting for server\")")]
            (js-await (-> (expect waiting-banner)
                          (.toBeVisible #js {:timeout 2000}))))

          ;; Cancel button should be visible
          (let [cancel-btn (.locator popup "#cancel-connect")]
            (js-await (-> (expect cancel-btn)
                          (.toBeVisible #js {:timeout 500})))

            ;; Click Cancel to stop retry
            (js-await (.click cancel-btn)))

          ;; Should show "cancelled" in system banner
          (let [cancelled-banner (.locator popup ".system-banner:has-text(\"cancelled\")")]
            (js-await (-> (expect cancelled-banner)
                          (.toBeVisible #js {:timeout 500}))))

          ;; Connect button should be back
          (let [connect-btn (.locator popup "#connect")]
            (js-await (-> (expect connect-btn)
                          (.toBeVisible #js {:timeout 500}))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_successful_connection_via_api_updates_ui []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 1000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))

          ;; Initially no connections
          (let [no-conn-msg (.locator popup ".no-connections")]
            (js-await (-> (expect no-conn-msg)
                          (.toBeVisible))))

          ;; Connect via direct API (bypasses UI button permission issues)
          (let [tab-id (js-await (find-tab-id popup "http://localhost:18080/basic.html"))]
            (js-await (connect-tab popup tab-id ws-port-1))

            ;; Wait for connection event then reload popup
            (js-await (wait-for-connection popup 5000))
            (js-await (.reload popup))
            (js-await (wait-for-popup-ready popup))

            ;; Should now show connection in UI
            (let [connected-items (.locator popup ".connected-tab-item")]
              (js-await (-> (expect connected-items)
                            (.toHaveCount 1)))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Popup Connection"
           (fn []
             (test "Popup Connection: connection tracking displays connected tabs with reveal buttons"
                   test_connection_tracking_displays_connected_tabs)

             (test "Popup Connection: connection retry shows waiting status and can be cancelled"
                   test_connection_retry_shows_waiting_and_can_cancel)

             (test "Popup Connection: successful connection via API updates UI correctly"
                   test_successful_connection_via_api_updates_ui)))
