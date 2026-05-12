(ns background-actions.fs-crud-actions-test
  "Tests for background FS CRUD action handlers - rename, delete, save, base-info"
  (:require ["vitest" :refer [describe test expect]]
            [background-actions :as bg-actions]
            [background-actions.repl-fs-actions :as repl-fs-actions]))

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

(def builtin-script
  (assoc base-script
         :script/id "script-builtin-1"
         :script/name "gist-installer.cljs"
         :script/builtin? true))

(def initial-state
  {:storage/scripts [base-script]
   :storage/granted-origins []
   :storage/ext-dep-cache {}})

(def uf-data {:system/now 1737100000000})

;; ============================================================
;; Rename Script Tests
;; ============================================================

(defn- test-rename-rejects-when-source-script-not-found []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "nonexistent.cljs" "new-name.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "Script not found"))))

(defn- test-rename-rejects-when-source-is-builtin-script []
  (let [state {:storage/scripts [builtin-script]}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.rename-script "gist-installer.cljs" "renamed.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(defn- test-rename-rejects-when-target-name-already-exists []
  (let [other-script (assoc base-script :script/id "script-456" :script/name "existing.cljs")
        state {:storage/scripts [base-script other-script]}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.rename-script "test.cljs" "existing.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "already exists"))))

(defn- test-rename-rejects-reserved-namespace-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "epupp/test.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-rename-rejects-reserved-namespace-uppercase-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "EPUPP/test.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-rename-rejects-leading-slash-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "/test.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "start with '/"))))

(defn- test-rename-rejects-path-traversal-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "foo/../bar.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "./' or '../'"))))

(defn- test-rename-allows-rename-when-target-name-is-free []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "renamed.cljs"])]
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    (-> (expect (-> result :uf/db :storage/scripts first :script/name))
        (.toBe "renamed.cljs"))
    (-> (expect (some #(= :storage/fx.persist! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-rename-updates-modified-timestamp-on-rename []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.rename-script "test.cljs" "renamed.cljs"])
        modified (-> result :uf/db :storage/scripts first :script/modified)]
    (-> (expect modified)
        (.not.toBe "2026-01-01T00:00:00.000Z"))))

(defn- test-rename-force-overwrite-replaces-normal-target-preserving-source-data []
  (let [existing-script (assoc base-script
                               :script/id "script-456"
                               :script/name "existing.cljs"
                               :script/code "(println \"existing\")")
        state {:storage/scripts [base-script existing-script]}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.rename-script "test.cljs" "existing.cljs" true])
        renamed-scripts (->> result :uf/db :storage/scripts
                             (filter #(= (:script/name %) "existing.cljs")))
        renamed-script (first renamed-scripts)]
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    (-> (expect (count (-> result :uf/db :storage/scripts)))
        (.toBe 1))
    (-> (expect (count renamed-scripts))
        (.toBe 1))
    (-> (expect (:script/id renamed-script))
        (.toBe "script-123"))
    (-> (expect (:script/code renamed-script))
        (.toBe "(println \"hello\")"))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-rename-force-overwrite-rejects-built-in-target []
  (let [builtin-target (assoc builtin-script :script/name "existing.cljs")
        state {:storage/scripts [base-script builtin-target]}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.rename-script "test.cljs" "existing.cljs" true])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "Cannot overwrite built-in scripts"))))

(describe ":fs/ax.rename-script"
          (fn []
            (test "rejects when source script not found" test-rename-rejects-when-source-script-not-found)
            (test "rejects when source is builtin script" test-rename-rejects-when-source-is-builtin-script)
            (test "rejects when target name already exists" test-rename-rejects-when-target-name-already-exists)
            (test "rejects reserved namespace on rename" test-rename-rejects-reserved-namespace-on-rename)
            (test "rejects reserved namespace uppercase on rename" test-rename-rejects-reserved-namespace-uppercase-on-rename)
            (test "rejects leading slash on rename" test-rename-rejects-leading-slash-on-rename)
            (test "rejects path traversal on rename" test-rename-rejects-path-traversal-on-rename)
            (test "allows rename when target name is free" test-rename-allows-rename-when-target-name-is-free)
            (test "updates modified timestamp on rename" test-rename-updates-modified-timestamp-on-rename)
            (test "force overwrite replaces normal target while preserving source identity and content"
                  test-rename-force-overwrite-replaces-normal-target-preserving-source-data)
            (test "force overwrite rejects built-in target"
                  test-rename-force-overwrite-rejects-built-in-target)))

;; ============================================================
;; Guard Rename Script Tests
;; ============================================================

(defn- test-guard-rename-forwards-force-flag-when-fs-access-allowed []
  (let [send-response :send-response
        state {:fs/sync-tab-id 42
               :ws/connections {42 {:ws/socket :socket}}}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.guard-rename-script 42 send-response "test.cljs" "existing.cljs" true])
        dispatch-effect (some #(when (= :fs/fx.dispatch-action (first %)) %) (:uf/fxs result))]
    (-> (expect dispatch-effect)
        (.toEqual [:fs/fx.dispatch-action send-response
                   [:fs/ax.rename-script "test.cljs" "existing.cljs" true]]))))

(describe ":fs/ax.guard-rename-script"
          (fn []
            (test "forwards force flag when FS access is allowed"
                  test-guard-rename-forwards-force-flag-when-fs-access-allowed)))

;; ============================================================
;; Delete Script Tests
;; ============================================================

(defn- test-delete-rejects-when-script-not-found []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.delete-script "nonexistent.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "Not deleting non-existent file"))))

(defn- test-delete-rejects-when-script-is-builtin []
  (let [state {:storage/scripts [builtin-script]}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.delete-script "gist-installer.cljs"])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(defn- test-delete-allows-delete-and-removes-from-state []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.delete-script "test.cljs"])]
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    (-> (expect (count (-> result :uf/db :storage/scripts)))
        (.toBe 0))
    (-> (expect (some #(= :storage/fx.persist! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":fs/ax.delete-script"
          (fn []
            (test "rejects when script not found" test-delete-rejects-when-script-not-found)
            (test "rejects when script is builtin" test-delete-rejects-when-script-is-builtin)
            (test "allows delete and removes from state" test-delete-allows-delete-and-removes-from-state)))

;; ============================================================
;; Save Script Tests
;; ============================================================

(defn- test-save-rejects-when-updating-a-builtin-script []
  (let [state {:storage/scripts [builtin-script]}
        updated-script (assoc builtin-script :script/code "(new code)")
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script updated-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(defn- test-save-rejects-when-name-exists-and-not-force []
  (let [new-script {:script/id "script-new"
                    :script/name "test.cljs"
                    :script/code "(new code)"}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "already exists"))))

(defn- test-save-force-overwrite-preserves-existing-script-id []
  (let [new-script {:script/id "script-new"
                    :script/name "test.cljs"
                    :script/code "(new code)"
                    :script/force? true}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])]
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    (-> (expect (count (-> result :uf/db :storage/scripts)))
        (.toBe 1))
    (-> (expect (-> result :uf/db :storage/scripts first :script/id))
        (.toBe "script-123"))
    (-> (expect (-> result :uf/db :storage/scripts first :script/code))
        (.toBe "(new code)"))))

(defn- test-save-allows-create-when-name-is-new []
  (let [new-script {:script/id "script-new"
                    :script/name "brand-new.cljs"
                    :script/code "(new code)"}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])]
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    (-> (expect (count (-> result :uf/db :storage/scripts)))
        (.toBe 2))
    (-> (expect (some #(= :storage/fx.persist! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-save-allows-update-when-script-exists-by-id []
  (let [updated-script (assoc base-script :script/code "(updated code)")
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script updated-script])]
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    (-> (expect (count (-> result :uf/db :storage/scripts)))
        (.toBe 1))
    (-> (expect (-> result :uf/db :storage/scripts first :script/code))
        (.toBe "(updated code)"))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-save-allows-overwrite-when-force-flag-set []
  (let [new-script {:script/id "script-new"
                    :script/name "test.cljs"
                    :script/code "(overwrite code)"
                    :script/force? true}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])]
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-save-preserves-enabled-state-when-updating-existing-script []
  (let [updated-script {:script/id "script-123"
                        :script/name "test.cljs"
                        :script/code "(updated code)"
                        :script/enabled false}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script updated-script])
        saved-script (-> result :uf/db :storage/scripts first)]
    (-> (expect (:script/enabled saved-script))
        (.toBe true))))

(defn- test-save-defaults-new-scripts-to-disabled []
  (let [new-script {:script/id "script-new"
                    :script/name "brand-new.cljs"
                    :script/code "(new code)"}
        result (bg-actions/handle-action initial-state uf-data
                 [:fs/ax.save-script new-script])
        saved-script (->> result :uf/db :storage/scripts
                          (filter #(= (:script/id %) "script-new"))
                          first)]
    (-> (expect (:script/enabled saved-script))
        (.toBe false))))

(defn- test-save-allows-manual-only-script []
  (let [manual-script {:script/name "manual.cljs"
                       :script/code "(println \"manual script\")"
                       :script/match []}
        result (bg-actions/handle-action initial-state uf-data
                                         [:fs/ax.save-script manual-script])
        saved-script (->> result :uf/db :storage/scripts
                          (filter #(= (:script/name %) "manual.cljs"))
                          first)]
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    (-> (expect (:script/match saved-script))
        (.toEqual []))
    (-> (expect (:script/enabled saved-script))
        (.toBe false))
    (-> (expect (some #(= :storage/fx.persist! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":fs/ax.save-script"
          (fn []
            (test "rejects when updating a builtin script" test-save-rejects-when-updating-a-builtin-script)
            (test "rejects when name exists and not force (create case)" test-save-rejects-when-name-exists-and-not-force)
            (test "force overwrite preserves existing script ID" test-save-force-overwrite-preserves-existing-script-id)
            (test "allows create when name is new" test-save-allows-create-when-name-is-new)
            (test "allows update when script exists by ID (non-builtin)" test-save-allows-update-when-script-exists-by-id)
            (test "allows overwrite when force flag set" test-save-allows-overwrite-when-force-flag-set)
            (test "preserves enabled state when updating existing script" test-save-preserves-enabled-state-when-updating-existing-script)
            (test "defaults new scripts to disabled" test-save-defaults-new-scripts-to-disabled)
            (test "allows manual-only script" test-save-allows-manual-only-script)))

;; ============================================================
;; Base Info Return Shape Tests
;; ============================================================

(defn- test-base-info-excludes-transport-envelope-keys []
  (let [result (repl-fs-actions/script->base-info base-script)]
    (-> (expect (contains? result :requestId))
        (.toBe false))
    (-> (expect (contains? result :source))
        (.toBe false))
    (-> (expect (contains? result :type))
        (.toBe false))
    (-> (expect (contains? result :fs/name))
        (.toBe true))
    (-> (expect (contains? result :fs/created))
        (.toBe true))
    (-> (expect (contains? result :fs/modified))
        (.toBe true))))

(describe "script->base-info"
          (fn []
            (test "excludes transport envelope keys" test-base-info-excludes-transport-envelope-keys)))

;; ============================================================
;; Save Script - Built-in Name Protection Tests
;; ============================================================

(def builtin-with-display-name
  (assoc base-script
         :script/id "script-builtin-2"
         :script/name "GitHub Gist Installer (Built-in)"
         :script/builtin? true))

(defn- test-builtin-protection-rejects-creating-script-with-normalized-builtin-name []
  (let [state {:storage/scripts [builtin-with-display-name]}
        new-script {:script/id "script-attacker"
                    :script/name "github_gist_installer_built_in.cljs"
                    :script/code "(malicious code)"}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(defn- test-builtin-protection-rejects-creating-script-even-with-force-flag []
  (let [state {:storage/scripts [builtin-with-display-name]}
        new-script {:script/id "script-attacker"
                    :script/name "github_gist_installer_built_in.cljs"
                    :script/code "(malicious code)"
                    :script/force? true}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "built-in"))))

(describe ":fs/ax.save-script - built-in name protection"
          (fn []
            (test "rejects when creating script with normalized builtin name" test-builtin-protection-rejects-creating-script-with-normalized-builtin-name)
            (test "rejects when creating script with normalized builtin name even with force flag" test-builtin-protection-rejects-creating-script-even-with-force-flag)))

;; ============================================================
;; epupp/ Namespace Reservation Tests
;; ============================================================

(defn- test-epupp-namespace-rejects-uppercase-epupp-prefix []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-attacker"
                    :script/name "EPUPP/my-script.cljs"
                    :script/code "(println \"test\")"}
        result (bg-actions/handle-action state uf-data
                                         [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-epupp-namespace-rejects-when-creating-script-with-epupp-prefix []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-attacker"
                    :script/name "epupp/my-script.cljs"
                    :script/code "(println \"test\")"}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-epupp-namespace-rejects-epupp-prefix-even-with-force-flag []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-attacker"
                    :script/name "epupp/test.cljs"
                    :script/code "(println \"test\")"
                    :script/force? true}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-epupp-namespace-rejects-epupp-built-in-prefix []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-attacker"
                    :script/name "epupp/built-in/fake.cljs"
                    :script/code "(println \"test\")"}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        error-response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect error-response)
        (.toBeTruthy))
    (-> (expect (:success error-response))
        (.toBe false))
    (-> (expect (:error error-response))
        (.toContain "reserved namespace"))))

(defn- test-epupp-namespace-allows-scripts-with-epupp-elsewhere-in-name []
  (let [state {:storage/scripts []}
        new-script {:script/id "script-ok"
                    :script/name "my-epupp-helper.cljs"
                    :script/code "(println \"test\")"}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])]
    (-> (expect (:uf/db result))
        (.toBeTruthy))
    (-> (expect (some #(and (= :bg/fx.send-response (first %))
                            (-> % second :success)) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":fs/ax.save-script - epupp/ namespace reservation"
          (fn []
            (test "rejects when creating script with epupp/ prefix" test-epupp-namespace-rejects-when-creating-script-with-epupp-prefix)
            (test "rejects uppercase EPUPP/ prefix (case-bypass)" test-epupp-namespace-rejects-uppercase-epupp-prefix)
            (test "rejects epupp/ prefix even with force flag" test-epupp-namespace-rejects-epupp-prefix-even-with-force-flag)
            (test "rejects epupp/built-in/ prefix (deep nesting)" test-epupp-namespace-rejects-epupp-built-in-prefix)
            (test "allows scripts with epupp elsewhere in name" test-epupp-namespace-allows-scripts-with-epupp-elsewhere-in-name)))

;; ============================================================
;; Save Script - Manifest-Derived Fields in Response Tests
;; ============================================================

(defn- test-save-manifest-derived-fields-save-response-includes-description-from-manifest []
  (let [state {:storage/scripts []}
        code-with-description "{:epupp/script-name \"with_description.cljs\"
 :epupp/description \"A helpful script\"}

(ns my-script)
(println \"hello\")"
        new-script {:script/id "script-with-desc"
                    :script/name "with_description.cljs"
                    :script/code code-with-description}
        result (bg-actions/handle-action state uf-data
                 [:fs/ax.save-script new-script])
        response (some #(when (= :bg/fx.send-response (first %)) (second %)) (:uf/fxs result))]
    (-> (expect (:success response))
        (.toBe true))
    (-> (expect (:fs/description response))
        (.toBe "A helpful script"))))

(describe ":fs/ax.save-script - manifest-derived fields in response"
          (fn []
            (test "save response includes :fs/description from manifest" test-save-manifest-derived-fields-save-response-includes-description-from-manifest)))

;; ============================================================
;; Base Info Return Shape Expanded Tests
;; ============================================================

(defn- test-base-info-required-fields-present []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/name result))
        (.toBe "test.cljs"))
    (-> (expect (:fs/created result))
        (.toBe "2026-01-15T10:00:00.000Z"))
    (-> (expect (:fs/modified result))
        (.toBe "2026-01-15T12:00:00.000Z"))))

(defn- test-base-info-optional-fields-omitted-when-nil []
  (let [script {:script/id "script-1"
                :script/name "minimal.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/description nil
                :script/run-at nil
                :script/inject nil
                :script/match nil}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/description))
        (.toBe false))
    (-> (expect (contains? result :fs/run-at))
        (.toBe false))
    (-> (expect (contains? result :fs/inject))
        (.toBe false))
    (-> (expect (contains? result :fs/auto-run-match))
        (.toBe false))
    (-> (expect (contains? result :fs/enabled?))
        (.toBe false))))

(defn- test-base-info-match-patterns-as-vector []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/match ["https://github.com/*" "https://gitlab.com/*"]
                :script/enabled true}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/auto-run-match result))
        (.toEqual ["https://github.com/*" "https://gitlab.com/*"]))))

