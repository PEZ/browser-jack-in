(ns e2e.panel-save-conflict-test
  "E2E tests for panel name conflict detection and overwrite functionality."
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures.constants :refer [builtin-script-count]]
            [fixtures.browser :refer [launch-browser get-extension-id]]
            [fixtures.pages :refer [create-panel-page]]
            [fixtures.messaging :refer [clear-storage]]
            [fixtures.wait :refer [wait-for-panel-ready wait-for-popup-ready
                                   wait-for-save-status wait-for-script-count
                                   wait-for-edit-hint wait-for-scripts-loaded]]
            [fixtures.events :refer [assert-no-errors!]]
            [panel-save-helpers :as panel-save-helpers]))

;; =============================================================================
;; Conflict Detection Tests
;; =============================================================================

(defn- ^:async save-script-in-panel!
  "Opens a panel page, optionally clears storage, fills manifest code, saves, then closes.
   opts must include :expected-filename. Optional :clear-first? (default false)."
  [context ext-id opts]
  (let [panel (js-await (create-panel-page context ext-id))
        textarea (.locator panel "#code-area")
        save-btn (.locator panel "button.btn-save")]
    (when (:clear-first? opts)
      (js-await (clear-storage panel))
      (js-await (.reload panel)))
    (js-await (wait-for-panel-ready panel))
    (js-await (.fill textarea (panel-save-helpers/code-with-manifest (dissoc opts :expected-filename :clear-first?))))
    (js-await (.click save-btn))
    (js-await (wait-for-save-status panel (:expected-filename opts)))
    (js-await (.close panel))))

(defn- ^:async open-popup-and-inspect!
  "Opens popup, verifies script count, clicks inspect on the named script, then closes."
  [context ext-id expected-count inspect-script-name]
  (let [popup (js-await (.newPage context))
        popup-url (str "chrome-extension://" ext-id "/popup.html")]
    (js-await (.goto popup popup-url #js {:timeout 1000}))
    (js-await (wait-for-popup-ready popup))
    (js-await (wait-for-script-count popup expected-count))
    (let [script-item (.locator popup (str ".script-item:has-text(\"" inspect-script-name "\")"))
          inspect-btn (.locator script-item "button.script-inspect")]
      (js-await (.click inspect-btn))
      (js-await (wait-for-edit-hint popup)))
    (js-await (.close popup))))

(defn- ^:async test_conflict_detection_when_renaming_to_existing []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (js-await (save-script-in-panel! context ext-id
                                       {:name "Script A" :match "*://example.com/*"
                                        :code "(println \"Script A\")"
                                        :expected-filename "script_a.cljs"
                                        :clear-first? true}))
      (js-await (save-script-in-panel! context ext-id
                                       {:name "Script B" :match "*://github.com/*"
                                        :code "(println \"Script B\")"
                                        :expected-filename "script_b.cljs"}))
      (js-await (open-popup-and-inspect! context ext-id (+ builtin-script-count 2) "script_a.cljs"))

      ;; === PHASE 4: Edit script A and change name to script B's name ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")
            overwrite-btn (.locator panel "button.btn-overwrite")
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
            name-hint (.locator save-section ".property-row:has(th:text('Name')) .field-hint")]
        (js-await (-> (expect name-field) (.toContainText "script_a.cljs")))
        (js-await (wait-for-scripts-loaded panel (+ builtin-script-count 2)))
        (js-await (.fill textarea (panel-save-helpers/code-with-manifest
                                   {:name "script_b.cljs"
                                    :match "*://example.com/*"
                                    :code "(println \"Script A modified\")"})))
        (js-await (-> (expect name-hint) (.toContainText "\"script_b.cljs\" already exists")))
        (js-await (-> (expect save-btn) (.toBeDisabled)))
        (js-await (-> (expect overwrite-btn) (.toBeVisible)))
        (js-await (assert-no-errors! panel))
        (js-await (.close panel)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_overwrite_button_works []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (js-await (save-script-in-panel! context ext-id
                                       {:name "Original Script" :match "*://example.com/*"
                                        :code "(println \"Original content\")"
                                        :expected-filename "original_script.cljs"
                                        :clear-first? true}))
      (js-await (save-script-in-panel! context ext-id
                                       {:name "Target Script" :match "*://github.com/*"
                                        :code "(println \"Target content\")"
                                        :expected-filename "target_script.cljs"}))
      (js-await (open-popup-and-inspect! context ext-id (+ builtin-script-count 2) "original_script.cljs"))

      ;; === PHASE 4: Rename to target name and click Overwrite ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            overwrite-btn (.locator panel "button.btn-overwrite")
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
        (js-await (-> (expect name-field) (.toContainText "original_script.cljs")))
        (js-await (.fill textarea (panel-save-helpers/code-with-manifest
                                   {:name "target_script.cljs"
                                    :match "*://example.com/*"
                                    :code "(println \"Overwritten content\")"})))
        (js-await (-> (expect overwrite-btn) (.toBeVisible)))
        (js-await (.click overwrite-btn))
        (js-await (wait-for-save-status panel "Replaced"))
        (js-await (.close panel)))

      ;; === PHASE 5: Verify 2 scripts remain (overwrite replaces target, original stays) ===
      (let [popup (js-await (.newPage context))
            popup-url (str "chrome-extension://" ext-id "/popup.html")]
        (js-await (.goto popup popup-url #js {:timeout 1000}))
        (js-await (wait-for-popup-ready popup))
        (js-await (wait-for-script-count popup (+ builtin-script-count 2)))
        (js-await (-> (expect (.locator popup ".script-item:has-text(\"target_script.cljs\")")) (.toBeVisible)))
        (js-await (-> (expect (.locator popup ".script-item:has-text(\"original_script.cljs\")")) (.toBeVisible)))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_overwrite_disabled_for_builtin []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create a user script ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))
        (let [user-code (panel-save-helpers/code-with-manifest {:name "User Script"
                                                                :match "*://example.com/*"
                                                                :code "(println \"User script\")"})]
          (js-await (.fill textarea user-code)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "user_script.cljs"))
        (js-await (.close panel)))

      ;; === PHASE 2: Edit the user script ===
      (let [popup (js-await (.newPage context))
            popup-url (str "chrome-extension://" ext-id "/popup.html")]
        (js-await (.goto popup popup-url #js {:timeout 1000}))
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"user_script.cljs\")")
              inspect-btn (.locator script-item "button.script-inspect")]
          (js-await (.click inspect-btn))
          (js-await (wait-for-edit-hint popup)))
        (js-await (.close popup)))

      ;; === PHASE 3: Change name to match built-in script ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            overwrite-btn (.locator panel "button.btn-overwrite")
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
        ;; Wait for user script to load (polling)
        (js-await (-> (expect name-field) (.toContainText "user_script.cljs")))
        ;; Wait for scripts-list to be loaded (built-in + user)
        (js-await (wait-for-scripts-loaded panel (+ builtin-script-count 1)))
        ;; Use reserved epupp/ prefix - cannot create scripts with this prefix
        (let [builtin-name-code (panel-save-helpers/code-with-manifest
                                 {:name "epupp/web_userscript_installer.cljs"
                                  :match "*://example.com/*"
                                  :code "(println \"Trying to use reserved namespace\")"})]
          (js-await (.fill textarea builtin-name-code)))
        ;; Verify overwrite button appears but is disabled (polling)
        (js-await (-> (expect overwrite-btn) (.toBeVisible)))
        (js-await (-> (expect overwrite-btn) (.toBeDisabled)))
        ;; Verify tooltip explains why (could be built-in or reserved namespace)
        (let [title (js-await (.getAttribute overwrite-btn "title"))]
          (-> (expect (or (.includes title "built-in")
                          (.includes title "reserved namespace")))
              (.toBe true)))
        (js-await (assert-no-errors! panel))
        (js-await (.close panel)))

      (finally
        (js-await (.close context))))))

