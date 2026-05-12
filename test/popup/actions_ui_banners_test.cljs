(ns popup.actions-ui-banners-test
  "Tests for popup system banner action handlers"
  (:require ["vitest" :refer [describe test expect]]
            [popup.actions :as popup-actions]))

;; ============================================================
;; Shared Setup
;; ============================================================

(def uf-data {:system/now 1234567890
              :config/deps-string "{:deps {}}"})

;; ============================================================
;; System Banner Multi-Message Tests
;; ============================================================

(defn- test-show-system-banner-appends-to-empty-list []
  (let [state {:ui/system-banners []}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.show-system-banner "success" "Saved!" {}])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should have one banner
    (-> (expect (count banners))
        (.toBe 1))
    ;; Banner should have type and message
    (-> (expect (:type (first banners)))
        (.toBe "success"))
    (-> (expect (:message (first banners)))
        (.toBe "Saved!"))))

(defn- test-show-system-banner-generates-unique-id []
  (let [state {:ui/system-banners []}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.show-system-banner "success" "Saved!" {}])
        banner (first (:ui/system-banners (:uf/db result)))]
    ;; Should have an ID
    (-> (expect (:id banner))
        (.toBeTruthy))))

(defn- test-show-system-banner-appends-to-existing-list []
  (let [existing-banner {:id "msg-1" :type "info" :message "Processing..."}
        state {:ui/system-banners [existing-banner]}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.show-system-banner "success" "Done!" {}])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should have two banners
    (-> (expect (count banners))
        (.toBe 2))
    ;; Original should be first
    (-> (expect (:message (first banners)))
        (.toBe "Processing..."))
    ;; New should be second
    (-> (expect (:message (second banners)))
        (.toBe "Done!"))))

(defn- test-show-system-banner-schedules-clear-for-specific-banner []
  (let [state {:ui/system-banners []}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.show-system-banner "success" "Saved!" {}])
        banner-id (:id (first (:ui/system-banners (:uf/db result))))
        defer-fx (some #(when (= :uf/fx.defer-dispatch (first %)) %) (:uf/fxs result))]
    ;; Should have defer dispatch effect
    (-> (expect defer-fx)
        (.toBeTruthy))
    ;; The deferred action should be clear with the specific ID
    (let [[_fx-name actions-list _delay] defer-fx
          [action-name action-id] (first actions-list)]
      (-> (expect action-name)
          (.toBe :banner/ax.clear-system-banner))
      (-> (expect action-id)
          (.toBe banner-id)))))

(defn- test-clear-system-banner-marks-specific-banner-as-leaving []
  (let [banner {:id "msg-1" :type "success" :message "Saved!"}
        state {:ui/system-banners [banner]}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.clear-system-banner "msg-1"])
        banners (:ui/system-banners (:uf/db result))
        updated-banner (first banners)]
    ;; Banner should be marked as leaving
    (-> (expect (:leaving updated-banner))
        (.toBe true))))

(defn- test-clear-system-banner-removes-banner-after-animation []
  (let [banner {:id "msg-1" :type "success" :message "Saved!" :leaving true}
        state {:ui/system-banners [banner]}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.clear-system-banner "msg-1"])
        banners (:ui/system-banners (:uf/db result))]
    ;; Banner should be removed
    (-> (expect (count banners))
        (.toBe 0))))

(defn- test-clear-system-banner-only-affects-target-banner []
  (let [banner1 {:id "msg-1" :type "info" :message "A"}
        banner2 {:id "msg-2" :type "success" :message "B"}
        state {:ui/system-banners [banner1 banner2]}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.clear-system-banner "msg-1"])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should still have both banners
    (-> (expect (count banners))
        (.toBe 2))
    ;; First should be leaving
    (-> (expect (:leaving (first banners)))
        (.toBe true))
    ;; Second should be unchanged
    (-> (expect (:leaving (second banners)))
        (.toBeFalsy))))

(defn- test-clear-system-banner-schedules-removal-after-animation []
  (let [banner {:id "msg-1" :type "success" :message "Saved!"}
        state {:ui/system-banners [banner]}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.clear-system-banner "msg-1"])
        defer-fx (some #(when (= :uf/fx.defer-dispatch (first %)) %) (:uf/fxs result))]
    ;; Should have defer dispatch for removal after 250ms animation
    (-> (expect defer-fx)
        (.toBeTruthy))
    (let [[_fx-name _actions delay-ms] defer-fx]
      (-> (expect delay-ms)
          (.toBe 250)))))

