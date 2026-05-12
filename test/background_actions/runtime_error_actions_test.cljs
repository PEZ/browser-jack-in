(ns background-actions.runtime-error-actions-test
  "Tests for runtime error action handlers"
  (:require ["vitest" :refer [describe test expect vi]]
            [background-actions :as bg-actions]
            [dep-resolver :as dep-resolver]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def base-script
  {:script/id "script-123"
   :script/name "test.cljs"
   :script/description "Test script"
   :script/match ["*://example.com/*"]
   :script/code "(println \"hello\")"
   :script/enabled true
   :script/created "2026-01-01T00:00:00.000Z"
   :script/modified "2026-01-01T00:00:00.000Z"
   :script/run-at "document-idle"
   :script/inject []})

(def initial-state
  {:storage/scripts [base-script]
   :storage/granted-origins []
   :storage/ext-dep-cache {}})

(def uf-data {:system/now 1737100000000})

(def runtime-state
  (assoc initial-state :runtime/errors {}))

(def sample-errors
  [{:error/type "library/not-found"
    :error/phase "resolve"
    :error/script-name "my/tweaks.cljs"
    :error/dep-raw "epupp://missing.js"
    :error/message "Library not found: missing.js"}
   {:error/type "library/not-found"
    :error/phase "resolve"
    :error/script-name "my/other.cljs"
    :error/dep-raw "epupp://gone.js"
    :error/message "Library not found: gone.js"}])

(defn- find-banner-broadcast-effect
  [result]
  (some #(when (= :banner/fx.broadcast-system (first %)) %)
        (:uf/fxs result)))

;; ============================================================
;; Runtime Error Actions Tests
;; ============================================================

(defn- test-set-tab-errors-stores-errors-by-script-name []
  (let [result (bg-actions/handle-action runtime-state uf-data
                 [:runtime/ax.set-tab-errors 42 sample-errors])
        new-state (:uf/db result)
        tab-errors (get-in new-state [:runtime/errors 42])]
    (-> (expect (count tab-errors)) (.toBe 2))
    (-> (expect (get-in tab-errors ["my/tweaks.cljs" :error/dep-raw])) (.toBe "epupp://missing.js"))
    (-> (expect (get-in tab-errors ["my/other.cljs" :error/dep-raw])) (.toBe "epupp://gone.js"))))

(defn- test-set-tab-errors-broadcasts-status []
  (let [result (bg-actions/handle-action runtime-state uf-data
                 [:runtime/ax.set-tab-errors 42 sample-errors])
        [fx-name fx-tab-id fx-errors] (first (:uf/fxs result))]
    (-> (expect fx-name) (.toBe :runtime/fx.broadcast-tab-status))
    (-> (expect fx-tab-id) (.toBe 42))
    (-> (expect (count fx-errors)) (.toBe 2))))

(defn- test-set-tab-errors-replaces-previous-errors []
  (let [state-with-errors (assoc-in runtime-state [:runtime/errors 42]
                                    {"old.cljs" {:error/message "old error"}})
        result (bg-actions/handle-action state-with-errors uf-data
                 [:runtime/ax.set-tab-errors 42 [(first sample-errors)]])
        tab-errors (get-in (:uf/db result) [:runtime/errors 42])]
    (-> (expect (get tab-errors "old.cljs")) (.toBeUndefined))
    (-> (expect (get-in tab-errors ["my/tweaks.cljs" :error/message])) (.toBe "Library not found: missing.js"))))

(defn- test-set-tab-errors-empty-clears-tab []
  (let [state-with-errors (assoc-in runtime-state [:runtime/errors 42]
                                    {"my/tweaks.cljs" {:error/message "err"}})
        result (bg-actions/handle-action state-with-errors uf-data
                 [:runtime/ax.set-tab-errors 42 []])
        tab-errors (get-in (:uf/db result) [:runtime/errors 42])]
    (-> (expect (count tab-errors)) (.toBe 0))))

(defn- test-get-tab-errors-returns-errors-for-tab []
  (let [state (assoc-in runtime-state [:runtime/errors 42]
                        {"my/tweaks.cljs" {:error/message "err"}})
        mock-response (fn [])
        result (bg-actions/handle-action state uf-data
                 [:runtime/ax.get-tab-errors mock-response 42])
        [fx-name fx-response response-data] (first (:uf/fxs result))]
    (-> (expect fx-name) (.toBe :msg/fx.send-response))
    (-> (expect fx-response) (.toBe mock-response))
    (-> (expect (:success response-data)) (.toBe true))
    (-> (expect (count (:errors response-data))) (.toBe 1))))

(defn- test-get-tab-errors-returns-empty-for-unknown-tab []
  (let [mock-response (fn [])
        result (bg-actions/handle-action runtime-state uf-data
                 [:runtime/ax.get-tab-errors mock-response 99])
        [_fx-name _fx-response response-data] (first (:uf/fxs result))]
    (-> (expect (:success response-data)) (.toBe true))
    (-> (expect (count (:errors response-data))) (.toBe 0))))

(defn- test-nav-handle-navigation-clears-tab-errors []
  (let [state (-> runtime-state
                  (assoc-in [:runtime/errors 42] {"script.cljs" {:error/message "err"}})
                  (assoc :connected-tabs/history {}))
        result (bg-actions/handle-action state uf-data
                 [:nav/ax.handle-navigation 42 "https://example.com"])
        new-state (:uf/db result)]
    (-> (expect (get (:runtime/errors new-state) 42)) (.toBeUndefined))
    (let [broadcast-fx (some #(when (= :runtime/fx.broadcast-tab-status (first %)) %) (:uf/fxs result))]
      (-> (expect broadcast-fx) (.toBeTruthy))
      (-> (expect (nth broadcast-fx 2)) (.toEqual {})))))

(defn- test-tab-handle-removed-clears-tab-errors []
  (let [state (-> runtime-state
                  (assoc-in [:runtime/errors 42] {"script.cljs" {:error/message "err"}})
                  (assoc :ws/connections {}))
        result (bg-actions/handle-action state uf-data
                 [:tab/ax.handle-removed 42])
        new-state (:uf/db result)]
    (-> (expect (get (:runtime/errors new-state) 42)) (.toBeUndefined))))

(defn- test-re-resolve-on-change-does-not-broadcast-unchanged-runtime-error []
  (let [script-with-deps (assoc base-script
                                :script/name "my/tweaks.cljs"
                                :script/inject ["epupp://missing.js"])
        existing-error (first sample-errors)
        state (assoc runtime-state
                     :runtime/errors {42 {"my/tweaks.cljs" existing-error}})
        original-resolve dep-resolver/resolve-execution-plan
        resolver-spy (.spyOn vi dep-resolver "resolve_execution_plan")]
    (try
      (.mockImplementation resolver-spy
                           (fn [_scripts-with-deps _all-scripts _cache]
                             {:plan/errors [existing-error]}))
      (let [result (bg-actions/handle-action state uf-data
                     [:runtime/ax.re-resolve-on-change [script-with-deps]])
            set-tab-errors-fx (some #(when (= :runtime/fx.set-tab-errors (first %)) %)
                                    (:uf/fxs result))]
        (-> (expect (find-banner-broadcast-effect result)) (.toBeFalsy))
        (-> (expect set-tab-errors-fx) (.toBeTruthy))
        (-> (expect (nth set-tab-errors-fx 1)) (.toBe "42"))
        (-> (expect (nth set-tab-errors-fx 2)) (.toEqual [existing-error])))
      (finally
        (.mockImplementation resolver-spy original-resolve)
        (.mockRestore resolver-spy)))))

(defn- test-re-resolve-on-change-broadcasts-new-runtime-error []
  (let [script-with-deps (assoc base-script
                                :script/name "my/tweaks.cljs"
                                :script/inject ["epupp://missing.js" "epupp://other.js"])
        existing-error (first sample-errors)
        new-error {:error/type "library/not-found"
                   :error/phase "resolve"
                   :error/script-name "my/tweaks.cljs"
                   :error/dep-raw "epupp://other.js"
                   :error/message "Library not found: other.js"}
        state (assoc runtime-state
                     :runtime/errors {42 {"my/tweaks.cljs" existing-error}})
        original-resolve dep-resolver/resolve-execution-plan
        resolver-spy (.spyOn vi dep-resolver "resolve_execution_plan")]
    (try
      (.mockImplementation resolver-spy
                           (fn [_scripts-with-deps _all-scripts _cache]
                             {:plan/errors [new-error]}))
      (let [result (bg-actions/handle-action state uf-data
                     [:runtime/ax.re-resolve-on-change [script-with-deps]])
            banner-fx (find-banner-broadcast-effect result)]
        (-> (expect banner-fx) (.toBeTruthy))
        (-> (expect (second banner-fx))
            (.toEqual {:event-type "error"
                       :operation "library-resolution"
                       :error "Library not found: other.js"
                       :errors ["Library not found: other.js"]})))
      (finally
        (.mockImplementation resolver-spy original-resolve)
        (.mockRestore resolver-spy)))))

(describe "runtime error actions"
          (fn []
            (test "set-tab-errors stores errors keyed by script name"
                  test-set-tab-errors-stores-errors-by-script-name)
            (test "set-tab-errors broadcasts runtime status"
                  test-set-tab-errors-broadcasts-status)
            (test "set-tab-errors replaces previous errors"
                  test-set-tab-errors-replaces-previous-errors)
            (test "set-tab-errors with empty list clears tab"
                  test-set-tab-errors-empty-clears-tab)
            (test "get-tab-errors returns errors for known tab"
                  test-get-tab-errors-returns-errors-for-tab)
            (test "get-tab-errors returns empty for unknown tab"
                  test-get-tab-errors-returns-empty-for-unknown-tab)
            (test "navigation clears tab runtime errors"
                  test-nav-handle-navigation-clears-tab-errors)
            (test "tab removed clears tab runtime errors"
                  test-tab-handle-removed-clears-tab-errors)
            (test "re-resolve skips banner for unchanged runtime error"
                  test-re-resolve-on-change-does-not-broadcast-unchanged-runtime-error)
            (test "re-resolve broadcasts banner for new runtime error"
                  test-re-resolve-on-change-broadcasts-new-runtime-error)))
