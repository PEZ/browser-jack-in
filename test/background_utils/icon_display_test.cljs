(ns background-utils.icon-display-test
  (:require [background-utils :as bg]
            ["vitest" :refer [describe test expect]]))

;; ============================================================
;; Test Functions
;; ============================================================

;; compute-display-icon-state - no tabs connected

(defn- test-compute-display-icon-returns-disconnected-for-empty-state []
  (-> (expect (bg/compute-display-icon-state {} nil))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-disconnected-when-active-tab-not-in-state []
  (-> (expect (bg/compute-display-icon-state {} 123))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-disconnected-when-active-tab-disconnected []
  (-> (expect (bg/compute-display-icon-state {123 "disconnected"} 123))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-disconnected-when-only-non-active-tabs-exist []
  (-> (expect (bg/compute-display-icon-state {456 "disconnected"} 123))
      (.toBe "disconnected")))

;; compute-display-icon-state - global connected state

(defn- test-compute-display-icon-returns-connected-when-active-tab-connected []
  (-> (expect (bg/compute-display-icon-state {123 "connected"} 123))
      (.toBe "connected")))

(defn- test-compute-display-icon-returns-connected-when-different-tab-connected-active-disconnected-alt []
  (-> (expect (bg/compute-display-icon-state {123 "disconnected" 456 "connected"} 123))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-connected-when-different-tab-connected-active-disconnected []
  (-> (expect (bg/compute-display-icon-state {123 "disconnected" 456 "connected"} 123))
      (.toBe "disconnected")))

(defn- test-compute-display-icon-returns-connected-when-different-tab-connected-active-not-in-state []
  (-> (expect (bg/compute-display-icon-state {456 "connected"} 123))
      (.toBe "disconnected")))

;; get-icon-paths

(defn- test-get-icon-paths-returns-connected-paths-for-connected-keyword []
  (let [paths (bg/get-icon-paths :connected)]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-connected-16.png"))))

(defn- test-get-icon-paths-returns-connected-paths-for-connected-string []
  (let [paths (bg/get-icon-paths "connected")]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-connected-16.png"))))

(defn- test-get-icon-paths-returns-disconnected-paths-for-disconnected-keyword []
  (let [paths (bg/get-icon-paths :disconnected)]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png"))))

(defn- test-get-icon-paths-returns-disconnected-paths-for-disconnected-string []
  (let [paths (bg/get-icon-paths "disconnected")]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png"))))

(defn- test-get-icon-paths-returns-disconnected-paths-for-nil []
  (let [paths (bg/get-icon-paths nil)]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png"))))

(defn- test-get-icon-paths-returns-disconnected-paths-for-unknown-state []
  (let [paths (bg/get-icon-paths "unknown")]
    (-> (expect (aget paths "16")) (.toBe "icons/icon-disconnected-16.png"))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "compute-display-icon-state - no tabs connected"
          (fn []
            (test "returns disconnected for empty state" test-compute-display-icon-returns-disconnected-for-empty-state)
            (test "returns disconnected when active tab not in state" test-compute-display-icon-returns-disconnected-when-active-tab-not-in-state)
            (test "returns disconnected when active tab is disconnected" test-compute-display-icon-returns-disconnected-when-active-tab-disconnected)
            (test "returns disconnected when only non-active tabs exist" test-compute-display-icon-returns-disconnected-when-only-non-active-tabs-exist)))

(describe "compute-display-icon-state - per-tab connected state"
          (fn []
            (test "returns connected when active tab is connected" test-compute-display-icon-returns-connected-when-active-tab-connected)
            (test "returns disconnected when different tab is connected, active is disconnected" test-compute-display-icon-returns-connected-when-different-tab-connected-active-disconnected-alt)
            (test "returns disconnected when different tab is connected, active is disconnected (dup)" test-compute-display-icon-returns-connected-when-different-tab-connected-active-disconnected)
            (test "returns disconnected when different tab is connected, active not in state" test-compute-display-icon-returns-connected-when-different-tab-connected-active-not-in-state)))

(describe "get-icon-paths"
          (fn []
            (test "returns connected paths for :connected keyword" test-get-icon-paths-returns-connected-paths-for-connected-keyword)
            (test "returns connected paths for 'connected' string" test-get-icon-paths-returns-connected-paths-for-connected-string)
            (test "returns disconnected paths for :disconnected keyword" test-get-icon-paths-returns-disconnected-paths-for-disconnected-keyword)
            (test "returns disconnected paths for 'disconnected' string" test-get-icon-paths-returns-disconnected-paths-for-disconnected-string)
            (test "returns disconnected paths for nil" test-get-icon-paths-returns-disconnected-paths-for-nil)
            (test "returns disconnected paths for unknown state" test-get-icon-paths-returns-disconnected-paths-for-unknown-state)))