(defn- test-show-system-banner-with-category-replaces-existing []
  (let [existing-banner {:id "msg-1" :type "info" :message "Connecting..." :category "connection"}
        state {:ui/system-banners [existing-banner]}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.show-system-banner "success" "Connected!" {} "connection"])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should still have one banner (replaced, not appended)
    (-> (expect (count banners))
        (.toBe 1))
    ;; New banner should have new message
    (-> (expect (:message (first banners)))
        (.toBe "Connected!"))
    ;; Old banner should be marked as leaving
    (-> (expect (:leaving existing-banner))
        (.toBeFalsy))))

(defn- test-show-system-banner-with-category-does-not-replace-different []
  (let [existing-banner {:id "msg-1" :type "info" :message "Loading..." :category "loading"}
        state {:ui/system-banners [existing-banner]}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.show-system-banner "success" "Connected!" {} "connection"])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should have two banners (different categories)
    (-> (expect (count banners))
        (.toBe 2))))

(defn- test-show-system-banner-without-category-appends-normally []
  (let [existing-banner {:id "msg-1" :type "info" :message "Connecting..." :category "connection"}
        state {:ui/system-banners [existing-banner]}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.show-system-banner "success" "Saved!" {}])
        banners (:ui/system-banners (:uf/db result))]
    ;; Should have two banners (no category match to replace)
    (-> (expect (count banners))
        (.toBe 2))))

(defn- test-clear-system-banner-no-op-when-banner-not-found []
  (let [state {:ui/system-banners [{:id "msg-other" :type "info" :message "Other"}]}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.clear-system-banner "msg-nonexistent"])]
    ;; Should be undefined (no-op) - prevents infinite loop when banner was already removed
    (-> (expect result) (.toBeUndefined))
    ;; State should be unchanged
    (-> (expect (count (:ui/system-banners state)))
        (.toBe 1))))

;; ============================================================
;; Handle System Banner Tests (D-10)
;; ============================================================

(defn- test-handle-system-banner-simple-success []
  (let [state {:ui/system-bulk-names {}}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "test.cljs"}])
        dxs (:uf/dxs result)
        show-banner-dx (some #(when (= :banner/ax.show-system-banner (first %)) %) dxs)]
    ;; Should dispatch show-system-banner
    (-> (expect show-banner-dx) (.toBeTruthy))
    ;; Banner message should contain script name
    (-> (expect (second show-banner-dx)) (.toBe "success"))
    (-> (expect (nth show-banner-dx 2)) (.toBe "Script \"test.cljs\" saved"))))

(defn- test-handle-system-banner-error []
  (let [state {:ui/system-bulk-names {}}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.handle-system-banner
                                             {:event-type "error"
                                              :operation "save"
                                              :script-name "test.cljs"
                                              :error "Permission denied"}])
        dxs (:uf/dxs result)
        show-banner-dx (some #(when (= :banner/ax.show-system-banner (first %)) %) dxs)]
    ;; Should dispatch show-system-banner with error
    (-> (expect (second show-banner-dx)) (.toBe "error"))
    (-> (expect (nth show-banner-dx 2)) (.toBe "FS sync error: Permission denied"))))

(defn- test-handle-system-banner-error-save-marks-modified []
  (let [state {:ui/system-bulk-names {}
               :ui/recently-modified-scripts #{}}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.handle-system-banner
                                             {:event-type "error"
                                              :operation "save"
                                              :script-name "test.cljs"
                                              :error "Failed"}])
        dxs (:uf/dxs result)
        mark-dx (some #(when (= :ui/ax.mark-scripts-modified (first %)) %) dxs)]
    ;; Should dispatch mark-scripts-modified for error saves
    (-> (expect mark-dx) (.toBeTruthy))
    (-> (expect (second mark-dx)) (.toEqual ["test.cljs"]))))

(defn- test-handle-system-banner-unchanged-marks-modified []
  (let [state {:ui/system-bulk-names {}
               :ui/recently-modified-scripts #{}}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "test.cljs"
                                              :unchanged true}])
        dxs (:uf/dxs result)
        mark-dx (some #(when (= :ui/ax.mark-scripts-modified (first %)) %) dxs)]
    ;; Should dispatch mark-scripts-modified for unchanged saves
    (-> (expect mark-dx) (.toBeTruthy))))

(defn- test-handle-system-banner-bulk-tracks-name []
  (let [state {:ui/system-bulk-names {}}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "a.cljs"
                                              :bulk-id "bulk-1"
                                              :bulk-count 3
                                              :bulk-index 0}])
        new-state (:uf/db result)]
    ;; Should track bulk name in state
    (-> (expect (get-in new-state [:ui/system-bulk-names "bulk-1"]))
        (.toEqual ["a.cljs"]))))

