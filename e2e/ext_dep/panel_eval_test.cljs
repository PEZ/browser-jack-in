(ns e2e.ext-dep.panel-eval-test
  "E2E tests for panel eval with ext-dep cache injection.

   Tests:
   1. Panel eval: consumer loads ext-dep from git raw URL cache
   2. Panel eval: consumer loads ext-dep from gist raw URL cache"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page-for-tab wait-for-popup-ready
                              assert-no-errors! find-tab-id http-port]]
            [ext-dep-helpers :refer [git-raw-url gist-raw-url pez-test-lib-code
                                         set-ext-dep-cache! make-ext-dep-cache
                                         poll-for-scittle-eval!]]))

(defn- ^:async test_panel_eval_consumer_loads_ext_dep_from_git_cache []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html") #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))
          (js-await (set-ext-dep-cache! popup (make-ext-dep-cache git-raw-url pez-test-lib-code)))
          (let [tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
            (js-await (.close popup))

            (let [panel (js-await (create-panel-page-for-tab context ext-id tab-id))
                  consumer-code (str "{:epupp/script-name \"test/panel_git_consumer.cljs\"\n"
                                     " :epupp/inject [\"" git-raw-url "\"]}\n\n"
                                     "(ns test.panel-git-consumer\n"
                                     "  (:require [pez.test-lib :as lib]))\n\n"
                                     "(set! (.-__EPUPP_PANEL_GIT_RESULT js/window) (lib/greeting \"PanelGit\"))")]
              (js-await (.fill (.locator panel "#code-area") consumer-code))
              (js-await (.click (.locator panel "button.btn-eval")))

              (let [result (js-await (poll-for-scittle-eval! test-page "(pez.test-lib/greeting \"test\")" 5000))]
                (js-await (-> (expect result) (.toBe "Hello from pez.test-lib, test!"))))

              (js-await (assert-no-errors! panel))
              (js-await (.close panel)))))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_panel_eval_consumer_loads_ext_dep_from_gist_cache []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html") #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-popup-ready popup))
          (js-await (set-ext-dep-cache! popup (make-ext-dep-cache gist-raw-url pez-test-lib-code)))
          (let [tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
            (js-await (.close popup))

            (let [panel (js-await (create-panel-page-for-tab context ext-id tab-id))
                  consumer-code (str "{:epupp/script-name \"test/panel_gist_consumer.cljs\"\n"
                                     " :epupp/inject [\"" gist-raw-url "\"]}\n\n"
                                     "(ns test.panel-gist-consumer\n"
                                     "  (:require [pez.test-lib :as lib]))\n\n"
                                     "(set! (.-__EPUPP_PANEL_GIST_RESULT js/window) (lib/greeting \"PanelGist\"))")]
              (js-await (.fill (.locator panel "#code-area") consumer-code))
              (js-await (.click (.locator panel "button.btn-eval")))

              (let [result (js-await (poll-for-scittle-eval! test-page "(pez.test-lib/greeting \"test\")" 5000))]
                (js-await (-> (expect result) (.toBe "Hello from pez.test-lib, test!"))))

              (js-await (assert-no-errors! panel))
              (js-await (.close panel)))))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

(.describe test "External Dependencies: panel eval with ext-dep cache"
           (fn []
             (test "panel eval: consumer loads ext-dep from raw.githubusercontent.com cache"
                   test_panel_eval_consumer_loads_ext_dep_from_git_cache)

             (test "panel eval: consumer loads ext-dep from gist raw URL cache"
                   test_panel_eval_consumer_loads_ext_dep_from_gist_cache)))
