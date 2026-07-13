(ns e2e.storage-userscript-api-test
  "E2E: epupp.storage via userscript inject (no REPL required)."
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures.browser :refer [launch-browser get-extension-id]]
            [fixtures.pages :refer [create-popup-page create-panel-page]]
            [fixtures.wait :refer [wait-for-save-status wait-for-popup-ready get-script-item
                                   wait-for-checkbox-state]]
            [fixtures.events :refer [wait-for-event assert-no-errors! clear-test-events!]]))

(defn- code-with-manifest
  [{:keys [name match description inject code]
    :or {code "(println \"Test script\")"}}]
  (let [inject-str (when inject
                     (str "[" (str/join " " (map #(str "\"" % "\"") inject)) "]"))
        meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     inject (conj (str ":epupp/inject " inject-str)))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

(defn- ^:async save-script-via-panel [context ext-id code]
  (let [panel (js-await (create-panel-page context ext-id))]
    (js-await (.fill (.locator panel "#code-area") code))
    (js-await (.click (.locator panel "button.btn-save")))
    (js-await (wait-for-save-status panel "Created"))
    (js-await (.close panel))))

(defn- ^:async enable-script-via-popup [context ext-id script-name]
  (let [popup (js-await (create-popup-page context ext-id))]
    (js-await (wait-for-popup-ready popup))
    (let [script-item (get-script-item popup script-name)
          checkbox (.locator script-item "input[type='checkbox']")]
      (js-await (.click checkbox))
      (js-await (wait-for-checkbox-state checkbox true)))
    (js-await (.close popup))))

(defn- ^:async poll-for-window-var [page expr-fn timeout-ms]
  (loop [start (.now js/Date)]
    (let [result (js-await (.evaluate page expr-fn))]
      (cond
        (some? result) result
        (> (- (.now js/Date) start) timeout-ms)
        (throw (js/Error. (str "Timeout after " timeout-ms "ms polling page")))
        :else
        (do
          (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
          (recur start))))))

(defn- ^:async test_userscript_storage_roundtrip_without_repl []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [consumer-code (code-with-manifest
                           {:name "test/storage_consumer.cljs"
                            :match "http://localhost:18080/*"
                            :inject ["epupp://epupp/storage.cljs"]
                            :code (str "(ns test.storage-consumer\n"
                                       "  (:require [epupp.storage :as storage]))\n\n"
                                       "(defn ^:async run []\n"
                                       "  (await (storage/set! :e2e/userscript {:ui/theme :theme/dark}))\n"
                                       "  (let [v (await (storage/get :e2e/userscript))]\n"
                                       "    (set! (.-__EPUPP_STORAGE_USERSCRIPT_RESULT js/window)\n"
                                       "          (pr-str v))))\n\n"
                                       "(run)")})]
        (js-await (save-script-via-panel context ext-id consumer-code)))

      (js-await (enable-script-via-popup context ext-id "test/storage_consumer.cljs"))

      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (clear-test-events! popup))
        (js-await (.close popup)))

      (let [page (js-await (.newPage context))]
        (js-await (.goto page "http://localhost:18080/basic.html" #js {:timeout 5000}))
        (js-await (-> (expect (.locator page "#test-marker"))
                      (.toContainText "ready")))

        (let [popup (js-await (create-popup-page context ext-id))
              event (js-await (wait-for-event popup "EXECUTE_PLAN_COMPLETE" 15000))]
          (js-await (-> (expect (.-event event)) (.toBe "EXECUTE_PLAN_COMPLETE")))

          (let [result (js-await (poll-for-window-var
                                  page
                                  (fn [] js/window.__EPUPP_STORAGE_USERSCRIPT_RESULT)
                                  8000))]
            (js-await (-> (expect result) (.not.toBe "nil")))
            (js-await (-> (expect (.includes result ":theme/dark")) (.toBe true)))
            (js-await (-> (expect (or (.includes result ":ui/theme")
                                      (.includes result "#:ui")))
                          (.toBe true))))

          (js-await (assert-no-errors! popup))
          (js-await (.close popup)))
        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Storage Userscript API"
           (fn []
             (test "userscript injects epupp.storage and round-trips keywords without REPL"
                   test_userscript_storage_roundtrip_without_repl)))
