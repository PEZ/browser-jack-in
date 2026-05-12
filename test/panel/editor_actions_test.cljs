(ns panel.editor-actions-test
  "Tests for panel editor action handlers - basic state, initialization, new script"
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
;; Panel handle-action tests
;; ============================================================

(defn- test_set_code_updates_code []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code "new code"])]
    (-> (expect (:panel/code (:uf/db result)))
        (.toBe "new code"))))

(defn- test_set_script_name_updates_name []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-name "My Script"])]
    (-> (expect (:panel/script-name (:uf/db result)))
        (.toBe "My Script"))))

(defn- test_set_script_match_updates_match_pattern []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-match "*://github.com/*"])]
    (-> (expect (:panel/script-match (:uf/db result)))
        (.toBe "*://github.com/*"))))

(defn- test_set_script_description_updates_description []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-script-description "A helpful description"])]
    (-> (expect (:panel/script-description (:uf/db result)))
        (.toBe "A helpful description"))))

(defn- test_update_scittle_status_updates_status []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.update-scittle-status :loaded])]
    (-> (expect (:panel/scittle-status (:uf/db result)))
        (.toBe :loaded))))

(defn- test_clear_results_empties_results []
  (let [state-with-results (assoc initial-state :panel/results [{:type :input :text "code"}])
        result (panel-actions/handle-action state-with-results uf-data [:editor/ax.clear-results])]
    (-> (expect (:panel/results (:uf/db result)))
        (.toEqual []))))

(defn- test_clear_code_empties_code []
  (let [state-with-code (assoc initial-state :panel/code "(+ 1 2)")
        result (panel-actions/handle-action state-with-code uf-data [:editor/ax.clear-code])]
    (-> (expect (:panel/code (:uf/db result)))
        (.toBe ""))))

(defn- test_handle_eval_result_adds_output_to_results []
  (let [state-evaluating (assoc initial-state :panel/evaluating? true)
        result (panel-actions/handle-action state-evaluating uf-data [:editor/ax.handle-eval-result {:result "42"}])
        new-state (:uf/db result)]
    (-> (expect (:panel/evaluating? new-state))
        (.toBe false))
    (-> (expect (count (:panel/results new-state)))
        (.toBe 1))
    (-> (expect (:type (first (:panel/results new-state))))
        (.toBe :output))))

(defn- test_handle_eval_result_adds_error_to_results []
  (let [state-evaluating (assoc initial-state :panel/evaluating? true)
        result (panel-actions/handle-action state-evaluating uf-data [:editor/ax.handle-eval-result {:error "oops"}])
        new-state (:uf/db result)]
    (-> (expect (:panel/evaluating? new-state))
        (.toBe false))
    (-> (expect (:type (first (:panel/results new-state))))
        (.toBe :error))))

(defn- test_load_script_for_editing_populates_all_fields []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.load-script-for-editing
                                             "script-123"
                                             "Test Script"
                                             "*://example.com/*"
                                             "(println \"hello\")"
                                             "A description"])
        new-state (:uf/db result)]
    (-> (expect (:panel/script-name new-state))
        (.toBe "Test Script"))
    (-> (expect (:panel/script-match new-state))
        (.toBe "*://example.com/*"))
    (-> (expect (:panel/code new-state))
        (.toBe "(println \"hello\")"))
    (-> (expect (:panel/script-description new-state))
        (.toBe "A description"))))

(defn- test_load_script_for_editing_handles_missing_description []
  (let [result (panel-actions/handle-action initial-state uf-data
                                            [:editor/ax.load-script-for-editing
                                             "script-123"
                                             "Test Script"
                                             "*://example.com/*"
                                             "(println \"hello\")"])
        new-state (:uf/db result)]
    ;; Missing description should default to empty string
    (-> (expect (:panel/script-description new-state))
        (.toBe ""))))

(describe "panel handle-action"
          (fn []
            (test ":editor/ax.set-code updates code" test_set_code_updates_code)
            (test ":editor/ax.set-script-name updates name" test_set_script_name_updates_name)
            (test ":editor/ax.set-script-match updates match pattern" test_set_script_match_updates_match_pattern)
            (test ":editor/ax.set-script-description updates description" test_set_script_description_updates_description)
            (test ":editor/ax.update-scittle-status updates status" test_update_scittle_status_updates_status)
            (test ":editor/ax.clear-results empties results" test_clear_results_empties_results)
            (test ":editor/ax.clear-code empties code" test_clear_code_empties_code)
            (test ":editor/ax.handle-eval-result adds output to results" test_handle_eval_result_adds_output_to_results)
            (test ":editor/ax.handle-eval-result adds error to results" test_handle_eval_result_adds_error_to_results)
            (test ":editor/ax.load-script-for-editing populates all fields" test_load_script_for_editing_populates_all_fields)
            (test ":editor/ax.load-script-for-editing handles missing description" test_load_script_for_editing_handles_missing_description)))

;; ============================================================
;; Panel initialization tests
;; ============================================================

