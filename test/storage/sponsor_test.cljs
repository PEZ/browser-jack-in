(ns storage.sponsor-test
  "Tests for sponsor status derivation logic."
  (:require ["vitest" :refer [describe test expect]]
            [storage :as storage]))

;; ============================================================
;; Test Functions
;; ============================================================

(def ^:private ninety-days-ms (* 90 24 60 60 1000))

(defn- test-sponsor-active-recent-check []
  (let [now 1000000000000
        db {:sponsor/status true
            :sponsor/checked-at (- now (* 1 24 60 60 1000))}]
    (-> (expect (storage/sponsor-active? db now))
        (.toBe true))))

(defn- test-sponsor-expired-check []
  (let [now 1000000000000
        db {:sponsor/status true
            :sponsor/checked-at (- now (* 91 24 60 60 1000))}]
    (-> (expect (storage/sponsor-active? db now))
        (.toBe false))))

(defn- test-sponsor-not-a-sponsor []
  (let [now 1000000000000
        db {:sponsor/status false
            :sponsor/checked-at (- now (* 1 24 60 60 1000))}]
    (-> (expect (storage/sponsor-active? db now))
        (.toBe false))))

(defn- test-sponsor-missing-checked-at []
  (let [now 1000000000000
        db {:sponsor/status true
            :sponsor/checked-at nil}]
    (-> (expect (storage/sponsor-active? db now))
        (.toBe false))))

(defn- test-sponsor-both-missing []
  (let [now 1000000000000
        db {}]
    (-> (expect (storage/sponsor-active? db now))
        (.toBe false))))

(defn- test-sponsor-exactly-at-expiry-boundary []
  (let [now 1000000000000
        db {:sponsor/status true
            :sponsor/checked-at (- now ninety-days-ms)}]
    (-> (expect (storage/sponsor-active? db now))
        (.toBe false))))

(defn- test-sponsor-just-before-expiry []
  (let [now 1000000000000
        db {:sponsor/status true
            :sponsor/checked-at (- now (dec ninety-days-ms))}]
    (-> (expect (storage/sponsor-active? db now))
        (.toBe true))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "Sponsor status derivation"
          (fn []
            (test "active sponsor with recent check" test-sponsor-active-recent-check)
            (test "expired sponsor (checked > 90 days ago)" test-sponsor-expired-check)
            (test "not a sponsor (status false)" test-sponsor-not-a-sponsor)
            (test "missing checked-at timestamp" test-sponsor-missing-checked-at)
            (test "both status and checked-at missing" test-sponsor-both-missing)
            (test "exactly at expiry boundary (90 days) is expired" test-sponsor-exactly-at-expiry-boundary)
            (test "just before expiry boundary is active" test-sponsor-just-before-expiry)))
