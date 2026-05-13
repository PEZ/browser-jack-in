(ns e2e.web-installer.installation-test
  "E2E tests for basic web userscript installation flows."
  (:require ["@playwright/test" :refer [test expect]]
            ["./../fixtures.mjs" :refer [launch-browser get-extension-id create-popup-page
                                          wait-for-popup-ready wait-for-event assert-no-errors!
                                          send-runtime-message clear-test-events!]]
            ["./helpers.mjs" :as h]))

(def git-raw-url
  "https://raw.githubusercontent.com/PEZ/pez-my-epupp-hq/3dbf6393916cd4e384826b093ab6e9a96b1793f9/userscripts/pez/test_lib.cljs")

(defn- cache-timeout-message
  [expected-url timeout-ms cache]
  (str "Timeout (" timeout-ms "ms) waiting for extDepCache to contain: "
       expected-url
       "\nCache keys: "
       (if cache
         (js/JSON.stringify (.keys js/Object cache))
         "null/undefined")))

(defn- ^:async fetch-ext-dep-entry
  "Fetch a single extDepCache entry by URL. Returns the entry or nil."
  [ext-page expected-url]
  (let [result (js-await (send-runtime-message ext-page "e2e/get-storage"
                                               #js {:key "extDepCache"}))
        cache (when (and result (.-success result)) (.-value result))]
    {:cache cache
     :entry (when cache (aget cache expected-url))}))

(defn- ^:async poll-ext-dep-cache
  "Poll extDepCache storage until the expected URL key exists."
  [ext-page expected-url timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [{:keys [cache entry]} (js-await (fetch-ext-dep-entry ext-page expected-url))]
        (cond
          entry
          entry

          (> (- (.now js/Date) start) timeout-ms)
          (throw (js/Error. (cache-timeout-message expected-url timeout-ms cache)))

          :else
          (do
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 200))))
            (recur)))))))

(defn- ^:async test_shows_button_and_installs []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Wait for Install button to appear
        (js-await (h/wait-for-install-button page "#installable-gist" "install" 2000))
        (js/console.log "Install button found")

        ;; Click install and confirm
        (js-await (h/click-install-and-confirm!+ page "#installable-gist" "installed"))
        (js/console.log "Script installed successfully")

        (js-await (.close page)))

      ;; Verify script appears in popup
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"test_installer_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000}))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_manual_only_script []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Install the manual-only script
        (js-await (h/wait-for-install-button page "#manual-only-gist" "install" 2000))
        (js-await (h/click-install-and-confirm!+ page "#manual-only-gist" "installed"))
        (js/console.log "Manual-only script installed")

        (js-await (.close page)))

      ;; Verify script shows "No auto-run (manual only)"
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))

        (let [script-item (.locator popup ".script-item:has-text(\"manual_only_script.cljs\")")]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000})))

          ;; Verify it shows "No auto-run (manual only)"
          (let [match-span (.locator script-item ".script-match")]
            (js-await (-> (expect match-span)
                          (.toHaveText "No auto-run (manual only)" #js {:timeout 500})))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async assert-dependency-preview!
  "Assert the install modal shows dependency preview with expected content."
  [page]
  (js-await (-> (expect (.locator page ".epupp-modal__table"))
                (.toContainText "Dependencies" #js {:timeout 1000})))
  (js-await (-> (expect (.locator page ".epupp-modal__table"))
                (.toContainText "epupp://phase6/wi_lib.cljs" #js {:timeout 1000})))
  (js-await (-> (expect (.locator page ".epupp-modal__table"))
                (.toContainText "(user library)" #js {:timeout 1000})))
  (js-await (-> (expect (.locator page "[data-e2e-epupp-deps-note]"))
                (.toContainText "installed separately" #js {:timeout 1000}))))

(defn- ^:async poll-page-result
  "Poll a page-level JS variable until it equals expected-value, or throw on timeout."
  [page js-var-name expected-value timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [result (js-await (.evaluate page (js* "name => window[name]") js-var-name))]
        (cond
          (= result expected-value)
          (js-await (-> (expect result) (.toBe expected-value)))

          (> (- (.now js/Date) start) timeout-ms)
          (throw (js/Error. (str "Timeout waiting for " js-var-name)))

          :else
          (do
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
            (recur)))))))

(defn- ^:async test_epupp_dependency_preview_and_runtime_resolution []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      (let [page (js-await (h/navigate-to-mock-gist context))
            consumer-container "#dep-consumer-gist"]
        (js-await (h/wait-for-install-button page consumer-container "install" 2000))
        (js-await (.click (h/get-install-button page consumer-container "install")))

        (js-await (assert-dependency-preview! page))

        (js-await (.click (.locator page "#epupp-confirm")))
        (js-await (h/wait-for-install-button page consumer-container "installed" 2000))

        ;; Navigate to consumer target page: missing library should surface resolution error
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              error-event (js-await (wait-for-event popup "RESOLUTION_ERROR" 10000))]
          (js-await (-> (expect (.-event error-event)) (.toBe "RESOLUTION_ERROR")))
          (js-await (-> (expect (.. error-event -data -message))
                        (.toContain "phase6/wi_lib.cljs")))
          (js-await (.close popup)))

        ;; Install the missing library from web installer
        (js-await (.goto page "http://localhost:18080/mock-gist.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))
        (js-await (h/wait-for-install-button page "#dep-library-gist" "install" 2000))
        (js-await (h/click-install-and-confirm!+ page "#dep-library-gist" "installed"))

        ;; Next navigation should succeed for the already-installed consumer
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))
          (js-await (poll-page-result page "__PHASE6_WI_RESULT" "phase6-library-loaded" 5000))
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_scittle_dependency_runtime_resolution []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Install the scittle:// consumer and verify it runs on the target page
      (let [page (js-await (h/navigate-to-mock-gist context))
            consumer-container "#scittle-dep-gist"]
        (js-await (h/wait-for-install-button page consumer-container "install" 2000))
        (js-await (h/click-install-and-confirm!+ page consumer-container "installed"))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (clear-test-events! popup))
          (js-await (.close popup)))

        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))
          (js-await (-> (expect (.locator page "#web-installer-scittle-marker"))
                        (.toContainText "web-installer-scittle-ok" #js {:timeout 5000})))
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_https_ext_dep_runtime_resolution []
  (.setTimeout test 60000)
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Install the HTTPS ext-dep consumer, wait for cache population, then verify runtime behavior
      (let [page (js-await (h/navigate-to-mock-gist context))
            consumer-container "#https-ext-dep-gist"]
        (js-await (h/wait-for-install-button page consumer-container "install" 2000))
        (js-await (h/click-install-and-confirm!+ page consumer-container "installed"))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (poll-ext-dep-cache popup git-raw-url 20000))
          (js-await (clear-test-events! popup))
          (js-await (.close popup)))

        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))
          (js-await (-> (expect (.locator page "#web-installer-https-ext-dep-marker"))
                        (.toHaveText "Hello from pez.test-lib, WebInstallerHttps!" #js {:timeout 5000})))
          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Web Installer: installation"
           (fn []
             (test "Web Installer: shows Install button and installs script"
                   test_shows_button_and_installs)

             (test "Web Installer: installs manual-only script"
                   test_manual_only_script)

             (test "Web Installer: previews epupp:// dependencies and resolves after installing library"
                   test_epupp_dependency_preview_and_runtime_resolution)

             (test "Web Installer: installs scittle:// dependency consumer and executes it"
                   test_scittle_dependency_runtime_resolution)

             (test "Web Installer: installs HTTPS ext-dep consumer and executes it"
                   test_https_ext_dep_runtime_resolution)))
