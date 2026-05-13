(ns fs-ui-reactivity-helpers
  (:require ["@playwright/test" :refer [chromium]]
            ["path" :as path]
            [fixtures :refer [http-port ws-port-1
                              clear-fs-scripts send-runtime-message
                              get-extension-id find-tab-id connect-tab]]
            [fs-write-helpers :as fs-write-helpers
             :refer [wait-for-script-tag]]))

(def eval-in-browser fs-write-helpers/eval-in-browser)

(def sleep fs-write-helpers/sleep)

(def ^:private !context (atom nil))
(def ^:private !ext-id (atom nil))

(defn get-context []
  @!context)

(defn get-ext-id []
  @!ext-id)

(defn- extract-eval-result-value [check-result]
  (when (and (.-success check-result)
             (seq (.-values check-result)))
    (first (.-values check-result))))

(defn ^:async wait-for-eval-promise
  "Wait for a REPL evaluation result stored in an atom."
  [atom-name timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [value (extract-eval-result-value
                   (js-await (eval-in-browser (str "(pr-str @" atom-name ")"))))]
        (cond
          (and value (not= value ":pending")) value
          (> (- (.now js/Date) start) timeout-ms) (throw (js/Error. (str "Timeout waiting for " atom-name)))
          :else (do (js-await (sleep 20)) (recur)))))))

(defn ^:async setup-browser! []
  (let [extension-path (.resolve path "dist/chrome")
        ctx (js-await (.launchPersistentContext
                       chromium ""
                       #js {:headless false
                            :args #js ["--no-sandbox"
                                       "--allow-file-access-from-files"
                                       "--enable-features=ExtensionsManifestV3Only"
                                       (str "--disable-extensions-except=" extension-path)
                                       (str "--load-extension=" extension-path)]}))
        ext-id (js-await (get-extension-id ctx))
        test-page (js-await (.newPage ctx))
        _ (js-await (.goto test-page (str "http://localhost:" http-port "/basic.html")))
        _ (js-await (.waitForLoadState test-page "domcontentloaded"))
        bg-page (js-await (.newPage ctx))
        _ (js-await (.goto bg-page
                           (str "chrome-extension://" ext-id "/popup.html")
                           #js {:waitUntil "networkidle"}))
        _ (js-await (clear-fs-scripts bg-page))
        tab-id (js-await (find-tab-id bg-page "http://localhost:*/*"))]
    (reset! !context ctx)
    (reset! !ext-id ext-id)
    (js-await (connect-tab bg-page tab-id ws-port-1))
    (js-await (send-runtime-message bg-page "toggle-fs-sync" #js {:tabId tab-id :enabled true}))
    (js-await (.close bg-page))
    (js-await (wait-for-script-tag "scittle" 5000))))

(defn close-browser! []
  (when-let [ctx @!context]
    (.close ctx)
    (reset! !context nil)
    (reset! !ext-id nil)))
