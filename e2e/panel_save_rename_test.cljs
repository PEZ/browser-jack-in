(ns e2e.panel-save-rename-test
  "E2E tests for DevTools panel rename functionality."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures :refer [launch-browser get-extension-id create-panel-page
                              clear-storage wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-edit-hint
                              assert-no-errors!]]
            [panel-save-helpers :as panel-save-helpers]))

;; =============================================================================
;; Panel User Journey: Rename Behavior
;; =============================================================================

(defn- ^:async test_rename_script_does_not_create_duplicate []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (js-await (panel-save-helpers/create-script-via-panel!
                 context ext-id {:clear-storage? true
                                 :code-opts {:name "My Cool Script"
                                             :match "*://example.com/*"
                                             :code "(println \"version 1\")"}
                                 :status "my_cool_script.cljs"}))
      (js-await (panel-save-helpers/inspect-script-from-popup!
                 context ext-id "my_cool_script.cljs"))
      (js-await (panel-save-helpers/rename-script-in-panel!
                 context ext-id {:expected-name "my_cool_script.cljs"
                                 :code-opts {:name "Renamed Script"
                                             :match "*://example.com/*"
                                             :code "(println \"version 1\")"}}))
      (js-await (panel-save-helpers/verify-popup-scripts!
                 context ext-id {:expected-count 1
                                 :visible ["renamed_script.cljs"]
                                 :not-visible ["my_cool_script.cljs"]}))
      (finally
        (js-await (.close context))))))

;; =============================================================================
;; Panel User Journey: Rename with Multiple Scripts
;; =============================================================================

