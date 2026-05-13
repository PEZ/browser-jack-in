(ns panel.manifest-actions-test
  "Tests for panel manifest parsing - set-code manifest extraction, epupp:// URLs"
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
;; Manifest-driven metadata tests
;; ============================================================

(defn- test_set_code_parses_manifest_and_returns_dxs []
  (let [code "^{:epupp/script-name \"GitHub Tweaks\"
  :epupp/auto-run-match \"https://github.com/*\"
  :epupp/description \"Enhance GitHub UX\"}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)
        dxs (:uf/dxs result)]
    ;; Code should be updated
    (-> (expect (:panel/code new-state))
        (.toBe code))
    ;; Should have dxs to update fields from manifest
    (-> (expect dxs)
        (.toBeTruthy))
    ;; dxs should contain set-script-name with normalized name
    (-> (expect (some #(= (first %) :editor/ax.set-script-name) dxs))
        (.toBeTruthy))))

(defn- test_set_code_stores_manifest_hints_for_normalization []
  (let [code "^{:epupp/script-name \"GitHub Tweaks\"}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    ;; Should store manifest hints showing normalization occurred
    (-> (expect (:panel/manifest-hints new-state))
        (.toBeTruthy))
    (-> (expect (:name-normalized? (:panel/manifest-hints new-state)))
        (.toBe true))
    (-> (expect (:raw-script-name (:panel/manifest-hints new-state)))
        (.toBe "GitHub Tweaks"))))

(defn- manifest-hints-for-code [code]
  (:panel/manifest-hints (:uf/db (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code]))))

(defn- test_set_code_stores_unknown_keys_in_hints []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/author \"PEZ\"
  :epupp/version \"1.0\"}
(ns test)"
        hints (manifest-hints-for-code code)]
    (-> (expect (:unknown-keys hints))
        (.toContain "epupp/author"))
    (-> (expect (:unknown-keys hints))
        (.toContain "epupp/version"))))

(defn- test_set_code_clears_hints_when_no_manifest []
  (let [state-with-hints (assoc initial-state
                                :panel/manifest-hints {:name-normalized? true})
        code "(defn foo [] 42)"
        result (panel-actions/handle-action state-with-hints uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    ;; Should clear hints when no manifest found
    (-> (expect (:panel/manifest-hints new-state))
        (.toBeFalsy))))

(defn- test_set_code_handles_site_match_as_vector []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/auto-run-match [\"https://github.com/*\" \"https://gist.github.com/*\"]}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        dxs (:uf/dxs result)
        ;; Find the set-script-match action
        match-action (first (filter #(= (first %) :editor/ax.set-script-match) dxs))]
    ;; Should pass vector to set-script-match
    (-> (expect (second match-action))
        (.toEqual ["https://github.com/*" "https://gist.github.com/*"]))))

(defn- test_set_code_stores_run_at_invalid_flag_in_hints []
  (let [code "^{:epupp/script-name \"test.cljs\"
  :epupp/run-at \"invalid-timing\"}
(ns test)"
        hints (manifest-hints-for-code code)]
    (-> (expect (:run-at-invalid? hints))
        (.toBe true))
    (-> (expect (:raw-run-at hints))
        (.toBe "invalid-timing"))))

(defn- test_set_code_stores_inject_in_manifest_hints []
  (let [code "{:epupp/script-name \"test.cljs\"
  :epupp/inject [\"scittle://reagent.js\" \"scittle://pprint.js\"]}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    ;; Should store inject in hints
    (-> (expect (:inject (:panel/manifest-hints new-state)))
        (.toEqual ["scittle://reagent.js" "scittle://pprint.js"]))))

(defn- test_set_code_stores_empty_inject_when_missing []
  (let [code "{:epupp/script-name \"test.cljs\"}
(ns test)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        new-state (:uf/db result)]
    ;; Should store empty vector when inject is missing
    (-> (expect (:inject (:panel/manifest-hints new-state)))
        (.toEqual []))))

(describe "panel set-code with manifest parsing"
          (fn []
            (test ":editor/ax.set-code parses manifest and returns dxs to update fields" test_set_code_parses_manifest_and_returns_dxs)
            (test ":editor/ax.set-code stores manifest hints for normalization" test_set_code_stores_manifest_hints_for_normalization)
            (test ":editor/ax.set-code stores unknown keys in hints" test_set_code_stores_unknown_keys_in_hints)
            (test ":editor/ax.set-code clears hints when no manifest" test_set_code_clears_hints_when_no_manifest)
            (test ":editor/ax.set-code handles site-match as vector" test_set_code_handles_site_match_as_vector)
            (test ":editor/ax.set-code stores run-at invalid flag in hints" test_set_code_stores_run_at_invalid_flag_in_hints)
            (test ":editor/ax.set-code stores inject in manifest hints" test_set_code_stores_inject_in_manifest_hints)
            (test ":editor/ax.set-code stores empty inject when missing" test_set_code_stores_empty_inject_when_missing)))

;; ============================================================
;; Panel manifest epupp:// URL handling
;; ============================================================

(defn- test-set-code-with-epupp-inject-extracts-hints []
  (let [code "{:epupp/script-name \"my_script.cljs\"\n :epupp/inject [\"scittle://replicant.js\" \"epupp://utils.cljs\"]}\n\n(ns my-script)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        hints (:panel/manifest-hints (:uf/db result))]
    ;; Hints should contain inject list including epupp:// URL
    (-> (expect (:inject hints))
        (.toEqual ["scittle://replicant.js" "epupp://utils.cljs"]))))

(defn- test-set-code-epupp-only-inject []
  (let [code "{:epupp/script-name \"consumer.cljs\"\n :epupp/inject [\"epupp://my_lib.cljs\"]}\n\n(ns consumer)"
        result (panel-actions/handle-action initial-state uf-data [:editor/ax.set-code code])
        hints (:panel/manifest-hints (:uf/db result))]
    (-> (expect (:inject hints))
        (.toEqual ["epupp://my_lib.cljs"]))))

(describe "Panel manifest epupp:// handling"
          (fn []
            (test "set-code extracts mixed scittle:// and epupp:// into manifest hints"
                  test-set-code-with-epupp-inject-extracts-hints)
            (test "set-code handles epupp://-only inject list"
                  test-set-code-epupp-only-inject)))
