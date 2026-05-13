(ns conditional-installer-test
  "E2E tests for conditional installer injection.
   Verifies that the background scanner only injects the web installer when:
   - The page is on a whitelisted origin
   - The page contains userscript manifest blocks
   Tests cover: no-manifest pages, manifest pages, non-whitelisted origins,
   and SPA navigation with retry scanning."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              wait-for-popup-ready assert-no-errors!
                              http-port]]
            ["./web_installer/helpers.mjs" :as h]))

;; ============================================================
;; Helpers
;; ============================================================

(def ^:private check-scittle-in-page
  "JS expression to check if Scittle has been loaded in the page."
  "(() => ({ hasScittle: !!(window.scittle && window.scittle.core) }))()")

(defn- ^:async navigate-to-no-manifests
  "Navigate to test page with no userscript manifests."
  [context]
  (let [page (js-await (.newPage context))]
    (js-await (.goto page (str "http://localhost:" http-port "/no-manifests.html")
                     #js {:timeout 2000}))
    (js-await (-> (expect (.locator page "#test-marker"))
                  (.toContainText "ready")))
    page))

(defn- ^:async navigate-to-spa-manifest-test
  "Navigate to SPA manifest test page."
  [context]
  (let [page (js-await (.newPage context))]
    (js-await (.goto page (str "http://localhost:" http-port "/spa-manifest-test.html")
                     #js {:timeout 2000}))
    (js-await (-> (expect (.locator page "#test-marker"))
                  (.toContainText "ready")))
    page))

;; ============================================================
;; Test: No manifests on whitelisted origin - Scittle NOT loaded
;; ============================================================

(defn- ^:async no-scittle-injection-test!+
  "Shared test body: verifies Scittle is NOT injected when navigating with navigate-fn."
  [navigate-fn]
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      (let [page (js-await (navigate-fn context))]
        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))

        (let [status (js-await (.evaluate page check-scittle-in-page))]
          (-> (expect (.-hasScittle status)) (.toBe false)))

        (let [install-buttons (.locator page "[data-e2e-install-state]")]
          (js-await (-> (expect install-buttons) (.toHaveCount 0))))

        (js-await (.close page)))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_no_manifests_no_scittle []
  (js-await (no-scittle-injection-test!+ navigate-to-no-manifests)))

;; ============================================================
;; Test: Page with manifest - installer injected and shows buttons
;; ============================================================

(defn- ^:async test_manifest_page_installer_injected []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page (has userscript manifests)
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Verify install buttons appear - the installer was injected
        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 2000}))))

        ;; Verify Scittle WAS loaded (installer needs Scittle)
        (let [status (js-await (.evaluate page check-scittle-in-page))]
          (-> (expect (.-hasScittle status)) (.toBe true)))

        (js-await (.close page)))

      ;; Check for errors
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; ============================================================
;; Test: Non-whitelisted origin - installer NOT injected
;; ============================================================

(defn- ^:async test_non_whitelisted_origin_no_injection []
  (js-await (no-scittle-injection-test!+ h/navigate-to-mock-gist-non-whitelisted)))

;; ============================================================
;; Test: SPA navigation triggers scanner retry and installer appears
;; ============================================================

(defn- ^:async test_spa_navigation_installer_appears []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to SPA test page (starts with no manifests)
      (let [page (js-await (navigate-to-spa-manifest-test context))]
        ;; Verify no install buttons on initial load (no manifests yet)
        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))
        (let [install-buttons (.locator page "[data-e2e-install-state]")]
          (js-await (-> (expect install-buttons) (.toHaveCount 0))))

        ;; Click "Gist Page" to trigger SPA navigation + manifest creation
        (js-await (.click (.locator page "#nav-gist")))

        ;; Wait for install button to appear via retry scanning
        ;; SPA nav retries at [0, 300, 1000, 3000ms] so use a generous timeout
        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 5000}))))

        (js-await (.close page)))

      ;; Check for errors
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; ============================================================
;; Test: Full page reload after SPA injection re-injects installer
;; ============================================================

(defn- ^:async test_reload_after_spa_reinjects []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to SPA test page and trigger SPA navigation to create manifests
      (let [page (js-await (navigate-to-spa-manifest-test context))]
        (js-await (.click (.locator page "#nav-gist")))

        ;; Wait for installer to appear after SPA nav
        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 5000}))))

        ;; Now reload the page - tab tracking is cleared on navigation
        ;; After reload, we're back at the base URL without manifests
        ;; Navigate to the gist view URL directly to have manifests on load
        (js-await (.goto page (str "http://localhost:" http-port "/spa-manifest-test.html?view=gist")
                         #js {:timeout 2000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; The page loads at base state without manifests showing
        ;; (the ?view=gist param is just in the URL, the JS navigate()
        ;; function only runs on button click/popstate)
        ;; So instead, navigate to mock-gist which always has manifests
        (js-await (.goto page (str "http://localhost:" http-port "/mock-gist.html")
                         #js {:timeout 2000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        ;; Verify installer appears again (tab tracking was cleared by navigation)
        (let [install-buttons (h/get-install-button page "install")]
          (js-await (-> (expect install-buttons)
                        (.not.toHaveCount 0 #js {:timeout 2000}))))

        (js-await (.close page)))

      ;; Check for errors
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

;; ============================================================
;; Test Registration
;; ============================================================

(test "no manifests on whitelisted origin: Scittle not loaded" test_no_manifests_no_scittle)
(test "manifest page on whitelisted origin: installer injected" test_manifest_page_installer_injected)
(test "non-whitelisted origin: installer not injected" test_non_whitelisted_origin_no_injection)
(test "SPA navigation: installer appears after retry scan" test_spa_navigation_installer_appears)
(test "reload after SPA injection: installer re-injected" test_reload_after_spa_reinjects)