(defn- ^:async test_rename_does_not_affect_other_scripts []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (js-await (panel-save-helpers/create-script-via-panel!
                 context ext-id {:clear-storage? true
                                 :code-opts {:name "First Script"
                                             :match "*://example.com/*"
                                             :code "(println \"script 1\")"}
                                 :status "first_script.cljs"}))
      (js-await (panel-save-helpers/create-script-via-panel!
                 context ext-id {:code-opts {:name "Second Script"
                                             :match "*://github.com/*"
                                             :code "(println \"script 2\")"}
                                 :status "second_script.cljs"}))
      (js-await (panel-save-helpers/verify-popup-scripts!
                 context ext-id {:expected-count 2}))
      (js-await (panel-save-helpers/inspect-script-from-popup!
                 context ext-id "first_script.cljs"))
      (js-await (panel-save-helpers/rename-script-in-panel!
                 context ext-id {:expected-name "first_script.cljs"
                                 :code-opts {:name "Renamed First Script"
                                             :match "*://example.com/*"
                                             :code "(println \"script 1\")"}}))
      (js-await (panel-save-helpers/verify-popup-scripts!
                 context ext-id {:expected-count 2
                                 :visible ["renamed_first_script.cljs"
                                           "second_script.cljs"]
                                 :exact-not-visible ["first_script.cljs"]}))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_multiple_renames_do_not_create_duplicates []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (js-await (panel-save-helpers/create-script-via-panel!
                 context ext-id {:clear-storage? true
                                 :code-opts {:name "Original Script"
                                             :match "*://example.com/*"
                                             :code "(println \"original\")"}
                                 :status "original_script.cljs"}))
      (js-await (panel-save-helpers/inspect-script-from-popup!
                 context ext-id "original_script.cljs"))
      (js-await (panel-save-helpers/rename-script-in-panel!
                 context ext-id {:expected-name "original_script.cljs"
                                 :code-opts {:name "First Rename"
                                             :match "*://example.com/*"
                                             :code "(println \"original\")"}}))
      (js-await (panel-save-helpers/verify-popup-scripts!
                 context ext-id {:expected-count 1
                                 :visible ["first_rename.cljs"]}))
      (js-await (panel-save-helpers/inspect-script-from-popup!
                 context ext-id "first_rename.cljs"))
      (js-await (panel-save-helpers/rename-script-in-panel!
                 context ext-id {:expected-name "first_rename.cljs"
                                 :code-opts {:name "Second Rename"
                                             :match "*://example.com/*"
                                             :code "(println \"original\")"}}))
      (js-await (panel-save-helpers/verify-popup-scripts!
                 context ext-id {:expected-count 1
                                 :visible ["second_rename.cljs"]
                                 :not-visible ["first_rename.cljs"
                                               "original_script.cljs"]}))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_panel_rename_triggers_popup_flash []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create initial script ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))
        (let [initial-code (panel-save-helpers/code-with-manifest {:name "Flash Rename Script"
                                                                   :match "*://example.com/*"
                                                                   :code "(println \"v1\")"})]
          (js-await (.fill textarea initial-code)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "flash_rename_script.cljs"))
        (js-await (.close panel)))

      ;; === PHASE 2: Open popup and ensure no flash class ===
      (let [popup (js-await (.newPage context))
            popup-url (str "chrome-extension://" ext-id "/popup.html")]
        (js-await (.goto popup popup-url #js {:timeout 1000}))
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"flash_rename_script.cljs\")")
              inspect-btn (.locator script-item "button.script-inspect")]
          (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 2000})))
          (js-await (-> (expect script-item) (.not.toHaveClass (js/RegExp. "script-item-fs-modified"))))
          (js-await (.click inspect-btn))
          (js-await (wait-for-edit-hint popup)))

        ;; === PHASE 3: Rename in panel while popup stays open ===
        (let [panel (js-await (create-panel-page context ext-id))
              textarea (.locator panel "#code-area")
              rename-btn (.locator panel "button.btn-rename")
              save-section (.locator panel ".save-script-section")
              name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
          (js-await (-> (expect name-field) (.toContainText "flash_rename_script.cljs")))
          (let [renamed-code (panel-save-helpers/code-with-manifest {:name "Flash Renamed Script"
                                                                     :match "*://example.com/*"
                                                                     :code "(println \"v1\")"})]
            (js-await (.fill textarea renamed-code)))
          (js-await (.click rename-btn))
          (js-await (wait-for-save-status panel "Renamed"))
          (js-await (.close panel)))

        ;; === PHASE 4: Popup should flash renamed item ===
        (let [renamed-item (.locator popup ".script-item:has-text(\"flash_renamed_script.cljs\")")]
          (js-await (-> (expect renamed-item)
                        (.toHaveClass (js/RegExp. "script-item-fs-modified")
                                      #js {:timeout 2000}))))

        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_rename_to_reserved_namespace_shows_error []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create a script to edit ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))
        (let [initial-code (panel-save-helpers/code-with-manifest {:name "Valid Script"
                                                                   :match "*://example.com/*"
                                                                   :code "(println \"valid\")"})]
          (js-await (.fill textarea initial-code)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "valid_script.cljs"))
        (js-await (.close panel)))

      ;; === PHASE 2: Edit script via popup inspect ===
      (let [popup (js-await (.newPage context))
            popup-url (str "chrome-extension://" ext-id "/popup.html")]
        (js-await (.goto popup popup-url #js {:timeout 1000}))
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"valid_script.cljs\")")
              inspect-btn (.locator script-item "button.script-inspect")]
          (js-await (.click inspect-btn))
          (js-await (wait-for-edit-hint popup)))
        (js-await (.close popup)))

      ;; === PHASE 3: Rename to epupp/ namespace - expect error hint ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
            name-hint (.locator save-section ".property-row:has(th:text('Name')) .field-hint")
            rename-btn (.locator panel "button.btn-rename")
            save-btn (.locator panel "button.btn-save")]
        ;; Wait for script to load
        (js-await (-> (expect name-field) (.toContainText "valid_script.cljs" #js {:timeout 500})))
        ;; Change name to reserved namespace
        (let [reserved-code (panel-save-helpers/code-with-manifest {:name "epupp/test.cljs"
                                                                    :match "*://example.com/*"
                                                                    :code "(println \"reserved\")"})]
          (js-await (.fill textarea reserved-code)))
        ;; Wait for name error hint to appear
        (js-await (-> (expect name-hint) (.toBeVisible #js {:timeout 500})))
        (js-await (-> (expect name-hint) (.toContainText "reserved" #js {:timeout 500})))
        ;; Buttons should be disabled
        (js-await (-> (expect rename-btn) (.toBeDisabled #js {:timeout 500})))
        (js-await (-> (expect save-btn) (.toBeDisabled #js {:timeout 500})))
        (js-await (assert-no-errors! panel))
        (js-await (.close panel)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_name_with_path_traversal_shows_error []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")
            save-section (.locator panel ".save-script-section")
            name-hint (.locator save-section ".property-row:has(th:text('Name')) .field-hint")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))

        ;; Test leading slash
        (let [code (panel-save-helpers/code-with-manifest {:name "/absolute/path.cljs"
                                                           :code "(println \"bad\")"})]
          (js-await (.fill textarea code)))
        (js-await (-> (expect name-hint) (.toBeVisible #js {:timeout 500})))
        (js-await (-> (expect save-btn) (.toBeDisabled #js {:timeout 500})))

        ;; Test ./ prefix
        (let [code (panel-save-helpers/code-with-manifest {:name "./relative.cljs"
                                                           :code "(println \"bad\")"})]
          (js-await (.fill textarea code)))
        (js-await (-> (expect name-hint) (.toBeVisible #js {:timeout 500})))
        (js-await (-> (expect save-btn) (.toBeDisabled #js {:timeout 500})))

        ;; Test ../ prefix
        (let [code (panel-save-helpers/code-with-manifest {:name "../parent.cljs"
                                                           :code "(println \"bad\")"})]
          (js-await (.fill textarea code)))
        (js-await (-> (expect name-hint) (.toBeVisible #js {:timeout 500})))
        (js-await (-> (expect save-btn) (.toBeDisabled #js {:timeout 500})))

        ;; Test ../ in middle
        (let [code (panel-save-helpers/code-with-manifest {:name "foo/../bar.cljs"
                                                           :code "(println \"bad\")"})]
          (js-await (.fill textarea code)))
        (js-await (-> (expect name-hint) (.toBeVisible #js {:timeout 500})))
        (js-await (-> (expect save-btn) (.toBeDisabled #js {:timeout 500})))

        (js-await (assert-no-errors! panel))
        (js-await (.close panel)))

      (finally
        (js-await (.close context))))))

(.describe test "Panel Save"
           (fn []
             (test "Panel Save: rename script does not create duplicate"
                   test_rename_script_does_not_create_duplicate)

             (test "Panel Save: rename does not affect other scripts"
                   test_rename_does_not_affect_other_scripts)

             (test "Panel Save: rename triggers popup flash"
                   test_panel_rename_triggers_popup_flash)

             (test "Panel Save: multiple renames do not create duplicates"
                   test_multiple_renames_do_not_create_duplicates)

             (test "Panel Save: rename to reserved namespace shows error and disables buttons"
                   test_rename_to_reserved_namespace_shows_error)

             (test "Panel Save: path traversal names show error and disable save"
                   test_name_with_path_traversal_shows_error)))