(defn- test-base-info-auto-run-and-enabled-when-script-has-patterns []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/match ["https://example.com/*"]
                :script/enabled true}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/auto-run-match))
        (.toBe true))
    (-> (expect (contains? result :fs/enabled?))
        (.toBe true))
    (-> (expect (:fs/enabled? result))
        (.toBe true))))

(defn- test-base-info-auto-run-and-enabled-omitted-when-no-patterns []
  (let [script {:script/id "script-1"
                :script/name "manual.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/match []
                :script/enabled false}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/auto-run-match))
        (.toBe false))
    (-> (expect (contains? result :fs/enabled?))
        (.toBe false))))

(defn- test-base-info-description-when-present []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/description "A helpful script"}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/description result))
        (.toBe "A helpful script"))))

(defn- test-base-info-run-at-when-present []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/run-at "document-start"}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/run-at result))
        (.toBe "document-start"))))

(defn- test-base-info-inject-when-present []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/inject ["scittle://reagent.js" "scittle://re-frame.js"]}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (:fs/inject result))
        (.toEqual ["scittle://reagent.js" "scittle://re-frame.js"]))))

(defn- test-base-info-empty-description-omitted []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/description ""}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/description))
        (.toBe false))))

(defn- test-base-info-empty-inject-omitted []
  (let [script {:script/id "script-1"
                :script/name "test.cljs"
                :script/created "2026-01-15T10:00:00.000Z"
                :script/modified "2026-01-15T12:00:00.000Z"
                :script/code "(println \"test\")"
                :script/inject []}
        result (repl-fs-actions/script->base-info script)]
    (-> (expect (contains? result :fs/inject))
        (.toBe false))))

(describe "script->base-info - response shape validation"
          (fn []
            (test "required fields present" test-base-info-required-fields-present)
            (test "optional fields omitted when nil" test-base-info-optional-fields-omitted-when-nil)
            (test "match patterns as vector" test-base-info-match-patterns-as-vector)
            (test "auto-run and enabled when script has patterns" test-base-info-auto-run-and-enabled-when-script-has-patterns)
            (test "auto-run and enabled omitted when no patterns" test-base-info-auto-run-and-enabled-omitted-when-no-patterns)
            (test "description when present" test-base-info-description-when-present)
            (test "run-at when present" test-base-info-run-at-when-present)
            (test "inject when present" test-base-info-inject-when-present)
            (test "empty description omitted" test-base-info-empty-description-omitted)
            (test "empty inject omitted" test-base-info-empty-inject-omitted)))
