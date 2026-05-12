(ns storage.enabled-state-test
  "Tests for script enabled state determination logic."
  (:require ["vitest" :refer [describe test expect]]))

;; ============================================================
;; Helper Functions
;; ============================================================

(defn determine-enabled-state
  "Determine the enabled state for a script based on:
   1. Whether it's always-enabled (e.g. sponsor script)
   2. Whether it has auto-run patterns
   3. Whether it's a new or existing script

   Logic:
   - Always-enabled → always true
   - No auto-run patterns → always false (manual-only)
   - Has patterns + existing → preserve existing enabled state
   - Has patterns + new → always false (all new scripts start disabled)"
  [{:keys [has-auto-run? existing-enabled is-new? always-enabled?]}]
  (cond
    always-enabled? true
    (not has-auto-run?) false
    (not is-new?) existing-enabled
    :else false))

;; ============================================================
;; Test Functions
;; ============================================================

(defn- test-no-auto-run-always-disabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? false
                :existing-enabled true
                :is-new? false
                :is-builtin? false}))
      (.toBe false)))

(defn- test-existing-with-auto-run-preserves-enabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled true
                :is-new? false
                :is-builtin? false}))
      (.toBe true))
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled false
                :is-new? false
                :is-builtin? false}))
      (.toBe false)))

(defn- test-new-user-script-starts-disabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled nil
                :is-new? true
                :is-builtin? false}))
      (.toBe false)))

(defn- test-new-builtin-starts-disabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled nil
                :is-new? true
                :is-builtin? true}))
      (.toBe false)))

(defn- test-auto-run-to-manual-resets-enabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? false
                :existing-enabled true
                :is-new? false
                :is-builtin? false}))
      (.toBe false)))

(defn- test-always-enabled-new-script-starts-enabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled nil
                :is-new? true
                :always-enabled? true}))
      (.toBe true)))

(defn- test-always-enabled-overrides-existing-disabled []
  (-> (expect (determine-enabled-state
               {:has-auto-run? true
                :existing-enabled false
                :is-new? false
                :always-enabled? true}))
      (.toBe true)))

(defn- test-always-enabled-stays-enabled-even-without-auto-run []
  (-> (expect (determine-enabled-state
               {:has-auto-run? false
                :existing-enabled false
                :is-new? false
                :always-enabled? true}))
      (.toBe true)))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "determine-enabled-state"
          (fn []
            (test "no auto-run patterns → always disabled" test-no-auto-run-always-disabled)
            (test "existing script with auto-run → preserves enabled state" test-existing-with-auto-run-preserves-enabled)
            (test "new user script with auto-run → starts disabled" test-new-user-script-starts-disabled)
            (test "new built-in with auto-run → starts disabled" test-new-builtin-starts-disabled)
            (test "auto-run → manual transition resets enabled" test-auto-run-to-manual-resets-enabled)
            (test "always-enabled new script starts enabled" test-always-enabled-new-script-starts-enabled)
            (test "always-enabled overrides existing disabled" test-always-enabled-overrides-existing-disabled)
            (test "always-enabled stays enabled even without auto-run" test-always-enabled-stays-enabled-even-without-auto-run)))
