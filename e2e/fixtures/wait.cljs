(ns fixtures.wait
  "Wait/poll helpers for reliable E2E testing without sleep."
  (:require ["@playwright/test" :refer [expect]]))

(defn ^:async poll-until
  "Generic polling helper. Calls pred-fn repeatedly until it returns truthy or timeout.
   Returns the truthy value on success, throws on timeout.

   pred-fn: Zero-arg function that returns falsy to continue polling, truthy to stop.
            Can be async (return a Promise).
   timeout-ms: Maximum time to wait before throwing.
   poll-interval: (optional) Milliseconds between polls, defaults to 20."
  ([pred-fn timeout-ms]
   (poll-until pred-fn timeout-ms 20))
  ([pred-fn timeout-ms poll-interval]
   (let [start (.now js/Date)]
     (loop []
       (let [result (js-await (pred-fn))]
         (cond
           result result
           (> (- (.now js/Date) start) timeout-ms) (throw (js/Error. "Timeout in poll-until"))
           :else (do
                   (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve poll-interval))))
                   (recur))))))))

(defn ^:async wait-for-script-count
  "Wait for the script list to have exactly n items."
  [page n]
  (js-await (-> (expect (.locator page ".script-item"))
                (.toHaveCount n #js {:timeout 3000}))))

(defn ^:async wait-for-save-status
  "Wait for system banner to appear with expected text (e.g., 'Created', 'Saved', 'Renamed')."
  [page text]
  (let [banner (.first (.locator page (str ".system-banner:has-text(\"" text "\")")))]
    (js-await (-> (expect banner)
                  (.toBeVisible #js {:timeout 5000})))))

(defn ^:async wait-for-checkbox-state
  "Wait for checkbox to reach expected checked state."
  [checkbox checked?]
  (if checked?
    (js-await (-> (expect checkbox) (.toBeChecked #js {:timeout 3000})))
    (js-await (-> (expect checkbox) (.not.toBeChecked #js {:timeout 3000})))))

(defn ^:async wait-for-panel-ready
  "Wait for panel to be ready after reload/navigation."
  [panel]
  (js-await (-> (expect (.locator panel "#code-area"))
                (.toBeVisible #js {:timeout 3000}))))

(defn ^:async wait-for-popup-ready
  "Wait for popup to be ready after reload/navigation."
  [popup]
  (js-await (-> (expect (.locator popup ".popup-header"))
                (.toBeVisible #js {:timeout 3000}))))

(defn ^:async wait-for-edit-hint
  "Wait for the edit hint message to appear in popup as a system banner."
  [popup]
  (js-await (-> (expect (.first (.locator popup ".system-banner")))
                (.toBeVisible #js {:timeout 3000}))))

(defn ^:async wait-for-scripts-loaded
  "Wait for panel to load scripts-list from storage.
   Uses data-e2e-scripts-count attribute on save-script-section."
  [panel expected-count]
  (let [save-section (.locator panel ".save-script-section")]
    (js-await (-> (expect save-section)
                  (.toHaveAttribute "data-e2e-scripts-count" (str expected-count))))))

(defn ^:async wait-for-property-value
  "Wait for a property-row to have a specific value."
  [panel property-name expected-value]
  (let [row (.locator panel (str "[data-e2e-property=\"" property-name "\"] .property-value"))]
    (js-await (-> (expect row)
                  (.toContainText expected-value #js {:timeout 3000})))))

(defn ^:async wait-for-editing-state
  "Wait for panel to be in editing or new-script state."
  [panel editing?]
  (let [save-section (.locator panel ".save-script-section")]
    (js-await (-> (expect save-section)
                  (.toHaveAttribute "data-e2e-editing" (str editing?) #js {:timeout 3000})))))

(defn ^:async wait-for-conflict-state
  "Wait for panel to be in name conflict state or not."
  [panel has-conflict?]
  (let [save-section (.locator panel ".save-script-section")]
    (js-await (-> (expect save-section)
                  (.toHaveAttribute "data-e2e-conflict" (str has-conflict?) #js {:timeout 3000})))))

(defn ^:async wait-for-banner-type
  "Wait for a system banner of a specific type to appear."
  [page banner-type]
  (let [banner (.locator page (str "[data-e2e-banner-type=\"" banner-type "\"]"))]
    (js-await (-> (expect (.first banner))
                  (.toBeVisible #js {:timeout 3000})))))

(defn ^:async wait-for-scittle-status
  "Wait for Scittle to reach a specific status."
  [panel status]
  (let [code-area (.locator panel ".code-input-area")]
    (js-await (-> (expect code-area)
                  (.toHaveAttribute "data-e2e-scittle-status" status #js {:timeout 5000})))))

(defn ^:async wait-for-connection-count
  "Wait for popup to show specific number of connections."
  [popup expected-count]
  (let [section (.locator popup "[data-e2e-section=\"repl-connect\"]")]
    (js-await (-> (expect section)
                  (.toHaveAttribute "data-e2e-connection-count" (str expected-count) #js {:timeout 5000})))))

(defn get-script-item
  "Get a script item locator by name using data-script-name attribute."
  [page script-name]
  (.locator page (str ".script-item[data-script-name=\"" script-name "\"]")))
