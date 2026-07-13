(ns e2e.storage-wire-test
  "E2E tests for user storage wire protocol.
   Tests postMessage flow: page -> content bridge -> background -> epuppUserKv."
  (:require ["@playwright/test" :refer [test expect chromium]]
            [fixtures.constants :refer [extension-path http-port ws-port-1]]
            [fixtures.browser :refer [get-extension-id]]
            [fixtures.messaging :refer [send-runtime-message]]
            [fixtures.events :refer [assert-no-errors!]]))

(def !context (atom nil))
(def !ext-page (atom nil))
(def !test-page (atom nil))

(def ^:private storage-evaluate-fn
  (js* "function(opts) {
     return new Promise(function(resolve, reject) {
       var requestId = 'e2e-storage-' + Date.now() + '-' + Math.floor(Math.random() * 1e9);
       var responseType = opts.responseType;
       window.addEventListener('message', function handler(event) {
         var msg = event.data;
         if (msg && msg.source === 'epupp-bridge' && msg.type === responseType && msg.requestId === requestId) {
           window.removeEventListener('message', handler);
           resolve(msg);
         }
       });
       var msg = Object.assign({ source: 'epupp-page', requestId: requestId }, opts.payload || {});
       window.postMessage(msg, '*');
       setTimeout(function() { reject(new Error('storage wire timeout: ' + opts.payload.type)); }, 10000);
     });
   }"))

(defn ^:async send-storage-message
  [page response-type payload]
  (js-await (.evaluate page storage-evaluate-fn
                       #js {:responseType response-type
                            :payload payload})))

(defn- ^:async connect-to-test-tab! [ext-page find-result]
  (let [connect-result (js-await (send-runtime-message
                                  ext-page "connect-tab"
                                  #js {:tabId (.-tabId find-result)
                                       :wsPort ws-port-1}))
        _ (when-not (and connect-result (.-success connect-result))
            (throw (js/Error. (str "Connection failed: " (.-error connect-result)))))]
    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 1000))))))

(defn ^:async setup! []
  (let [ctx (js-await (.launchPersistentContext
                       chromium ""
                       #js {:headless false
                            :args #js ["--no-sandbox"
                                       (str "--disable-extensions-except=" extension-path)
                                       (str "--load-extension=" extension-path)]}))]
    (reset! !context ctx)
    (let [ext-id (js-await (get-extension-id ctx))
          page (js-await (.newPage ctx))]
      (js-await (.goto page (str "http://localhost:" http-port "/basic.html")))
      (js-await (.waitForLoadState page "domcontentloaded"))
      (reset! !test-page page)
      (let [ext-page (js-await (.newPage ctx))]
        (js-await (.goto ext-page
                         (str "chrome-extension://" ext-id "/popup.html")
                         #js {:waitUntil "networkidle"}))
        (reset! !ext-page ext-page)
        (let [find-result (js-await (send-runtime-message
                                     ext-page "e2e/find-tab-id"
                                     #js {:urlPattern "http://localhost:*/*"}))]
          (when-not (and find-result (.-success find-result))
            (throw (js/Error. (str "Could not find test tab: " (.-error find-result)))))
          (js-await (connect-to-test-tab! ext-page find-result)))))))

(defn ^:async teardown! []
  (when @!ext-page
    (try
      (js-await (assert-no-errors! @!ext-page))
      (catch :default _e nil)))
  (when @!context
    (js-await (.close @!context))))

(defn ^:async get-chrome-storage-value [ext-page key]
  (let [result (js-await (send-runtime-message ext-page "e2e/get-storage" #js {:key key}))]
    (when-not (and result (.-success result))
      (throw (js/Error. (str "e2e/get-storage failed for " key ": " (.-error result)))))
    (.-value result)))

(.describe test "Storage Wire"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (.setTimeout test 60000)
                           (js-await (setup!))))
             (.afterAll test (fn [] (teardown!)))

             (test "storage-set and storage-get round-trip"
                   (^:async fn []
                     (let [request-key "e2e/prefs"
                           edn-value "{:count 42}"
                           set-result (js-await (send-storage-message
                                                 @!test-page
                                                 "storage-set-response"
                                                 #js {:type "storage-set"
                                                      :key request-key
                                                      :value edn-value}))
                           _ (-> (expect (.-success set-result)) (.toBe true))
                           get-result (js-await (send-storage-message
                                                 @!test-page
                                                 "storage-get-response"
                                                 #js {:type "storage-get" :key request-key}))
                           kv-blob (js-await (get-chrome-storage-value @!ext-page "epuppUserKv"))]
                       (-> (expect (.-success get-result)) (.toBe true))
                       (-> (expect (.-value get-result)) (.toBe edn-value))
                       (-> (expect (aget kv-blob request-key)) (.toBe edn-value)))))

             (test "storage-keys lists stored keys"
                   (^:async fn []
                     (let [keys-result (js-await (send-storage-message
                                                  @!test-page
                                                  "storage-keys-response"
                                                  #js {:type "storage-keys"}))]
                       (-> (expect (.-success keys-result)) (.toBe true))
                       (-> (expect (.-keys keys-result)) (.toBeDefined))
                       (-> (expect (.includes (.-keys keys-result) "e2e/prefs")) (.toBe true)))))

             (test "storage-remove deletes key"
                   (^:async fn []
                     (let [request-key "e2e/prefs"
                           remove-result (js-await (send-storage-message
                                                    @!test-page
                                                    "storage-remove-response"
                                                    #js {:type "storage-remove" :key request-key}))
                           get-result (js-await (send-storage-message
                                                 @!test-page
                                                 "storage-get-response"
                                                 #js {:type "storage-get" :key request-key}))]
                       (-> (expect (.-success remove-result)) (.toBe true))
                       (-> (expect (.-success get-result)) (.toBe true))
                       (-> (expect (.-value get-result)) (.toBeFalsy)))))

             (test "storage-clear empties epuppUserKv but leaves scripts unchanged"
                   (^:async fn []
                     (let [scripts-before (js-await (get-chrome-storage-value @!ext-page "scripts"))
                           _ (js-await (send-storage-message
                                         @!test-page
                                         "storage-set-response"
                                         #js {:type "storage-set"
                                              :key "e2e/clear-test"
                                              :value "\"x\""}))
                           clear-result (js-await (send-storage-message
                                                    @!test-page
                                                    "storage-clear-response"
                                                    #js {:type "storage-clear"}))
                           kv-after (js-await (get-chrome-storage-value @!ext-page "epuppUserKv"))
                           scripts-after (js-await (get-chrome-storage-value @!ext-page "scripts"))]
                       (-> (expect (.-success clear-result)) (.toBe true))
                       (-> (expect (.-length (js/Object.keys (or kv-after #js {})))) (.toBe 0))
                       (-> (expect scripts-after) (.toEqual scripts-before)))))

             (test "requestId correlates response"
                   (^:async fn []
                     (let [result (js-await (send-storage-message
                                             @!test-page
                                             "storage-keys-response"
                                             #js {:type "storage-keys"}))]
                       (-> (expect (.-requestId result)) (.toBeDefined))
                       (-> (expect (.startsWith (.-requestId result) "e2e-storage-")) (.toBe true)))))))
