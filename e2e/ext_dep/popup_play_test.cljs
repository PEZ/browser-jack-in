(ns e2e.ext-dep.popup-play-test
  "E2E tests for popup play button with ext-dep cache injection.

   Tests:
   1. Popup play button: consumer loads ext-dep from git raw URL cache
   2. Popup play button: consumer loads ext-dep from gist raw URL cache"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              wait-for-event clear-test-events!
                              assert-no-errors! find-tab-id get-script-item
                              http-port]]
            [ext-dep-helpers :refer [git-raw-url gist-raw-url pez-test-lib-code
                                         code-with-manifest save-script-via-panel
                                         set-ext-dep-cache! make-ext-dep-cache
                                         poll-for-window-property!]]))

(defn- ^:async activate-tab!
  "Activate a browser tab by ID."
  [popup tab-id]
  (js-await (.evaluate popup
                       (fn [target-tab-id]
                         (js/Promise.
                          (fn [resolve]
                            (js/chrome.tabs.update target-tab-id #js {:active true}
                                                   (fn [] (resolve true))))))
                       tab-id)))

(defn- ^:async click-play-button!
  "Click the play/run button for a script in the popup."
  [popup script-name]
  (let [item (get-script-item popup script-name)
        run-btn (.locator item "button.script-run")]
    (js-await (-> (expect run-btn) (.toBeVisible #js {:timeout 500})))
    (js-await (.click run-btn))))

(defn- ^:async test_popup_play_consumer_loads_ext_dep_from_git_cache []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name "test/play_git_consumer.cljs"
                            :match (str "http://localhost:" http-port "/*")
                            :inject [git-raw-url]
                            :code "(ns test.play-git-consumer\n  (:require [pez.test-lib :as lib]))\n\n(set! (.-__EPUPP_PLAY_GIT_RESULT js/window) (lib/greeting \"PlayGit\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (set-ext-dep-cache! popup (make-ext-dep-cache git-raw-url pez-test-lib-code)))
        (js-await (.close popup)))

      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html") #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
          (js-await (activate-tab! popup tab-id))
          (js-await (click-play-button! popup "test/play_git_consumer.cljs"))
          (js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))

          (let [result (js-await (poll-for-window-property! test-page "__EPUPP_PLAY_GIT_RESULT" 5000))]
            (js-await (-> (expect result) (.toBe "Hello from pez.test-lib, PlayGit!"))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_popup_play_consumer_loads_ext_dep_from_gist_cache []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name "test/play_gist_consumer.cljs"
                            :match (str "http://localhost:" http-port "/*")
                            :inject [gist-raw-url]
                            :code "(ns test.play-gist-consumer\n  (:require [pez.test-lib :as lib]))\n\n(set! (.-__EPUPP_PLAY_GIST_RESULT js/window) (lib/greeting \"PlayGist\"))"})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (set-ext-dep-cache! popup (make-ext-dep-cache gist-raw-url pez-test-lib-code)))
        (js-await (.close popup)))

      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html") #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
          (js-await (activate-tab! popup tab-id))
          (js-await (click-play-button! popup "test/play_gist_consumer.cljs"))
          (js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))

          (let [result (js-await (poll-for-window-property! test-page "__EPUPP_PLAY_GIST_RESULT" 5000))]
            (js-await (-> (expect result) (.toBe "Hello from pez.test-lib, PlayGist!"))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

(.describe test "External Dependencies: popup play button with ext-dep cache"
           (fn []
             (test "popup play button: consumer loads ext-dep from raw.githubusercontent.com cache"
                   test_popup_play_consumer_loads_ext_dep_from_git_cache)

             (test "popup play button: consumer loads ext-dep from gist raw URL cache"
                   test_popup_play_consumer_loads_ext_dep_from_gist_cache)))