(defn- ^:async test_no_conflict_when_name_matches_original []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; === PHASE 1: Create a script ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")]
        (js-await (clear-storage panel))
        (js-await (.reload panel))
        (js-await (wait-for-panel-ready panel))
        (let [initial-code (panel-save-helpers/code-with-manifest {:name "Self Update Script"
                                                                   :match "*://example.com/*"
                                                                   :code "(println \"v1\")"})]
          (js-await (.fill textarea initial-code)))
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "self_update_script.cljs"))
        (js-await (.close panel)))

      ;; === PHASE 2: Edit the script from popup ===
      (let [popup (js-await (.newPage context))
            popup-url (str "chrome-extension://" ext-id "/popup.html")]
        (js-await (.goto popup popup-url #js {:timeout 1000}))
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup ".script-item:has-text(\"self_update_script.cljs\")")
              inspect-btn (.locator script-item "button.script-inspect")]
          (js-await (.click inspect-btn))
          (js-await (wait-for-edit-hint popup)))
        (js-await (.close popup)))

      ;; === PHASE 3: Edit with same name - should NOT show conflict ===
      (let [panel (js-await (create-panel-page context ext-id))
            textarea (.locator panel "#code-area")
            save-btn (.locator panel "button.btn-save")
            overwrite-btn (.locator panel "button.btn-overwrite")
            save-section (.locator panel ".save-script-section")
            name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")
            name-hint (.locator save-section ".property-row:has(th:text('Name')) .hint")]
        (js-await (-> (expect name-field) (.toContainText "self_update_script.cljs")))
        ;; Update code but keep same name
        (let [updated-code (panel-save-helpers/code-with-manifest {:name "self_update_script.cljs"
                                                                   :match "*://example.com/*"
                                                                   :code "(println \"v2 - updated\")"})]
          (js-await (.fill textarea updated-code)))
        ;; Verify: NO warning hint (no conflict with self)
        (js-await (-> (expect name-hint) (.not.toBeVisible)))
        ;; Verify: Save button is enabled
        (js-await (-> (expect save-btn) (.toBeEnabled)))
        ;; Verify: NO Overwrite button
        (js-await (-> (expect overwrite-btn) (.not.toBeVisible)))
        ;; Save should work normally
        (js-await (.click save-btn))
        (js-await (wait-for-save-status panel "Saved"))
        (js-await (assert-no-errors! panel))
        (js-await (.close panel)))

      (finally
        (js-await (.close context))))))

(.describe test "Panel Name Conflict"
           (fn []
             (test "Panel Conflict: detects when renaming to existing script name"
                   test_conflict_detection_when_renaming_to_existing)

             (test "Panel Conflict: overwrite button works"
                   test_overwrite_button_works)

             (test "Panel Conflict: overwrite disabled for built-in scripts"
                   test_overwrite_disabled_for_builtin)

             (test "Panel Conflict: no conflict when name matches original"
                   test_no_conflict_when_name_matches_original)))
