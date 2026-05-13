(ns script-utils.storage-test
  "Tests for script->js serialization and storage contract pinning."
  (:require ["vitest" :refer [describe test expect]]
            [manifest-parser :as mp]
            [script-utils :as script-utils]))

;; ============================================================
;; Shared test data
;; ============================================================

(def full-test-script
  "Canonical test script with all fields for storage contract testing."
  {:script/id "script-1"
   :script/name "test.cljs"
   :script/description "Test"
   :script/match ["*://example.com/*"]
   :script/code "(ns test)"
   :script/enabled true
   :script/created "2026-01-01T00:00:00.000Z"
   :script/modified "2026-01-02T00:00:00.000Z"
   :script/run-at "document-end"
   :script/inject ["scittle://reagent.js"]
   :script/builtin? false
   :script/always-enabled? false
   :script/special? true
   :script/web-installer-scan true})

;; ============================================================
;; script->js tests
;; ============================================================

(defn- test-script-js-stores-only-primary-fields []
  (let [script (assoc full-test-script :script/builtin? true)
        js-script (script-utils/script->js script)]
    ;; runAt and match stored for early injection loader
    (-> (expect (aget js-script "runAt"))
        (.toBe "document-end"))
    (-> (expect (aget js-script "match"))
        (.toEqual #js ["*://example.com/*"]))
    ;; Derived fields NOT stored (re-derived from manifest on load)
    (-> (expect (aget js-script "name"))
        (.toBeUndefined))
    (-> (expect (aget js-script "description"))
        (.toBeUndefined))
    (-> (expect (aget js-script "inject"))
        (.toBeUndefined))
    (-> (expect (.-builtin js-script))
        (.toBe true))))

(defn- assert-script-flags-roundtrip [script expect-special? expect-web-installer-scan?]
  (let [js-script (script-utils/script->js script)
        parsed (first (script-utils/parse-scripts #js [js-script]))]
    (if expect-special?
      (-> (expect (.-special js-script)) (.toBe true))
      (-> (expect (.-special js-script)) (.toBeFalsy)))
    (if expect-web-installer-scan?
      (-> (expect (.-webInstallerScan js-script)) (.toBe true))
      (-> (expect (.-webInstallerScan js-script)) (.toBeFalsy)))
    (if expect-special?
      (-> (expect (:script/special? parsed)) (.toBe true))
      (-> (expect (:script/special? parsed)) (.toBeUndefined)))
    (if expect-web-installer-scan?
      (-> (expect (:script/web-installer-scan parsed)) (.toBe true))
      (-> (expect (:script/web-installer-scan parsed)) (.toBeUndefined)))))

(describe "script->js"
          (fn []
            (test "stores only primary fields, not derived" test-script-js-stores-only-primary-fields)
            (test "roundtrips special flags through parse-scripts"
                  (fn []
                    (assert-script-flags-roundtrip
                     {:script/id "installer-1"
                      :script/code "(ns installer)"
                      :script/enabled false
                      :script/created "2026-01-01T00:00:00.000Z"
                      :script/modified "2026-01-02T00:00:00.000Z"
                      :script/builtin? true
                      :script/special? true
                      :script/web-installer-scan true}
                     true
                     true)))
            (test "roundtrips scripts without special flags"
                  (fn []
                    (assert-script-flags-roundtrip
                     {:script/id "regular-1"
                      :script/code "(ns regular)"
                      :script/enabled true
                      :script/created "2026-01-01T00:00:00.000Z"
                      :script/modified "2026-01-02T00:00:00.000Z"
                      :script/builtin? false}
                     false
                     false)))))

;; ============================================================
;; Storage contract pinning
;; ============================================================

(defn- test-storage-contract-stored-fields []
  (let [js-obj (script-utils/script->js full-test-script)
        stored-keys (set (vec (js/Object.keys js-obj)))]
    ;; These fields ARE persisted to chrome.storage
    (-> (expect (contains? stored-keys "id")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "code")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "enabled")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "created")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "modified")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "builtin")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "alwaysEnabled")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "runAt")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "match")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "special")) (.toBeTruthy))
    (-> (expect (contains? stored-keys "webInstallerScan")) (.toBeTruthy))))

(defn- test-storage-contract-derived-fields-not-stored []
  (let [script (assoc full-test-script
                      :script/inject ["scittle://reagent.js" "epupp://my_lib.cljs"])
        js-obj (script-utils/script->js script)
        stored-keys (set (vec (js/Object.keys js-obj)))]
    ;; These fields are NOT stored - re-derived from manifest on load
    (-> (expect (contains? stored-keys "name")) (.toBeFalsy))
    (-> (expect (contains? stored-keys "description")) (.toBeFalsy))
    (-> (expect (contains? stored-keys "inject")) (.toBeFalsy))))

(defn- test-storage-contract-exact-field-count []
  (let [script {:script/id "script-1"
                :script/code "(ns test)"
                :script/enabled true
                :script/created "2026-01-01"
                :script/modified "2026-01-02"
                :script/builtin? false}
        js-obj (script-utils/script->js script)
        key-count (.-length (js/Object.keys js-obj))]
    ;; Exactly 11 fields: id, code, enabled, created, modified,
    ;; builtin, alwaysEnabled, special, webInstallerScan, runAt, match
    (-> (expect key-count) (.toBe 11))))

(defn- test-storage-contract-roundtrip-preserves-stored-fields []
  (let [script {:script/id "script-1"
                :script/code "^{:epupp/script-name \"test.cljs\"\n  :epupp/inject [\"scittle://reagent.js\" \"epupp://lib.cljs\"]}\n(ns test)"
                :script/enabled true
                :script/created "2026-01-01T00:00:00.000Z"
                :script/modified "2026-01-02T00:00:00.000Z"
                :script/builtin? false
                :script/run-at "document-end"
                :script/match ["*://example.com/*"]}
        js-obj (script-utils/script->js script)
        parsed (first (script-utils/parse-scripts #js [js-obj]
                        {:extract-manifest mp/extract-manifest}))]
    ;; Stored fields survive roundtrip
    (-> (expect (:script/id parsed)) (.toBe "script-1"))
    (-> (expect (:script/enabled parsed)) (.toBe true))
    (-> (expect (:script/created parsed)) (.toBe "2026-01-01T00:00:00.000Z"))
    (-> (expect (:script/modified parsed)) (.toBe "2026-01-02T00:00:00.000Z"))
    ;; Derived fields re-derived from manifest
    (-> (expect (:script/name parsed)) (.toBe "test.cljs"))
    (-> (expect (:script/inject parsed))
        (.toEqual ["scittle://reagent.js" "epupp://lib.cljs"]))))

(describe "Storage contract"
          (fn []
            (test "script->js includes all stored fields" test-storage-contract-stored-fields)
            (test "script->js excludes derived fields (name, description, inject)" test-storage-contract-derived-fields-not-stored)
            (test "script->js produces exactly 11 fields" test-storage-contract-exact-field-count)
            (test "roundtrip through JS preserves stored and re-derives from manifest" test-storage-contract-roundtrip-preserves-stored-fields)))
