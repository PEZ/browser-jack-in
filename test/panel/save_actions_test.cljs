(ns panel.save-actions-test
  "Tests for panel save action handlers - save, save response, rename response"
  (:require ["vitest" :refer [describe test expect]]
            [panel-actions :as panel-actions]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def initial-state
  {:panel/results []
   :panel/code ""
   :panel/evaluating? false
   :panel/scittle-status :unknown
   :panel/script-name ""
   :panel/script-match ""
   :panel/script-description ""
   :panel/system-banners []})

(def uf-data {:system/now 1234567890})

;; ============================================================
;; Panel save action tests
;; ============================================================

(defn- test_save_script_with_missing_fields_shows_error []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.save-script])
        dxs (:uf/dxs result)
        [action-type event-type _message] (first dxs)]
    ;; Should dispatch show-system-banner with error
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))))

(defn- test_save_script_with_complete_fields_triggers_save_effect []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])]
    ;; Should NOT update state directly (async response will do that)
    (-> (expect (:uf/db result))
        (.toBeUndefined))
    ;; Should trigger save effect with script and normalized name
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :editor/fx.save-script))
    ;; Effect args: [script normalized-name]
    (let [[_fx script name] (first (:uf/fxs result))]
      (-> (expect (:script/name script))
          (.toBe "My Script"))
      (-> (expect name)
          (.toBe "my_script.cljs")))))

(defn- test_save_script_preserves_name_when_editing_with_unchanged_name []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "my_script.cljs")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/original-name "my_script.cljs"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script normalized-name] (first (:uf/fxs result))]
    ;; Should preserve name when unchanged
    (-> (expect (:script/name script))
        (.toBe "my_script.cljs"))
    (-> (expect normalized-name)
        (.toBe "my_script.cljs"))))

(defn- test_save_script_uses_new_name_when_name_changed []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "New Name")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/original-name "old_name.cljs"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script normalized-name] (first (:uf/fxs result))]
    ;; Name should be normalized to new name
    (-> (expect (:script/name script))
        (.toBe "New Name"))
    (-> (expect normalized-name)
        (.toBe "new_name.cljs"))))

(defn- test_save_script_normalizes_name_for_new_scripts []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Cool Script")
                  (assoc :panel/script-match "*://example.com/*"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script normalized-name] (first (:uf/fxs result))]
    ;; Name is normalized for display consistency
    (-> (expect (:script/name script))
        (.toBe "My Cool Script"))
    (-> (expect normalized-name)
        (.toBe "my_cool_script.cljs"))))

(defn- test_save_script_includes_description_when_provided []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/script-description "A helpful description"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script] (first (:uf/fxs result))]
    (-> (expect (:script/description script))
        (.toBe "A helpful description"))))

(defn- test_save_script_omits_description_when_empty []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/script-description ""))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script] (first (:uf/fxs result))]
    ;; Empty description should not be included in script
    (-> (expect (:script/description script))
        (.toBeUndefined))))

(defn- test_save_script_includes_description_in_effect_when_set []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/script-description "A description"))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script _name _id _action-text] (first (:uf/fxs result))]
    ;; Description should be in the script sent to background
    (-> (expect (:script/description script))
        (.toBe "A description"))))

(defn- test_save_script_preserves_vector_match_without_double_wrapping []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match ["*://example.com/*" "*://foo.com/*"]))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script] (first (:uf/fxs result))]
    ;; Vector match should stay flat (not double-wrapped)
    (-> (expect (js/Array.isArray (:script/match script)))
        (.toBe true))
    (-> (expect (count (:script/match script)))
        (.toBe 2))
    (-> (expect (aget (:script/match script) 0))
        (.toBe "*://example.com/*"))
    (-> (expect (aget (:script/match script) 1))
        (.toBe "*://foo.com/*"))))

(defn- test_save_script_includes_inject_from_manifest_hints []
  (let [state (-> initial-state
                  (assoc :panel/code "(ns test)")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match "*://example.com/*")
                  (assoc :panel/manifest-hints {:inject ["scittle://reagent.js"]}))
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx-name script] (first (:uf/fxs result))]
    (-> (expect (:script/inject script))
        (.toEqual ["scittle://reagent.js"]))))

(defn- test_save_script_succeeds_without_site_match []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match ""))  ;; No site-match
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])]
    ;; Should NOT show error - save should proceed
    (-> (expect (:uf/db result))
        (.toBeUndefined))
    ;; Should trigger save effect
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :editor/fx.save-script))
    ;; Script should have empty match vector
    (let [[_fx script] (first (:uf/fxs result))]
      (-> (expect (:script/match script))
          (.toEqual [])))))

