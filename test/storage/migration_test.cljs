(ns storage.migration-test
  "Tests for storage schema migration logic."
  (:require ["vitest" :refer [describe test expect]]
            [storage :as storage]))

;; ============================================================
;; Test Functions
;; ============================================================

(defn- test-migrates-unversioned-storage []
  (let [result #js {:scripts #js []
                    :granted-origins #js ["https://example.com/*"]}
        normalized (storage/normalize-storage-result result)]
    (-> (expect (:storage/schema-version normalized))
        (.toBe 1))
    (-> (expect (:storage/granted-origins normalized))
        (.toEqual ["https://example.com/*"]))
    (-> (expect (:storage/remove-keys normalized))
        (.toEqual ["granted-origins"]))
    (-> (expect (:storage/migrated? normalized))
        (.toBe true))))

(defn- test-keeps-versioned-storage-unchanged []
  (let [result #js {:schemaVersion 1
                    :scripts #js []
                    :grantedOrigins #js ["https://example.com/*"]}
        normalized (storage/normalize-storage-result result)]
    (-> (expect (:storage/schema-version normalized))
        (.toBe 1))
    (-> (expect (:storage/granted-origins normalized))
        (.toEqual ["https://example.com/*"]))
    (-> (expect (:storage/remove-keys normalized))
        (.toEqual []))
    (-> (expect (:storage/migrated? normalized))
        (.toBe false))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "storage schema migration"
          (fn []
            (test "migrates unversioned storage and renames granted-origins" test-migrates-unversioned-storage)
            (test "keeps versioned storage unchanged when already v1" test-keeps-versioned-storage-unchanged)))
