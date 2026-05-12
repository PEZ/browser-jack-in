(ns panel.eval-actions-test
  "Tests for panel eval action handlers - eval, selection, inject threading"
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
;; Panel eval action tests
;; ============================================================

(defn- test_eval_with_empty_code_returns_nil []
  (let [result (panel-actions/handle-action initial-state uf-data [:editor/ax.eval])]
    (-> (expect result)
        (.toBeNull))))

(defn- test_eval_when_already_evaluating_returns_nil []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/evaluating? true))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])]
    (-> (expect result)
        (.toBeNull))))

(defn- test_eval_with_loaded_scittle_triggers_direct_eval []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/scittle-status :loaded))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])
        new-state (:uf/db result)]
    (-> (expect (:panel/evaluating? new-state))
        (.toBe true))
    (-> (expect (count (:panel/results new-state)))
        (.toBe 1))
    ;; Should trigger fx.eval-in-page
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :editor/fx.eval-in-page))))

(defn- test_eval_without_scittle_triggers_inject_and_eval []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/scittle-status :unknown))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])
        new-state (:uf/db result)]
    (-> (expect (:panel/evaluating? new-state))
        (.toBe true))
    (-> (expect (:panel/scittle-status new-state))
        (.toBe :loading))
    ;; Should trigger fx.inject-and-eval
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :editor/fx.inject-and-eval))))

(defn- test_eval_without_scittle_passes_inject_libs []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/scittle-status :unknown)
                  (assoc :panel/manifest-hints {:inject ["scittle://reagent.js"]}))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])
        [effect-name _effect-code effect-libs] (first (:uf/fxs result))]
    (-> (expect effect-name)
        (.toBe :editor/fx.inject-and-eval))
    (-> (expect effect-libs)
        (.toEqual ["scittle://reagent.js"]))))

(describe "panel eval action"
          (fn []
            (test ":editor/ax.eval with empty code returns nil" test_eval_with_empty_code_returns_nil)
            (test ":editor/ax.eval when already evaluating returns nil" test_eval_when_already_evaluating_returns_nil)
            (test ":editor/ax.eval with loaded scittle triggers direct eval" test_eval_with_loaded_scittle_triggers_direct_eval)
            (test ":editor/ax.eval without scittle triggers inject-and-eval" test_eval_without_scittle_triggers_inject_and_eval)
            (test ":editor/ax.eval without scittle passes inject libs" test_eval_without_scittle_passes_inject_libs)))

;; ============================================================
;; Panel selection action tests
;; ============================================================

(defn- test_set_selection_updates_selection_state []
  (let [selection {:start 0 :end 7 :text "(+ 1 2)"}
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-selection selection])]
    (-> (expect (:panel/selection (:uf/db result)))
        (.toEqual {:start 0 :end 7 :text "(+ 1 2)"}))))

(defn- test_set_selection_clears_selection_with_nil []
  (let [state-with-selection (assoc initial-state :panel/selection {:start 0 :end 5 :text "hello"})
        result (panel-actions/handle-action state-with-selection uf-data [:editor/ax.set-selection nil])]
    (-> (expect (:panel/selection (:uf/db result)))
        (.toBeNull))))

(defn- test_eval_selection_with_selection_evaluates_selected_text []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)\n(* 3 4)")
                  (assoc :panel/selection {:start 8 :end 15 :text "(* 3 4)"})
                  (assoc :panel/scittle-status :loaded))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
        new-state (:uf/db result)
        [effect-name effect-code] (first (:uf/fxs result))]
    ;; Should be evaluating
    (-> (expect (:panel/evaluating? new-state))
        (.toBe true))
    ;; Should show selection as input, not full code
    (-> (expect (:text (last (:panel/results new-state))))
        (.toBe "(* 3 4)"))
    ;; Effect should receive selection text
    (-> (expect effect-name)
        (.toBe :editor/fx.eval-in-page))
    (-> (expect effect-code)
        (.toBe "(* 3 4)"))))

(defn- test_eval_selection_without_selection_evaluates_full_code []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/selection nil)
                  (assoc :panel/scittle-status :loaded))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
        [_effect-name effect-code] (first (:uf/fxs result))]
    ;; Should fall back to full code
    (-> (expect effect-code)
        (.toBe "(+ 1 2)"))))

(defn- test_eval_selection_with_empty_selection_text_evaluates_full_code []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/selection {:start 3 :end 3 :text ""})
                  (assoc :panel/scittle-status :loaded))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
        [_effect-name effect-code] (first (:uf/fxs result))]
    ;; Empty selection (cursor position) falls back to full code
    (-> (expect effect-code)
        (.toBe "(+ 1 2)"))))

(defn- test_eval_selection_with_empty_code_and_empty_selection_returns_nil []
  (let [state (-> initial-state
                  (assoc :panel/selection {:start 0 :end 0 :text ""}))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])]
    ;; Should return nil when both code and selection are empty
    (-> (expect result)
        (.toBeNull))))

(defn- test_eval_selection_when_already_evaluating_returns_nil []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/selection {:start 0 :end 7 :text "(+ 1 2)"})
                  (assoc :panel/evaluating? true))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])]
    (-> (expect result)
        (.toBeNull))))

