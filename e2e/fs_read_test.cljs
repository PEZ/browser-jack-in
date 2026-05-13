(ns e2e.fs-read-test
  "E2E tests for REPL file system read operations: epupp.fs/show, epupp.fs/ls"
  (:require ["@playwright/test" :refer [test expect chromium]]
            ["path" :as path]
            [fixtures :refer [http-port ws-port-1
                              send-runtime-message get-extension-id
                              find-tab-id connect-tab]]
            [fs-write-helpers :refer [eval-in-browser eval-async-and-poll!
                                      wait-for-builtin-script wait-for-script-tag]]))

(def ^:private !context (atom nil))

(defn ^:async setup-browser! []
  (let [extension-path (.resolve path "dist/chrome")
        ctx (js-await (.launchPersistentContext
                       chromium ""
                       #js {:headless false
                            :args #js ["--no-sandbox"
                                       "--allow-file-access-from-files"
                                       "--enable-features=ExtensionsManifestV3Only"
                                       (str "--disable-extensions-except=" extension-path)
                                       (str "--load-extension=" extension-path)]}))]
    (reset! !context ctx)
    (let [ext-id (js-await (get-extension-id ctx))
          test-page (js-await (.newPage ctx))]
      (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")))
      (js-await (.waitForLoadState test-page "domcontentloaded"))
      (let [bg-page (js-await (.newPage ctx))]
        (js-await (.goto bg-page
                         (str "chrome-extension://" ext-id "/popup.html")
                         #js {:waitUntil "networkidle"}))
        (let [tab-id (js-await (find-tab-id bg-page "http://localhost:*/*"))]
          (js-await (connect-tab bg-page tab-id ws-port-1))
          (js-await (send-runtime-message bg-page "toggle-fs-sync" #js {:tabId tab-id :enabled true}))
          (js-await (send-runtime-message bg-page "e2e/ensure-builtin" #js {}))
          (js-await (wait-for-builtin-script bg-page "epupp-builtin-web-userscript-installer" 5000))
          (js-await (.close bg-page))
          (js-await (wait-for-script-tag "scittle" 5000)))))))

(defn- ^:async test_show_retrieves_script_code_by_name []
  (let [ns-check (js-await (eval-in-browser "(fn? epupp.fs/show)"))]
    (-> (expect (.-success ns-check)) (.toBe true))
    (-> (expect (.-values ns-check)) (.toContain "true")))
  (let [result (js-await (eval-async-and-poll!
                          "(def !show-result (atom :pending))
                           (defn ^:async do-it [] (reset! !show-result (await (epupp.fs/show \"epupp/web_userscript_installer.cljs\"))))
                           (do-it)
                           :setup-done"
                          "@!show-result"
                          3000))]
    (-> (expect (.includes result "epupp/script-name")) (.toBe true))))

(defn- ^:async test_show_returns_nil_for_nonexistent_script []
  (let [result (js-await (eval-async-and-poll!
                          "(def !show-nil-result (atom :pending))
                           (defn ^:async do-it [] (reset! !show-nil-result (await (epupp.fs/show \"does-not-exist.cljs\"))))
                           (do-it)
                           :setup-done"
                          "@!show-nil-result"
                          3000))]
    (-> (expect result) (.toBe "nil"))))

(defn- ^:async test_show_with_vector_returns_map []
  (let [result (js-await (eval-async-and-poll!
                          "(def !bulk-show-result (atom :pending))
                           (defn ^:async do-it [] (reset! !bulk-show-result (await (epupp.fs/show [\"epupp/web_userscript_installer.cljs\" \"does-not-exist.cljs\"]))))
                           (do-it)
                           :setup-done"
                          "(pr-str @!bulk-show-result)"
                          3000))]
    (-> (expect (.includes result "epupp/web_userscript_installer.cljs")) (.toBe true))
    (-> (expect (.includes result "epupp/script-name")) (.toBe true))
    (-> (expect (.includes result "does-not-exist.cljs")) (.toBe true))
    (-> (expect (.includes result "nil")) (.toBe true))))

(defn- ^:async test_ls_hides_builtins_by_default []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/ls)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))
  (let [result (js-await (eval-async-and-poll!
                          "(def !ls-result (atom :pending))
                           (defn ^:async do-it [] (reset! !ls-result (await (epupp.fs/ls))))
                           (do-it)
                           :setup-done"
                          "(pr-str @!ls-result)"
                          3000))]
    (-> (expect (.includes result "epupp/web_userscript_installer.cljs")) (.toBe false))))

(defn- ^:async test_ls_includes_builtins_when_option_set []
  (let [result (js-await (eval-async-and-poll!
                          "(def !ls-hidden-result (atom :pending))
                           (defn ^:async do-it [] (reset! !ls-hidden-result (await (epupp.fs/ls {:fs/ls-hidden? true}))))
                           (do-it)
                           :setup-done"
                          "(pr-str @!ls-hidden-result)"
                          3000))]
    (-> (expect (.includes result "epupp/web_userscript_installer.cljs")) (.toBe true))
    (-> (expect (.includes result ":requestId")) (.toBe false))
    (-> (expect (.includes result ":source")) (.toBe false))
    (-> (expect (.includes result ":type")) (.toBe false))))

(.describe test "REPL FS: read operations"
           (fn []
             (.beforeAll test (fn [] (setup-browser!)))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: show - retrieves script code by name"
                   test_show_retrieves_script_code_by_name)

             (test "REPL FS: show - returns nil for non-existent script"
                   test_show_returns_nil_for_nonexistent_script)

             (test "REPL FS: show - vector returns map of names to codes"
                   test_show_with_vector_returns_map)

             (test "REPL FS: ls - hides built-in scripts by default"
                   test_ls_hides_builtins_by_default)

             (test "REPL FS: ls - includes built-ins when :fs/ls-hidden? true"
                   test_ls_includes_builtins_when_option_set)))

