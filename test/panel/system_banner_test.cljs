(ns panel.system-banner-test
  "Tests for panel system banner and runtime status handlers"
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
;; Panel system banner category tests
;; ============================================================

(defn- test_show_system_banner_with_category_replaces_existing_banner []
  (let [existing-banner {:id "msg-1" :type "info" :message "Processing..." :category "operation"}
        state {:panel/system-banners [existing-banner]}
        result (panel-actions/handle-action state uf-data [:editor/ax.show-system-banner "success" "Done!" "operation"])
        banners (:panel/system-banners (:uf/db result))]
    ;; Should still have one banner (replaced, not appended)
    (-> (expect (count banners))
        (.toBe 1))
    ;; New banner should have new message
    (-> (expect (:message (first banners)))
        (.toBe "Done!"))))

(defn- test_show_system_banner_with_category_does_not_replace_different_category []
  (let [existing-banner {:id "msg-1" :type "info" :message "Loading..." :category "loading"}
        state {:panel/system-banners [existing-banner]}
        result (panel-actions/handle-action state uf-data [:editor/ax.show-system-banner "success" "Saved!" "save"])
        banners (:panel/system-banners (:uf/db result))]
    ;; Should have two banners (different categories)
    (-> (expect (count banners))
        (.toBe 2))))

(defn- test_show_system_banner_without_category_appends_normally []
  (let [existing-banner {:id "msg-1" :type "info" :message "Existing..." :category "existing"}
        state {:panel/system-banners [existing-banner]}
        result (panel-actions/handle-action state uf-data [:editor/ax.show-system-banner "success" "New!"])
        banners (:panel/system-banners (:uf/db result))]
    ;; Should have two banners (no category to replace)
    (-> (expect (count banners))
        (.toBe 2))))

(describe "panel system banner with category"
          (fn []
            (test ":editor/ax.show-system-banner with category replaces existing banner" test_show_system_banner_with_category_replaces_existing_banner)
            (test ":editor/ax.show-system-banner does not replace different category" test_show_system_banner_with_category_does_not_replace_different_category)
            (test ":editor/ax.show-system-banner without category appends normally" test_show_system_banner_without_category_appends_normally)))

;; ============================================================
;; Handle System Banner Tests (D-11, D-12)
;; ============================================================

(defn- test-handle-system-banner-simple-success []
  (let [state (assoc initial-state
                     :panel/system-bulk-names {}
                     :panel/original-name "other.cljs")
        result (panel-actions/handle-action state uf-data
                                            [:panel/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "test.cljs"}])
        dxs (:uf/dxs result)
        show-banner-dx (some #(when (= :editor/ax.show-system-banner (first %)) %) dxs)]
    ;; Should dispatch show-system-banner
    (-> (expect show-banner-dx) (.toBeTruthy))
    (-> (expect (second show-banner-dx)) (.toBe "success"))
    (-> (expect (nth show-banner-dx 2)) (.toBe "Script \"test.cljs\" saved"))))

(defn- test-handle-system-banner-error []
  (let [state (assoc initial-state :panel/system-bulk-names {})
        result (panel-actions/handle-action state uf-data
                                            [:panel/ax.handle-system-banner
                                             {:event-type "error"
                                              :operation "save"
                                              :script-name "test.cljs"
                                              :error "Permission denied"}])
        dxs (:uf/dxs result)
        show-banner-dx (some #(when (= :editor/ax.show-system-banner (first %)) %) dxs)]
    (-> (expect (second show-banner-dx)) (.toBe "error"))
    (-> (expect (nth show-banner-dx 2)) (.toBe "FS sync error: Permission denied"))))

(defn- test-handle-system-banner-skip-own-saves []
  (let [state (assoc initial-state
                     :panel/script-name "test.cljs"
                     :panel/original-name "test.cljs"
                     :panel/system-bulk-names {})
        result (panel-actions/handle-action state uf-data
                                            [:panel/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "test.cljs"}])
        dxs (:uf/dxs result)
        show-banner-dx (some #(when (= :editor/ax.show-system-banner (first %)) %) dxs)]
    ;; Should NOT show banner for panel's own saves
    (-> (expect show-banner-dx) (.toBeFalsy))))

(defn- test-handle-system-banner-affects-current-reloads []
  (let [state (assoc initial-state
                     :panel/script-name "test.cljs"
                     :panel/original-name "test.cljs"
                     :panel/system-bulk-names {})
        result (panel-actions/handle-action state uf-data
                                            [:panel/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "test.cljs"}])
        dxs (:uf/dxs result)
        reload-dx (some #(when (= :editor/ax.reload-script-from-storage (first %)) %) dxs)]
    ;; Should reload script from storage when current script is affected
    (-> (expect reload-dx) (.toBeTruthy))
    (-> (expect (second reload-dx)) (.toBe "test.cljs"))))

(defn- test-handle-system-banner-affects-current-delete-clears []
  (let [state (assoc initial-state
                     :panel/script-name "test.cljs"
                     :panel/original-name "test.cljs"
                     :panel/system-bulk-names {})
        result (panel-actions/handle-action state uf-data
                                            [:panel/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "delete"
                                              :script-name "test.cljs"}])
        dxs (:uf/dxs result)
        new-script-dx (some #(when (= :editor/ax.new-script (first %)) %) dxs)]
    ;; Should new-script when current script is deleted
    (-> (expect new-script-dx) (.toBeTruthy))))

(defn- test-handle-system-banner-from-name-matching []
  (let [state (assoc initial-state
                     :panel/script-name "new_name.cljs"
                     :panel/original-name "old_name.cljs"
                     :panel/system-bulk-names {})
        result (panel-actions/handle-action state uf-data
                                            [:panel/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "other.cljs"
                                              :from-name "old_name.cljs"}])
        dxs (:uf/dxs result)
        reload-dx (some #(when (= :editor/ax.reload-script-from-storage (first %)) %) dxs)]
    ;; Should match via from-name against original-name
    (-> (expect reload-dx) (.toBeTruthy))))

(defn- test-handle-system-banner-bulk-tracks-and-clears []
  (let [state (assoc initial-state :panel/system-bulk-names {"bulk-1" ["a.cljs"]})
        result (panel-actions/handle-action state uf-data
                                            [:panel/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "b.cljs"
                                              :bulk-id "bulk-1"
                                              :bulk-count 2
                                              :bulk-index 1}])
        new-state (:uf/db result)]
    ;; Bulk final should clear bulk names
    (-> (expect (get-in new-state [:panel/system-bulk-names "bulk-1"]))
        (.toBeUndefined))))

(defn- test-handle-system-banner-bulk-final-log-effect []
  (let [state (assoc initial-state
                     :panel/system-bulk-names {"bulk-1" ["a.cljs"]}
                     :panel/original-name "other.cljs")
        result (panel-actions/handle-action state uf-data
                                            [:panel/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "b.cljs"
                                              :bulk-id "bulk-1"
                                              :bulk-count 2
                                              :bulk-index 1}])
        fxs (:uf/fxs result)
        log-fx (some #(when (= :panel/fx.log-system-banner (first %)) %) fxs)]
    ;; Should dispatch log effect with bulk names
    (-> (expect log-fx) (.toBeTruthy))
    (-> (expect (nth log-fx 2)) (.toEqual ["a.cljs" "b.cljs"]))))

(defn- test-handle-system-banner-no-affects-different-script []
  (let [state (assoc initial-state
                     :panel/script-name "other.cljs"
                     :panel/original-name "other.cljs"
                     :panel/system-bulk-names {})
        result (panel-actions/handle-action state uf-data
                                            [:panel/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "test.cljs"}])
        dxs (:uf/dxs result)
        reload-dx (some #(when (= :editor/ax.reload-script-from-storage (first %)) %) dxs)
        show-banner-dx (some #(when (= :editor/ax.show-system-banner (first %)) %) dxs)]
    ;; Should NOT reload (different script)
    (-> (expect reload-dx) (.toBeFalsy))
    ;; Should show banner (not panel's own save)
    (-> (expect show-banner-dx) (.toBeTruthy))))

(describe "Panel Handle System Banner (D-11, D-12)"
          (fn []
            (test "simple success dispatches show-system-banner" test-handle-system-banner-simple-success)
            (test "error dispatches banner with error message" test-handle-system-banner-error)
            (test "skips banner for panel's own saves" test-handle-system-banner-skip-own-saves)
            (test "reloads editor when current script is modified externally" test-handle-system-banner-affects-current-reloads)
            (test "clears editor when current script is deleted" test-handle-system-banner-affects-current-delete-clears)
            (test "matches via from-name against original-name" test-handle-system-banner-from-name-matching)
            (test "bulk final tracks then clears names" test-handle-system-banner-bulk-tracks-and-clears)
            (test "bulk final dispatches log effect with names" test-handle-system-banner-bulk-final-log-effect)
            (test "does not reload for different script" test-handle-system-banner-no-affects-different-script)))

;; ============================================================
;; Panel Runtime Status Tests
;; ============================================================

(defn- test-panel-handle-runtime-status-stores-errors []
  (let [state (assoc initial-state :runtime/errors {})
        result (panel-actions/handle-action state uf-data
                 [:panel/ax.handle-runtime-status
                  {:errors {"my/script.cljs" {:error/message "Library not found"}}}])]
    (-> (expect (count (:runtime/errors (:uf/db result)))) (.toBe 1))
    (-> (expect (get-in (:uf/db result) [:runtime/errors "my/script.cljs" :error/message]))
        (.toBe "Library not found"))))

(defn- test-panel-handle-runtime-status-clears-on-empty []
  (let [state (assoc initial-state :runtime/errors {"old.cljs" {:error/message "old"}})
        result (panel-actions/handle-action state uf-data
                 [:panel/ax.handle-runtime-status {:errors {}}])]
    (-> (expect (count (:runtime/errors (:uf/db result)))) (.toBe 0))))

(describe "Panel runtime status"
          (fn []
            (test "handle-runtime-status stores errors in state"
                  test-panel-handle-runtime-status-stores-errors)
            (test "handle-runtime-status clears on empty errors"
                  test-panel-handle-runtime-status-clears-on-empty)))