(defn- test_initialize_editor_with_saved_code_parses_manifest []
  (let [saved-code "{:epupp/script-name \"my_script.cljs\"
 :epupp/auto-run-match \"https://example.com/*\"
 :epupp/description \"Test script\"}

(ns my-script)
(println \"hello\")"
        result (panel-actions/handle-action
                initial-state uf-data
                [:editor/ax.initialize-editor
                 {:code saved-code :hostname "example.com"}])
        new-state (:uf/db result)
        dxs (:uf/dxs result)]
    ;; Code should be set
    (-> (expect (:panel/code new-state))
        (.toBe saved-code))
    ;; Original name should come from manifest
    (-> (expect (:panel/original-name new-state))
        (.toBe "my_script.cljs"))
    ;; Manifest hints should be populated
    (-> (expect (:panel/manifest-hints new-state))
        (.toBeTruthy))
    ;; Should have dxs to set name/match/description from manifest
    (-> (expect (some #(= (first %) :editor/ax.set-script-name) dxs))
        (.toBeTruthy))
    (-> (expect (some #(= (first %) :editor/ax.set-script-match) dxs))
        (.toBeTruthy))
    (-> (expect (some #(= (first %) :editor/ax.set-script-description) dxs))
        (.toBeTruthy))))

(defn- test_initialize_editor_with_no_saved_code_uses_default_script []
  (let [result (panel-actions/handle-action
                initial-state uf-data
                [:editor/ax.initialize-editor {:hostname "example.com"}])
        new-state (:uf/db result)
        dxs (:uf/dxs result)]
    ;; Code should have default script
    (-> (expect (:panel/code new-state))
        (.toContain "hello_world.cljs"))
    (-> (expect (:panel/code new-state))
        (.toContain "(ns hello-world)"))
    ;; Should NOT set original-name (it's a new script template)
    (-> (expect (:panel/original-name new-state))
        (.toBeUndefined))
    ;; Should have dxs to set name from manifest
    (-> (expect (some #(= (first %) :editor/ax.set-script-name) dxs))
        (.toBeTruthy))))

(defn- test_initialize_editor_with_empty_code_uses_default_script []
  (let [result (panel-actions/handle-action
                initial-state uf-data
                [:editor/ax.initialize-editor {:code "" :hostname "example.com"}])
        new-state (:uf/db result)]
    ;; Empty code should trigger default script
    (-> (expect (:panel/code new-state))
        (.toContain "hello_world.cljs"))
    ;; Should NOT set original-name for default script
    (-> (expect (:panel/original-name new-state))
        (.toBeUndefined))))

(describe "panel initialization action"
          (fn []
            (test ":editor/ax.initialize-editor with saved code parses manifest" test_initialize_editor_with_saved_code_parses_manifest)
            (test ":editor/ax.initialize-editor with no saved code uses default script" test_initialize_editor_with_no_saved_code_uses_default_script)
            (test ":editor/ax.initialize-editor with empty code uses default script" test_initialize_editor_with_empty_code_uses_default_script)))

;; ============================================================
;; New Script action tests
;; ============================================================

(defn- test_new_script_resets_to_default_script []
  (let [state-with-script (-> initial-state
                              (assoc :panel/code "(println \"custom code\")")
                              (assoc :panel/script-name "custom_script.cljs")
                              (assoc :panel/script-match "*://custom.com/*")
                              (assoc :panel/script-description "Custom description")
                              (assoc :panel/original-name "custom_script.cljs")
                              (assoc :panel/system-banners [{:id "test" :type "success" :message "Saved"}]))
        result (panel-actions/handle-action state-with-script uf-data [:editor/ax.new-script])
        new-state (:uf/db result)]
    ;; Code should be reset to default script
    (-> (expect (:panel/code new-state))
        (.toContain "hello_world.cljs"))
    (-> (expect (:panel/code new-state))
        (.toContain "(ns hello-world)"))
    ;; Original name should be cleared
    (-> (expect (:panel/original-name new-state))
        (.toBeNull))))

(defn- test_new_script_clears_persisted_state []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.new-script])
        fxs (:uf/fxs result)]
    ;; Should trigger clear-persisted-state effect
    (-> (expect (some #(= (first %) :editor/fx.clear-persisted-state) fxs))
        (.toBeTruthy))))

(defn- test_new_script_returns_dxs_to_parse_default_manifest []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.new-script])
        dxs (:uf/dxs result)]
    ;; Should have dxs to set script name from default manifest
    ;; Note: No set-script-match expected - default script has no site-match (manual-only)
    (-> (expect (some #(= (first %) :editor/ax.set-script-name) dxs))
        (.toBeTruthy))))

(defn- test_new_script_preserves_results_array []
  (let [state-with-results (-> initial-state
                               (assoc :panel/results [{:type :input :text "(+ 1 2)"}
                                                      {:type :output :text "3"}]))
        result (panel-actions/handle-action state-with-results uf-data [:editor/ax.new-script])
        new-state (:uf/db result)]
    ;; Results should be preserved
    (-> (expect (count (:panel/results new-state)))
        (.toBe 2))
    (-> (expect (:type (first (:panel/results new-state))))
        (.toBe :input))))

(describe "panel new script action"
          (fn []
            (test ":editor/ax.new-script resets to default script" test_new_script_resets_to_default_script)
            (test ":editor/ax.new-script clears persisted state" test_new_script_clears_persisted_state)
            (test ":editor/ax.new-script returns dxs to parse default manifest" test_new_script_returns_dxs_to_parse_default_manifest)
            (test ":editor/ax.new-script preserves results array" test_new_script_preserves_results_array)))
