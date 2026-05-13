(ns e2e.ext-dep-full-cycle-test
  "E2E tests for the full external dependency fetch cycle.
   Unlike ext_dep_test.cljs which pre-seeds the cache, these tests verify
   the real network fetch path: save script -> storage change -> fetch -> cache -> inject."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser get-extension-id create-popup-page
                              create-panel-page create-panel-page-for-tab
                              wait-for-event wait-for-save-status
                              wait-for-popup-ready get-script-item
                              wait-for-checkbox-state send-runtime-message
                              find-tab-id http-port
                              assert-no-errors! clear-test-events!]]))

;; =============================================================================
;; Constants - SHA-pinned, permanently available URLs
;; =============================================================================

(def git-raw-url
  "https://raw.githubusercontent.com/PEZ/pez-my-epupp-hq/3dbf6393916cd4e384826b093ab6e9a96b1793f9/userscripts/pez/test_lib.cljs")

(def gist-raw-url
  "https://gist.githubusercontent.com/PEZ/f7059fe7328bb25ee3f459d7457dc2a8/raw/50b3bed5fff509c2d86c2cbb4d3fa5f0f47c23ed/pez_test_lib.cljs")

(def cache-poll-timeout
  "Network fetch in Docker can be slow. 20s timeout for cache population."
  20000)
;; =============================================================================
;; Helpers
;; =============================================================================

(defn- code-with-manifest
  [{:keys [name match inject code]
    :or {code "(println \"Test script\")"}}]
  (let [inject-str (when inject
                     (str "[" (str/join " " (map #(str "\"" % "\"") inject)) "]"))
        meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     inject (conj (str ":epupp/inject " inject-str)))
        meta-block (str "{" (str/join "\n " meta-parts) "}\n\n")]
    (str meta-block code)))

(defn- ^:async save-script-via-panel
  [context ext-id code]
  (let [panel (js-await (create-panel-page context ext-id))]
    (js-await (.fill (.locator panel "#code-area") code))
    (js-await (.click (.locator panel "button.btn-save")))
    (js-await (wait-for-save-status panel "Created"))
    (js-await (.close panel))))

(defn- ^:async enable-script-via-popup
  [context ext-id script-name]
  (let [popup (js-await (create-popup-page context ext-id))]
    (js-await (wait-for-popup-ready popup))
    (let [script-item (get-script-item popup script-name)
          checkbox (.locator script-item "input[type='checkbox']")]
      (js-await (.click checkbox))
      (js-await (wait-for-checkbox-state checkbox true)))
    (js-await (.close popup))))

(defn- cache-timeout-message
  [expected-url timeout-ms cache]
  (str "Timeout (" timeout-ms "ms) waiting for extDepCache to contain: "
       expected-url
       "\nCache keys: "
       (if cache
         (js/JSON.stringify (.keys js/Object cache))
         "null/undefined")))

(defn- ^:async poll-ext-dep-cache
  "Poll extDepCache storage until the expected URL key exists.
   Returns the cache entry. Includes diagnostic info on timeout."
  [ext-page expected-url timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [result (js-await (send-runtime-message ext-page "e2e/get-storage"
                                                   #js {:key "extDepCache"}))
            cache (when (and result (.-success result)) (.-value result))
            entry (when cache (aget cache expected-url))]
        (cond
          entry
          entry

          (> (- (.now js/Date) start) timeout-ms)
          (throw (js/Error. (cache-timeout-message expected-url timeout-ms cache)))

          :else
          (do
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 200))))
            (recur)))))))
;; =============================================================================
;; Test: Full cycle - git raw URL (auto-run)
;; =============================================================================

(defn- ^:async poll-window-property
  "Poll a window property on page until it becomes non-nil.
   Uses js* to avoid Squint control-flow in browser context."
  [page prop-name timeout-ms]
  (let [start (.now js/Date)
        eval-fn (js* "function(p) { return window[p]; }")]
    (loop []
      (let [result (js-await (.evaluate page eval-fn prop-name))]
        (cond
          (some? result) result
          (> (- (.now js/Date) start) timeout-ms)
          (throw (js/Error. (str "Timeout waiting for " prop-name)))
          :else
          (do
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
            (recur)))))))

