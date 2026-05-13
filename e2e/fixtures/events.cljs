(ns fixtures.events
  "Test event helpers for structured E2E testing via background worker logging."
  (:require ["@playwright/test" :refer [expect]]
            [fixtures.messaging :refer [send-runtime-message]]))

(defn ^:async clear-test-events!
  "Clear test events in storage. Call at start of each test.
   Must be called from an extension page (popup/panel)."
  [ext-page]
  (js-await
   (.evaluate ext-page
              "() => new Promise(resolve => {
                 chrome.storage.local.set({ 'test-events': [] }, resolve);
               })")))

(defn ^:async get-test-events-via-message
  "Read test events from storage via background worker message.
   Works from any extension page (popup, panel, or background page).
   Returns JS array of event objects."
  [ext-page]
  (let [result (js-await (send-runtime-message ext-page "e2e/get-test-events" nil))]
    (if (and result (.-success result))
      (.-events result)
      (array))))

(defn ^:async get-test-events
  "Read test events from storage via the dev log button.

   Workaround for Playwright limitation: page.evaluate returns undefined on extension pages.
   Instead, we click the 'Dump Dev Log' button which console.logs the events,
   then capture the console output.

   Returns JS array of event objects with .event, .ts, .perf, .data properties."
  [ext-page]
  (let [marker "__EPUPP_DEV_LOG__"
        result-promise
        (js/Promise.
         (fn [resolve]
           (let [timeout-id (js/setTimeout (fn [] (resolve (array))) 5000)]
             (.on ext-page "console"
                  (fn [msg]
                    (when (= "log" (.type msg))
                      (let [text (.text msg)]
                        (when (.startsWith text marker)
                          (js/clearTimeout timeout-id)
                          (let [json-str (.trim (.substring text (.-length marker)))]
                            (try
                              (resolve (js/JSON.parse json-str))
                              (catch :default e
                                (js/console.error "Failed to parse dev log:" e)
                                (resolve (array)))))))))))))]
    (let [dev-log-btn (.locator ext-page ".dev-log-btn")]
      (js-await (-> (expect dev-log-btn) (.toBeVisible (js-obj "timeout" 5000))))
      (js-await (.click dev-log-btn)))
    (js-await result-promise)))

(defn ^:async wait-for-event
  "Poll storage until event appears or timeout.
   ext-page: popup or panel page for storage access
   event-name: SCREAMING_SNAKE event name (string)
   timeout-ms: max wait time"
  [ext-page event-name timeout-ms]
  (let [start (.now js/Date)]
    (loop []
      (let [events (js-await (get-test-events-via-message ext-page))
            found (first (filter #(= (.-event %) event-name) events))]
        (cond
          found found
          (> (- (.now js/Date) start) timeout-ms)
          (throw (js/Error. (str "Timeout waiting for event: " event-name
                                 ". Events so far: " (js/JSON.stringify (clj->js (map #(.-event %) events))))))
          :else (do
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 20))))
                  (recur)))))))

(defn ^:async assert-no-new-event-within
  "Assert that no NEW event with given name occurs within timeout-ms.
   Polls rapidly (every 20ms) and fails immediately if count increases.

   initial-count: The number of events of this type that existed before the action
   Use for tests that verify something should NOT happen."
  [ext-page event-name initial-count timeout-ms]
  (let [start (.now js/Date)
        poll-interval 20]
    (loop []
      (let [events (js-await (get-test-events ext-page))
            current-count (.-length (.filter events (fn [e] (= (.-event e) event-name))))]
        (cond
          (> current-count initial-count)
          (throw (js/Error. (str "Unexpected new event occurred: " event-name
                                 " (count went from " initial-count " to " current-count ")"
                                 " after " (- (.now js/Date) start) "ms")))
          (> (- (.now js/Date) start) timeout-ms) true
          :else (do
                  (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve poll-interval))))
                  (recur)))))))

(defn ^:async assert-no-errors!
  "Assert that no UNCAUGHT_ERROR or UNHANDLED_REJECTION events were logged.
   Call this at the end of each test before closing the context.
   Uses message API so works from any extension page (popup/panel)."
  [ext-page]
  (let [events (js-await (get-test-events-via-message ext-page))
        errors (.filter events
                        (fn [e]
                          (or (= (.-event e) "UNCAUGHT_ERROR")
                              (= (.-event e) "UNHANDLED_REJECTION"))))]
    (when (pos? (.-length errors))
      (js/console.error "Found errors:" (js/JSON.stringify errors nil 2)))
    (js-await (-> (expect (.-length errors))
                  (.toBe 0)))))