(defn- test_eval_selection_without_scittle_triggers_inject_and_eval []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)\n(* 3 4)")
                  (assoc :panel/selection {:start 8 :end 15 :text "(* 3 4)"})
                  (assoc :panel/scittle-status :unknown))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
        new-state (:uf/db result)
        [effect-name effect-code] (first (:uf/fxs result))]
    ;; Should trigger inject-and-eval with selection
    (-> (expect effect-name)
        (.toBe :editor/fx.inject-and-eval))
    (-> (expect effect-code)
        (.toBe "(* 3 4)"))
    ;; Status should be loading
    (-> (expect (:panel/scittle-status new-state))
        (.toBe :loading))))

(defn- test_eval_selection_with_loaded_scittle_passes_inject_libs []
  (let [state (-> initial-state
                  (assoc :panel/code "(+ 1 2)")
                  (assoc :panel/scittle-status :loaded)
                  (assoc :panel/manifest-hints {:inject ["scittle://reagent.js"]}))
        result (panel-actions/handle-action state uf-data [:editor/ax.eval-selection])
        [effect-name _effect-code effect-libs] (first (:uf/fxs result))]
    (-> (expect effect-name)
        (.toBe :editor/fx.eval-in-page))
    (-> (expect effect-libs)
        (.toEqual ["scittle://reagent.js"]))))

(describe "panel selection actions"
          (fn []
            (test ":editor/ax.set-selection updates selection state" test_set_selection_updates_selection_state)
            (test ":editor/ax.set-selection clears selection with nil" test_set_selection_clears_selection_with_nil)
            (test ":editor/ax.eval-selection with selection evaluates selected text" test_eval_selection_with_selection_evaluates_selected_text)
            (test ":editor/ax.eval-selection without selection evaluates full code" test_eval_selection_without_selection_evaluates_full_code)
            (test ":editor/ax.eval-selection with empty selection text evaluates full code" test_eval_selection_with_empty_selection_text_evaluates_full_code)
            (test ":editor/ax.eval-selection with empty code and empty selection returns nil" test_eval_selection_with_empty_code_and_empty_selection_returns_nil)
            (test ":editor/ax.eval-selection when already evaluating returns nil" test_eval_selection_when_already_evaluating_returns_nil)
            (test ":editor/ax.eval-selection without scittle triggers inject-and-eval" test_eval_selection_without_scittle_triggers_inject_and_eval)
            (test ":editor/ax.eval-selection with loaded scittle passes inject libs" test_eval_selection_with_loaded_scittle_passes_inject_libs)))

;; ============================================================
;; Panel eval inject threading baseline tests
;; ============================================================

(defn- test-panel-eval-loaded-passes-inject-to-eval-effect []
  (let [state (assoc initial-state
                     :panel/code "(+ 1 2)"
                     :panel/scittle-status :loaded
                     :panel/manifest-hints {:inject ["scittle://reagent.js" "epupp://utils.cljs"]})
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])
        fxs (:uf/fxs result)
        eval-effect (first fxs)]
    ;; Effect should be eval-in-page with code and inject libs
    (-> (expect (first eval-effect)) (.toBe :editor/fx.eval-in-page))
    (-> (expect (second eval-effect)) (.toBe "(+ 1 2)"))
    (-> (expect (nth eval-effect 2))
        (.toEqual ["scittle://reagent.js" "epupp://utils.cljs"]))))

(defn- test-panel-eval-not-loaded-passes-inject-to-inject-and-eval-effect []
  (let [state (assoc initial-state
                     :panel/code "(+ 1 2)"
                     :panel/scittle-status :unknown
                     :panel/manifest-hints {:inject ["scittle://pprint.js"]})
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])
        fxs (:uf/fxs result)
        eval-effect (first fxs)]
    ;; Should inject-and-eval when scittle not loaded
    (-> (expect (first eval-effect)) (.toBe :editor/fx.inject-and-eval))
    (-> (expect (second eval-effect)) (.toBe "(+ 1 2)"))
    (-> (expect (nth eval-effect 2))
        (.toEqual ["scittle://pprint.js"]))))

(defn- test-panel-eval-nil-inject-passes-nil []
  (let [state (assoc initial-state
                     :panel/code "(+ 1 2)"
                     :panel/scittle-status :loaded
                     :panel/manifest-hints {})
        result (panel-actions/handle-action state uf-data [:editor/ax.eval])
        fxs (:uf/fxs result)
        eval-effect (first fxs)]
    (-> (expect (first eval-effect)) (.toBe :editor/fx.eval-in-page))
    ;; nil inject when no manifest hints
    (-> (expect (nth eval-effect 2)) (.toBeFalsy))))

(describe "Panel eval inject threading"
          (fn []
            (test "scittle loaded passes inject to eval-in-page" test-panel-eval-loaded-passes-inject-to-eval-effect)
            (test "scittle not loaded passes inject to inject-and-eval" test-panel-eval-not-loaded-passes-inject-to-inject-and-eval-effect)
            (test "nil inject when no manifest hints" test-panel-eval-nil-inject-passes-nil)))