(defn- ^:async test-auto-run-cycle
  "Shared auto-run test cycle parameterized by ext-dep URL and script details."
  [{:keys [dep-url script-name ns-name window-prop greeting-arg]}]
  (.setTimeout test 60000)
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name script-name
                            :match (str "http://localhost:" http-port "/*")
                            :inject [dep-url]
                            :code (str "(ns " ns-name "\n"
                                       "  (:require [pez.test-lib :as lib]))\n\n"
                                       "(set! (.-" window-prop " js/window)\n"
                                       "      (lib/greeting \"" greeting-arg "\"))")})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id script-name))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (poll-ext-dep-cache popup dep-url cache-poll-timeout))
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      (let [page (js-await (.newPage context))]
        (js-await (.goto page (str "http://localhost:" http-port "/basic.html")
                         #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))]
          (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 10000))

          (let [result (js-await (poll-window-property page window-prop 5000))]
            (js-await (-> (expect result)
                          (.toBe (str "Hello from pez.test-lib, " greeting-arg "!")))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_full_cycle_git_auto_run []
  (js-await (test-auto-run-cycle
             {:dep-url git-raw-url
              :script-name "test/fc_git_auto.cljs"
              :ns-name "test.fc-git-auto"
              :window-prop "__EPUPP_FULL_CYCLE_GIT_RESULT"
              :greeting-arg "FullCycleGit"})))

;; =============================================================================
;; Test: Full cycle - gist raw URL (auto-run)
;; =============================================================================

(defn- ^:async test_full_cycle_gist_auto_run []
  (js-await (test-auto-run-cycle
             {:dep-url gist-raw-url
              :script-name "test/fc_gist_auto.cljs"
              :ns-name "test.fc-gist-auto"
              :window-prop "__EPUPP_FULL_CYCLE_GIST_RESULT"
              :greeting-arg "FullCycleGist"})))
;; =============================================================================
;; Test: Full cycle - git raw URL (popup play button)
;; =============================================================================

(defn- ^:async test_full_cycle_git_popup_play []
  (.setTimeout test 60000)
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name "test/fc_git_play.cljs"
                            :match (str "http://localhost:" http-port "/*")
                            :inject [git-raw-url]
                            :code (str "(ns test.fc-git-play\n"
                                       "  (:require [pez.test-lib :as lib]))\n\n"
                                       "(set! (.-__EPUPP_FC_PLAY_GIT_RESULT js/window)\n"
                                       "      (lib/greeting \"PlayGit\"))")})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (poll-ext-dep-cache popup git-raw-url cache-poll-timeout))
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")
                         #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
          (js-await (.evaluate popup
                               (fn [target-tab-id]
                                 (js/Promise.
                                  (fn [resolve]
                                    (js/chrome.tabs.update target-tab-id #js {:active true}
                                                           (fn [] (resolve true))))))
                               tab-id))

          (let [item (get-script-item popup "test/fc_git_play.cljs")
                run-btn (.locator item "button.script-run")]
            (js-await (-> (expect run-btn) (.toBeVisible #js {:timeout 500})))
            (js-await (.click run-btn)))

          (js-await (wait-for-event popup "SCRIPT_INJECTED" 5000))

          (let [result (js-await (poll-window-property test-page "__EPUPP_FC_PLAY_GIT_RESULT" 5000))]
            (js-await (-> (expect result)
                          (.toBe "Hello from pez.test-lib, PlayGit!"))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test: Full cycle - panel eval fetches ext dep on demand
;; =============================================================================

(defn- ^:async clear-cache-and-find-tab
  "Clear extDepCache and return the tab-id for the test page."
  [context ext-id]
  (let [popup (js-await (create-popup-page context ext-id))
        _ (js-await (wait-for-popup-ready popup))
        clear-result (js-await (send-runtime-message popup "e2e/set-storage"
                                                     #js {:key "extDepCache"
                                                          :value #js {}}))
        _ (js-await (-> (expect (.-success clear-result)) (.toBe true)))
        tab-id (js-await (find-tab-id popup (str "http://localhost:" http-port "/*")))]
    (js-await (.close popup))
    tab-id))

(defn- ^:async poll-scittle-eval
  "Poll page by evaluating a Scittle expression until it returns non-nil."
  [page expr timeout-ms]
  (let [start (.now js/Date)
        eval-fn (js* "function(e) { try { return scittle.core.eval_string(e); } catch(_) { return null; } }")]
    (loop []
      (let [result (js-await (.evaluate page eval-fn expr))]
        (cond
          (some? result) result
          (> (- (.now js/Date) start) timeout-ms)
          (throw (js/Error. (str "Timeout: expression not available on page: " expr)))
          :else
          (do
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
            (recur)))))))

(defn- ^:async test_full_cycle_panel_eval_fetches_ext_dep_on_demand []
  (.setTimeout test 60000)
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [test-page (js-await (.newPage context))]
        (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")
                         #js {:timeout 5000}))
        (js-await (-> (expect (.locator test-page "#test-marker"))
                      (.toContainText "ready")))

        (let [tab-id (js-await (clear-cache-and-find-tab context ext-id))
              panel (js-await (create-panel-page-for-tab context ext-id tab-id))
              consumer-code (str "{:epupp/script-name \"test/panel_fetch_on_demand.cljs\"\n"
                                 " :epupp/inject [\"" git-raw-url "\"]}\n\n"
                                 "(ns test.panel-fetch-on-demand\n"
                                 "  (:require [pez.test-lib :as lib]))\n\n"
                                 "(set! (.-__EPUPP_PANEL_FETCH_ON_DEMAND_RESULT js/window)\n"
                                 "      (lib/greeting \"PanelFetchOnDemand\"))")]
          (js-await (.fill (.locator panel "#code-area") consumer-code))
          (js-await (.click (.locator panel "button.btn-eval")))

          (let [result (js-await (poll-scittle-eval
                                  test-page
                                  "(pez.test-lib/greeting \"test\")"
                                  5000))]
            (js-await (-> (expect result)
                          (.toBe "Hello from pez.test-lib, test!"))))

          (let [cache-result (js-await (send-runtime-message panel "e2e/get-storage"
                                                             #js {:key "extDepCache"}))
                cache (when (and cache-result (.-success cache-result)) (.-value cache-result))]
            (js-await (-> (expect (aget cache git-raw-url))
                          (.toBeTruthy))))

          (js-await (assert-no-errors! panel))
          (js-await (.close panel)))
        (js-await (.close test-page)))

      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Test Registration
;; =============================================================================

(.describe test "External Dependencies: full fetch cycle (network)"
           (fn []
             (test "Full cycle git URL: save triggers fetch, auto-run injects and executes"
                   test_full_cycle_git_auto_run)

             (test "Full cycle gist URL: save triggers fetch, auto-run injects and executes"
                   test_full_cycle_gist_auto_run)

             (test "Full cycle git URL: save triggers fetch, popup play injects and executes"
               test_full_cycle_git_popup_play)

             (test "Full cycle git URL: panel eval fetches uncached ext dep on demand"
               test_full_cycle_panel_eval_fetches_ext_dep_on_demand)))