(defn- test_save_script_without_site_match_uses_empty_vector []
  (let [state (-> initial-state
                  (assoc :panel/code "(println \"hi\")")
                  (assoc :panel/script-name "My Script")
                  (assoc :panel/script-match nil))  ;; nil site-match
        result (panel-actions/handle-action state uf-data [:editor/ax.save-script])
        [_fx script] (first (:uf/fxs result))]
    ;; nil should normalize to empty vector
    (-> (expect (:script/match script))
        (.toEqual []))))

(describe "panel save action"
          (fn []
            (test ":editor/ax.save-script with missing fields shows error" test_save_script_with_missing_fields_shows_error)
            (test ":editor/ax.save-script succeeds without site-match" test_save_script_succeeds_without_site_match)
            (test ":editor/ax.save-script without site-match uses empty vector" test_save_script_without_site_match_uses_empty_vector)
            (test ":editor/ax.save-script with complete fields triggers save effect" test_save_script_with_complete_fields_triggers_save_effect)
            (test ":editor/ax.save-script preserves name when editing with unchanged name" test_save_script_preserves_name_when_editing_with_unchanged_name)
            (test ":editor/ax.save-script uses new name when name changed" test_save_script_uses_new_name_when_name_changed)
            (test ":editor/ax.save-script normalizes name for new scripts" test_save_script_normalizes_name_for_new_scripts)
            (test ":editor/ax.save-script includes description when provided" test_save_script_includes_description_when_provided)
            (test ":editor/ax.save-script omits description when empty" test_save_script_omits_description_when_empty)
            (test ":editor/ax.save-script includes description in effect when set" test_save_script_includes_description_in_effect_when_set)
            (test ":editor/ax.save-script preserves vector match without double-wrapping" test_save_script_preserves_vector_match_without_double_wrapping)
            (test ":editor/ax.save-script includes inject from manifest hints" test_save_script_includes_inject_from_manifest_hints)))

;; ============================================================
;; Panel save response handling tests
;; ============================================================

(defn- test_handle_save_response_updates_state_on_success []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.handle-save-response
                                             {:success true
                                              :name "my_script.cljs"
                                              :id "script-123"
                                              :action-text "Created"}])
        new-state (:uf/db result)
        dxs (:uf/dxs result)
        [action-type event-type message] (first dxs)]
    ;; Should dispatch show-system-banner with success
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "success"))
    (-> (expect message)
        (.toContain "Created"))
    ;; State updates
    (-> (expect (:panel/script-name new-state))
        (.toBe "my_script.cljs"))
    (-> (expect (:panel/original-name new-state))
        (.toBe "my_script.cljs"))))

(defn- test_handle_save_response_shows_error_on_failure []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.handle-save-response
                                             {:success false
                                              :error "Name collision"}])
        dxs (:uf/dxs result)
        [action-type event-type message] (first dxs)]
    ;; Should dispatch show-system-banner with error
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))
    (-> (expect message)
        (.toBe "Name collision"))
    ;; Should not have db changes on error-only response
    (-> (expect (:uf/db result))
        (.toBeUndefined))))

(defn- test_handle_rename_response_updates_state_on_success []
  (let [state (assoc initial-state :panel/original-name "old_name.cljs")
        result (panel-actions/handle-action state uf-data
                                            [:editor/ax.handle-rename-response
                                             {:success true
                                              :to-name "new_name.cljs"}])
        new-state (:uf/db result)
        dxs (:uf/dxs result)
        [action-type event-type message] (first dxs)]
    ;; Should dispatch show-system-banner with success
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "success"))
    (-> (expect message)
        (.toContain "Renamed"))
    ;; State updates
    (-> (expect (:panel/script-name new-state))
        (.toBe "new_name.cljs"))
    (-> (expect (:panel/original-name new-state))
        (.toBe "new_name.cljs"))))

(defn- test_handle_rename_response_shows_error_on_failure []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.handle-rename-response
                                             {:success false
                                              :error "Script not found"}])
        dxs (:uf/dxs result)
        [action-type event-type message] (first dxs)]
    ;; Should dispatch show-system-banner with error
    (-> (expect action-type)
        (.toBe :editor/ax.show-system-banner))
    (-> (expect event-type)
        (.toBe "error"))
    (-> (expect message)
        (.toBe "Script not found"))))

(describe "panel save response handling"
          (fn []
            (test ":editor/ax.handle-save-response updates state on success" test_handle_save_response_updates_state_on_success)
            (test ":editor/ax.handle-save-response shows error on failure" test_handle_save_response_shows_error_on_failure)
            (test ":editor/ax.handle-rename-response updates state on success" test_handle_rename_response_updates_state_on_success)
            (test ":editor/ax.handle-rename-response shows error on failure" test_handle_rename_response_shows_error_on_failure)))
