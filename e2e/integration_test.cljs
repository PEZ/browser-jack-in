(ns integration-test
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [fixtures :refer [launch-browser
                              get-extension-id
                              create-panel-page
                              create-popup-page
                              clear-storage
                              wait-for-save-status
                              wait-for-checkbox-state
                              wait-for-edit-hint
                              wait-for-panel-ready
                              assert-no-errors!]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\"")))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

;; =============================================================================
;; Integration: Script Lifecycle (panel -> popup -> panel -> popup)
;; =============================================================================

(defn- ^:async save-script-from-panel!
  "Create a panel page, fill code, save, and close."
  [context ext-id code]
  (let [panel (js-await (create-panel-page context ext-id))]
    (js-await (.fill (.locator panel "#code-area") code))
    (js-await (.click (.locator panel "button.btn-save")))
    (js-await (wait-for-save-status panel "Created"))
    (js-await (.close panel))))

(defn- ^:async verify-script-toggle-and-hint!
  "Verify script appears in popup, toggle enable/disable, check edit hint."
  [context ext-id]
  (let [popup (js-await (create-popup-page context ext-id))
        script-item (.locator popup ".script-item:has-text(\"lifecycle_test.cljs\")")
        checkbox (.locator script-item "input[type='checkbox']")
        inspect-btn (.locator script-item "button.script-inspect")
        hint (.locator popup ".system-banner")]
    (js-await (-> (expect script-item) (.toContainText "lifecycle_test.cljs")))
    (js-await (-> (expect script-item) (.toContainText "*://lifecycle.test/*")))
    (js-await (-> (expect checkbox) (.not.toBeChecked)))
    (js-await (.click checkbox))
    (js-await (wait-for-checkbox-state checkbox true))
    (js-await (.click checkbox))
    (js-await (wait-for-checkbox-state checkbox false))
    (js-await (-> (expect hint) (.toHaveCount 0)))
    (js-await (.click inspect-btn))
    (js-await (wait-for-edit-hint popup))
    (js-await (-> (expect hint) (.toContainText "Developer Tools")))
    (js-await (.close popup))))

(defn- ^:async test_script_lifecycle []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Start with clean storage
      (let [temp-page (js-await (.newPage context))]
        (js-await (.goto temp-page (str "chrome-extension://" ext-id "/popup.html")))
        (js-await (clear-storage temp-page))
        (js-await (.close temp-page)))

      ;; === PHASE 1: Save script from panel ===
      ;; Input: "Lifecycle Test" -> Normalized: "lifecycle_test.cljs"
      (js-await (save-script-from-panel!
                 context ext-id
                 (code-with-manifest {:name "Lifecycle Test"
                                      :match "*://lifecycle.test/*"
                                      :code "(println \"Original code\")"})))

      ;; === PHASE 2: Verify in popup, toggle, check edit hint ===
      (js-await (verify-script-toggle-and-hint! context ext-id))

      ;; === PHASE 3: Edit script - panel receives it ===
      ;; Click inspect in popup first, then open panel (which reads editingScript on init)
      (let [popup (js-await (create-popup-page context ext-id))
            script-item (.locator popup ".script-item:has-text(\"lifecycle_test.cljs\")")
            inspect-btn (.locator script-item "button.script-inspect")]
        (js-await (.click inspect-btn))
        (js-await (wait-for-edit-hint popup))
        (js-await (.close popup)))

      ;; Now open panel - it will read editingScript on init
      (let [panel (js-await (create-panel-page context ext-id))
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
        (js-await (-> (expect name-field) (.toContainText "lifecycle_test.cljs")))
        (let [updated-code (code-with-manifest {:name "lifecycle_test.cljs"
                                                :match "*://lifecycle.test/*"
                                                :code "(println \"Updated code\")"})]
          (js-await (.fill (.locator panel "#code-area") updated-code)))
        (js-await (.click (.locator panel "button.btn-save")))
        (js-await (wait-for-save-status panel "Saved"))
        (js-await (.close panel)))

      ;; === PHASE 4: Delete script ===
      (let [popup (js-await (create-popup-page context ext-id))
            script-item (.locator popup ".script-item:has-text(\"lifecycle_test.cljs\")")
            delete-btn (.locator script-item "button.script-delete")]
        (.on popup "dialog" (fn [dialog] (.accept dialog)))
        (js-await (.click delete-btn))
        (js-await (-> (expect script-item) (.toHaveCount 0)))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_run_at_badges []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Start with clean storage
      (let [temp-page (js-await (.newPage context))]
        (js-await (.goto temp-page (str "chrome-extension://" ext-id "/popup.html")))
        (js-await (clear-storage temp-page))
        (js-await (.close temp-page)))

      ;; === Create scripts with different timing ===
      (js-await (save-script-from-panel!
                 context ext-id
                 (code-with-manifest {:name "Early Script"
                                      :match "*://early.test/*"
                                      :run-at "document-start"
                                      :code "(println \"early script\")"})))
      (js-await (save-script-from-panel!
                 context ext-id
                 (code-with-manifest {:name "DOM Ready Script"
                                      :match "*://domready.test/*"
                                      :run-at "document-end"
                                      :code "(println \"dom ready script\")"})))
      (js-await (save-script-from-panel!
                 context ext-id
                 (code-with-manifest {:name "Normal Script"
                                      :match "*://normal.test/*"
                                      :code "(println \"normal script\")"})))

      ;; === Verify timing badges in popup ===
      (let [popup (js-await (create-popup-page context ext-id))
            early-item (.locator popup ".script-item:has-text(\"early_script.cljs\")")
            domready-item (.locator popup ".script-item:has-text(\"dom_ready_script.cljs\")")
            normal-item (.locator popup ".script-item:has-text(\"normal_script.cljs\")")]

        ;; Document-start script has a timing badge with timing-specific title text.
        (let [badge (.locator early-item ".run-at-badge")]
          (js-await (-> (expect badge) (.toBeVisible)))
          (js-await (-> (expect (.locator badge "svg")) (.toBeVisible)))
          (js-await (-> (expect badge) (.toHaveAttribute "title" #"document-start"))))

        ;; Document-end script has a timing badge with timing-specific title text.
        (let [badge (.locator domready-item ".run-at-badge")]
          (js-await (-> (expect badge) (.toBeVisible)))
          (js-await (-> (expect (.locator badge "svg")) (.toBeVisible)))
          (js-await (-> (expect badge) (.toHaveAttribute "title" #"document-end"))))

        ;; Normal script has NO badge (document-idle is default)
        (let [badge (.locator normal-item ".run-at-badge")]
          (js-await (-> (expect badge) (.toHaveCount 0))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(test "Integration: script lifecycle - save, view, toggle, edit, delete"
      test_script_lifecycle)

(test "Integration: run-at badges display correctly for script timing"
      test_run_at_badges)

;; =============================================================================
;; Integration: Run-at Badge Display
;; =============================================================================


