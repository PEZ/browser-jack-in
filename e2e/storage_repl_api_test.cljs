(ns e2e.storage-repl-api-test
  "E2E tests for the epupp.storage REPL API.
   Exercises get/set!/remove!/keys/clear! via nREPL evaluation
   in the page's Scittle context. FS sync is not required."
  (:require ["@playwright/test" :refer [test expect chromium]]
            [fixtures.constants :refer [extension-path http-port ws-port-1]]
            [fixtures.browser :refer [get-extension-id]]
            [fixtures.messaging :refer [send-runtime-message]]
            [fixtures.events :refer [assert-no-errors!]]
            [fs-write-helpers :refer [eval-in-browser eval-async-and-poll!
                                      wait-for-script-tag]]))

(def !context (atom nil))
(def !ext-id (atom nil))
(def !ext-page (atom nil))

(defn- assert-success! [result error-prefix]
  (when-not (and result (.-success result))
    (throw (js/Error. (str error-prefix (.-error result))))))

(defn- ^:async get-chrome-storage-value [ext-page key]
  (let [result (js-await (send-runtime-message ext-page "e2e/get-storage" #js {:key key}))]
    (assert-success! result (str "e2e/get-storage failed for " key ": "))
    (.-value result)))

(defn- ^:async connect-test-tab! [ctx ext-id]
  (let [popup (js-await (.newPage ctx))]
    (js-await (.goto popup
                     (str "chrome-extension://" ext-id "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (reset! !ext-page popup)
    (let [find-result (js-await (send-runtime-message
                                  popup "e2e/find-tab-id"
                                  #js {:urlPattern "http://localhost:*/*"}))]
      (assert-success! find-result "Could not find test tab: ")
      (let [connect-result (js-await (send-runtime-message
                                       popup "connect-tab"
                                       #js {:tabId (.-tabId find-result)
                                            :wsPort ws-port-1}))]
        (assert-success! connect-result "Connection failed: ")))))

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
        (let [req-result (js-await (eval-in-browser "(require '[epupp.storage :as storage])"))]
          (assert-success! req-result "Failed to require epupp.storage: "))))))

(defn- ^:async teardown! []
  (when @!ext-page
    (try
      (js-await (assert-no-errors! @!ext-page))
      (catch :default _ nil)))
  (when @!context
    (js-await (.close @!context))))

(defn- ^:async eval-storage! [setup-code check-code]
  (js-await (eval-async-and-poll! setup-code check-code 5000)))

(def ^:private roundtrip-setup
  (str "(def !r (atom :pending))"
       "(defn ^:async do-it []"
       "  (await (epupp.storage/set! :ui/prefs {:ui/theme :theme/dark}))"
       "  (reset! !r (await (epupp.storage/get :ui/prefs))))"
       "(do-it)"
       ":setup-done"))

(defn- ^:async test_roundtrip_set_get_preserves_keywords []
  (let [result (js-await (eval-storage! roundtrip-setup "(pr-str @!r)"))]
    (-> (expect result) (.not.toBe "nil"))
    (-> (expect (.includes result ":theme/dark")) (.toBe true))
    (-> (expect (or (.includes result ":ui/theme")
                    (.includes result "#:ui")))
        (.toBe true))))

(def ^:private shared-key-setup
  (str "(def !ks (atom :pending))"
       "(defn ^:async do-it []"
       "  (await (epupp.storage/set! :e2e/shared {:count 7}))"
       "  (reset! !ks (await (epupp.storage/get :e2e/shared))))"
       "(do-it)"
       ":setup-done"))

(defn- ^:async test_keyword_and_string_keys_share_bucket []
  ;; set with keyword, get with string key form via separate eval
  (js-await (eval-storage!
             (str "(def !set-done (atom :pending))"
                  "(defn ^:async do-it []"
                  "  (await (epupp.storage/set! :e2e/shared {:count 7}))"
                  "  (reset! !set-done :done))"
                  "(do-it)"
                  ":setup-done")
             "(pr-str @!set-done)"))
  (let [set-result (js-await (eval-storage!
                              (str "(def !ks (atom :pending))"
                                   "(defn ^:async do-it []"
                                   "  (reset! !ks (await (epupp.storage/get \"e2e/shared\"))))"
                                   "(do-it)"
                                   ":setup-done")
                              "(pr-str @!ks)"))]
    (-> (expect set-result) (.toBe "{:count 7}"))))

(def ^:private missing-setup
  (str "(def !missing (atom :pending))"
       "(defn ^:async do-it []"
       "  (reset! !missing (await (epupp.storage/get :e2e/does-not-exist))))"
       "(do-it)"
       ":setup-done"))

(defn- ^:async test_get_missing_returns_nil []
  (let [result (js-await (eval-storage! missing-setup "(pr-str @!missing)"))]
    (-> (expect result) (.toBe "nil"))))

(def ^:private remove-setup
  (str "(def !rm (atom :pending))"
       "(defn ^:async do-it []"
       "  (await (epupp.storage/set! :e2e/remove-test \"x\"))"
       "  (await (epupp.storage/remove! :e2e/remove-test))"
       "  (reset! !rm (await (epupp.storage/remove! :e2e/remove-test))))"
       "(do-it)"
       ":setup-done"))

(defn- ^:async test_remove_is_idempotent []
  (let [result (js-await (eval-storage! remove-setup "(pr-str @!rm)"))]
    (-> (expect result) (.toBe "nil"))))

(def ^:private keys-setup
  (str "(def !keys-setup (atom :pending))"
       "(defn ^:async do-it []"
       "  (await (epupp.storage/clear!))"
       "  (await (epupp.storage/set! :e2e/keys-a 1))"
       "  (await (epupp.storage/set! :e2e/keys-b 2))"
       "  (reset! !keys-setup :done))"
       "(do-it)"
       ":setup-done"))

(def ^:private keys-result-setup
  (str "(def !keys-result (atom :pending))"
       "(defn ^:async do-it []"
       "  (reset! !keys-result (await (epupp.storage/keys))))"
       "(do-it)"
       ":setup-done"))

(defn- ^:async test_keys_returns_keyword_vector []
  (js-await (eval-storage! keys-setup "(pr-str @!keys-setup)"))
  (let [result (js-await (eval-storage! keys-result-setup "(pr-str @!keys-result)"))]
    (-> (expect result) (.not.toBe "nil"))
    (-> (expect result) (.not.toBe "[]"))
    (-> (expect (.includes result ":e2e/keys-a")) (.toBe true))
    (-> (expect (.includes result ":e2e/keys-b")) (.toBe true))))

(def ^:private clear-setup
  (str "(def !clear-setup (atom :pending))"
       "(defn ^:async do-it []"
       "  (await (epupp.storage/set! :e2e/clear-only \"keep-scripts\"))"
       "  (reset! !clear-setup :done))"
       "(do-it)"
       ":setup-done"))

(def ^:private clear-done-setup
  (str "(def !clear-done (atom :pending))"
       "(defn ^:async do-it []"
       "  (await (epupp.storage/clear!))"
       "  (reset! !clear-done :done))"
       "(do-it)"
       ":setup-done"))

(defn- ^:async test_clear_empties_user_bucket_only []
  (let [scripts-before (js-await (get-chrome-storage-value @!ext-page "scripts"))]
    (js-await (eval-storage! clear-setup "(pr-str @!clear-setup)"))
    (js-await (eval-storage! clear-done-setup "(pr-str @!clear-done)"))
    (let [kv-after (js-await (get-chrome-storage-value @!ext-page "epuppUserKv"))
          scripts-after (js-await (get-chrome-storage-value @!ext-page "scripts"))]
      (if kv-after
        (-> (expect (.-length (js/Object.keys kv-after))) (.toBe 0))
        (-> (expect kv-after) (.toBeFalsy)))
      (-> (expect scripts-after) (.toEqual scripts-before)))))

(.describe test "Storage REPL API"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (.setTimeout test 60000)
                           (js-await (setup!))))
             (.afterAll test (fn [] (teardown!)))

             (test "Storage REPL API: set!/get round-trip preserves keywords"
                   test_roundtrip_set_get_preserves_keywords)

             (test "Storage REPL API: keyword and string keys share bucket"
                   test_keyword_and_string_keys_share_bucket)

             (test "Storage REPL API: get missing key returns nil"
                   test_get_missing_returns_nil)

             (test "Storage REPL API: remove! is idempotent"
                   test_remove_is_idempotent)

             (test "Storage REPL API: keys returns keyword vector"
                   test_keys_returns_keyword_vector)

             (test "Storage REPL API: clear! empties user bucket only"
                   test_clear_empties_user_bucket_only)))
