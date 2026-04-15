(ns e2e.capture-element-test
  "E2E tests for the capture-element feature.
   Tests the full message flow: page postMessage -> content bridge -> background
   -> captureVisibleTab (+ optional crop) -> response back to page."
  (:require ["@playwright/test" :refer [test expect chromium]]
            [fixtures :refer [extension-path get-extension-id http-port ws-port-1
                              send-runtime-message assert-no-errors!]]))

(def !context (atom nil))
(def !ext-page (atom nil))
(def !test-page (atom nil))

(def ^:private capture-evaluate-fn
  "Pure JS function for page.evaluate - must not reference squint_core.
   Takes an options object, sends capture-element postMessage, returns response Promise."
  (js* "function(options) {
     return new Promise(function(resolve, reject) {
       var requestId = 'e2e-capture-' + Date.now() + '-' + Math.floor(Math.random() * 1e9);
       window.addEventListener('message', function handler(event) {
         var msg = event.data;
         if (msg && msg.source === 'epupp-bridge'
             && msg.type === 'capture-element-response'
             && msg.requestId === requestId) {
           window.removeEventListener('message', handler);
           resolve({ success: msg.success, dataUrl: msg.dataUrl, error: msg.error });
         }
       });
       var msg = Object.assign(
         { source: 'epupp-page', type: 'capture-element', requestId: requestId },
         options || {}
       );
       window.postMessage(msg, '*');
       setTimeout(function() { reject(new Error('capture-element timeout')); }, 15000);
     });
   }"))

(defn ^:async send-capture-message
  "Send capture-element message from page context via postMessage,
   wait for response from content bridge."
  [page opts]
  (js-await (.evaluate page capture-evaluate-fn (or opts #js {}))))

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
          (let [connect-result (js-await (send-runtime-message
                                          ext-page "connect-tab"
                                          #js {:tabId (.-tabId find-result)
                                               :wsPort ws-port-1}))]
            (when-not (and connect-result (.-success connect-result))
              (throw (js/Error. (str "Connection failed: " (.-error connect-result)))))
            (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 1000))))))))))

(defn ^:async teardown! []
  (when @!ext-page
    (try
      (js-await (assert-no-errors! @!ext-page))
      (catch :default _e nil)))
  (when @!context
    (js-await (.close @!context))))

(.describe test "Capture Element"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (.setTimeout test 60000)
                           (js-await (setup!))))
             (.afterAll test (fn [] (teardown!)))

             (test "capture-visible: returns PNG data URL"
                   (^:async fn []
                     (let [result (js-await (send-capture-message
                                             @!test-page
                                             #js {:format "png"}))]
                       (-> (expect (.-success result)) (.toBe true))
                       (-> (expect (.-dataUrl result)) (.toBeDefined))
                       (-> (expect (.startsWith (.-dataUrl result) "data:image/png;base64,"))
                           (.toBe true))
                       (-> (expect (.-length (.-dataUrl result))) (.toBeGreaterThan 100)))))

             (test "capture-visible: JPEG format"
                   (^:async fn []
                     (let [result (js-await (send-capture-message
                                             @!test-page
                                             #js {:format "jpeg" :quality 80}))]
                       (-> (expect (.-error result)) (.toBeUndefined))
                       (-> (expect (.-success result)) (.toBe true))
                       (-> (expect (.startsWith (.-dataUrl result) "data:image/jpeg;base64,"))
                           (.toBe true)))))

             (test "capture with rect: returns cropped PNG"
                   (^:async fn []
                     (let [result (js-await (send-capture-message
                                             @!test-page
                                             #js {:rect #js {:x 0 :y 0 :width 100 :height 50}
                                                  :dpr 1
                                                  :format "png"}))]
                       (-> (expect (.-success result)) (.toBe true))
                       (-> (expect (.startsWith (.-dataUrl result) "data:image/png;base64,"))
                           (.toBe true))
                       (-> (expect (.-length (.-dataUrl result))) (.toBeGreaterThan 50)))))

             (test "capture with rect: JPEG cropped"
                   (^:async fn []
                     (let [result (js-await (send-capture-message
                                             @!test-page
                                             #js {:rect #js {:x 10 :y 10 :width 200 :height 100}
                                                  :dpr 1
                                                  :format "jpeg"
                                                  :quality 80}))]
                       (-> (expect (.-error result)) (.toBeUndefined))
                       (-> (expect (.-success result)) (.toBe true))
                       (-> (expect (.startsWith (.-dataUrl result) "data:image/jpeg;base64,"))
                           (.toBe true)))))))
