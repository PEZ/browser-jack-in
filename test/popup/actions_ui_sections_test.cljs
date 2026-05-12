(ns popup.actions-ui-sections-test
  "Tests for popup section toggle and modified scripts tracking actions"
  (:require ["vitest" :refer [describe test expect]]
            [popup.actions :as popup-actions]))

;; ============================================================
;; Shared Setup
;; ============================================================

(def uf-data {:system/now 1234567890
              :config/deps-string "{:deps {}}"})

;; ============================================================
;; Section Toggle Tests
;; ============================================================

(defn- test-toggle-section-toggles-false-to-true []
  (let [state {:ui/sections-collapsed {:repl-connect false}}
        result (popup-actions/handle-action state uf-data [:ui/ax.toggle-section :repl-connect])]
    (-> (expect (get-in (:uf/db result) [:ui/sections-collapsed :repl-connect]))
        (.toBe true))))

(defn- test-toggle-section-toggles-true-to-false []
  (let [state {:ui/sections-collapsed {:settings true}}
        result (popup-actions/handle-action state uf-data [:ui/ax.toggle-section :settings])]
    (-> (expect (get-in (:uf/db result) [:ui/sections-collapsed :settings]))
        (.toBe false))))

(defn- test-toggle-section-handles-nil-state []
  (let [state {:ui/sections-collapsed {}}
        result (popup-actions/handle-action state uf-data [:ui/ax.toggle-section :scripts])]
    (-> (expect (get-in (:uf/db result) [:ui/sections-collapsed :scripts]))
        (.toBe true))))

;; ============================================================
;; Modified Scripts Tracking Tests
;; ============================================================

(defn- test-mark-scripts-modified-single-script []
  (let [state {:ui/recently-modified-scripts #{}}
        result (popup-actions/handle-action state uf-data
                                            [:ui/ax.mark-scripts-modified ["test.cljs"]])
        new-state (:uf/db result)]
    ;; Script should be added to modified set
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "test.cljs"))
    ;; Should schedule clear action
    (-> (expect (some #(= :uf/fx.defer-dispatch (first %)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-mark-scripts-modified-multiple-scripts []
  (let [state {:ui/recently-modified-scripts #{}}
        result (popup-actions/handle-action state uf-data
                                            [:ui/ax.mark-scripts-modified ["one.cljs" "two.cljs"]])
        new-state (:uf/db result)]
    ;; Both scripts should be added
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "one.cljs"))
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "two.cljs"))))

(defn- test-mark-scripts-modified-appends-to-existing []
  (let [state {:ui/recently-modified-scripts #{"existing.cljs"}}
        result (popup-actions/handle-action state uf-data
                                            [:ui/ax.mark-scripts-modified ["new.cljs"]])
        new-state (:uf/db result)]
    ;; Should have both existing and new
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "existing.cljs"))
    (-> (expect (:ui/recently-modified-scripts new-state))
        (.toContain "new.cljs"))))

(defn- test-clear-modified-scripts-removes-all []
  (let [state {:ui/recently-modified-scripts #{"one.cljs" "two.cljs"}}
        result (popup-actions/handle-action state uf-data
                                            [:ui/ax.clear-modified-scripts])
        new-state (:uf/db result)]
    ;; Should clear all modified scripts
    (-> (expect (count (:ui/recently-modified-scripts new-state)))
        (.toBe 0))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "Section toggle"
          (fn []
            (test "toggles collapsed state from false to true" test-toggle-section-toggles-false-to-true)
            (test "toggles collapsed state from true to false" test-toggle-section-toggles-true-to-false)
            (test "handles nil state by defaulting to true" test-toggle-section-handles-nil-state)))

(describe "Modified scripts tracking"
          (fn []
            (test "single script marked modified" test-mark-scripts-modified-single-script)
            (test "multiple scripts marked in batch" test-mark-scripts-modified-multiple-scripts)
            (test "appends to existing modified set" test-mark-scripts-modified-appends-to-existing)
            (test "clear action removes all" test-clear-modified-scripts-removes-all)))