(defn- test-handle-system-banner-bulk-final-shows-summary []
  (let [state {:ui/system-bulk-names {"bulk-1" ["a.cljs" "b.cljs"]}}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "c.cljs"
                                              :bulk-id "bulk-1"
                                              :bulk-count 3
                                              :bulk-index 2}])
        dxs (:uf/dxs result)
        show-banner-dx (some #(when (= :banner/ax.show-system-banner (first %)) %) dxs)
        bulk-info (nth show-banner-dx 3)]
    ;; Should show summary banner
    (-> (expect (nth show-banner-dx 2)) (.toBe "3 files saved"))
    ;; Bulk names should include all tracked names
    (-> (expect (:bulk-names bulk-info)) (.toEqual ["a.cljs" "b.cljs" "c.cljs"]))))

(defn- test-handle-system-banner-bulk-final-clears-names []
  (let [state {:ui/system-bulk-names {"bulk-1" ["a.cljs" "b.cljs"]}}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "c.cljs"
                                              :bulk-id "bulk-1"
                                              :bulk-count 3
                                              :bulk-index 2}])
        new-state (:uf/db result)]
    ;; Should clear bulk names after final
    (-> (expect (get-in new-state [:ui/system-bulk-names "bulk-1"]))
        (.toBeUndefined))))

(defn- test-handle-system-banner-bulk-intermediate-no-banner []
  (let [state {:ui/system-bulk-names {}}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "a.cljs"
                                              :bulk-id "bulk-1"
                                              :bulk-count 3
                                              :bulk-index 0}])
        dxs (:uf/dxs result)
        show-banner-dx (some #(when (= :banner/ax.show-system-banner (first %)) %) dxs)]
    ;; Intermediate bulk ops should not show banner
    (-> (expect show-banner-dx) (.toBeFalsy))))

(defn- test-handle-system-banner-no-state-read []
  ;; Verify the action does NOT require @!state - it works with passed state parameter
  (let [state {:ui/system-bulk-names {"bulk-1" ["existing.cljs"]}}
        result (popup-actions/handle-action state uf-data
                                            [:banner/ax.handle-system-banner
                                             {:event-type "success"
                                              :operation "save"
                                              :script-name "new.cljs"
                                              :bulk-id "bulk-1"
                                              :bulk-count 2
                                              :bulk-index 1}])
        dxs (:uf/dxs result)
        show-banner-dx (some #(when (= :banner/ax.show-system-banner (first %)) %) dxs)
        bulk-info (nth show-banner-dx 3)]
    ;; Bulk names should include both existing and newly tracked
    (-> (expect (:bulk-names bulk-info)) (.toEqual ["existing.cljs" "new.cljs"]))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "System banners"
          (fn []
            (test "appends banner to empty list" test-show-system-banner-appends-to-empty-list)
            (test "generates unique ID for each banner" test-show-system-banner-generates-unique-id)
            (test "appends to existing list" test-show-system-banner-appends-to-existing-list)
            (test "schedules clear for specific banner ID" test-show-system-banner-schedules-clear-for-specific-banner)
            (test "marks specific banner as leaving on first clear" test-clear-system-banner-marks-specific-banner-as-leaving)
            (test "removes banner after animation (already leaving)" test-clear-system-banner-removes-banner-after-animation)
            (test "only affects target banner when clearing" test-clear-system-banner-only-affects-target-banner)
            (test "schedules removal after 250ms animation delay" test-clear-system-banner-schedules-removal-after-animation)
            (test "with category replaces existing banner in same category" test-show-system-banner-with-category-replaces-existing)
            (test "with category does not replace banner in different category" test-show-system-banner-with-category-does-not-replace-different)
            (test "without category appends normally" test-show-system-banner-without-category-appends-normally)
            (test "no-op when clearing non-existent banner" test-clear-system-banner-no-op-when-banner-not-found)))

(describe "Handle System Banner (D-10)"
          (fn []
            (test "simple success dispatches show-system-banner" test-handle-system-banner-simple-success)
            (test "error dispatches banner with error message" test-handle-system-banner-error)
            (test "error save marks scripts modified" test-handle-system-banner-error-save-marks-modified)
            (test "unchanged save marks scripts modified" test-handle-system-banner-unchanged-marks-modified)
            (test "bulk operation tracks name in state" test-handle-system-banner-bulk-tracks-name)
            (test "bulk final shows summary banner with all names" test-handle-system-banner-bulk-final-shows-summary)
            (test "bulk final clears tracked names" test-handle-system-banner-bulk-final-clears-names)
            (test "bulk intermediate does not show banner" test-handle-system-banner-bulk-intermediate-no-banner)
            (test "bulk names computed from state parameter" test-handle-system-banner-no-state-read)))
