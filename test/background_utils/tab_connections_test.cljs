(ns background-utils.tab-connections-test
  (:require [background-utils :as bg]
            ["vitest" :refer [describe test expect]]))

;; ============================================================
;; Test Functions
;; ============================================================

;; find-tab-on-port

(defn- test-find-tab-on-port-returns-nil-for-empty-connections []
  (-> (expect (bg/find-tab-on-port {} 1340 nil))
      (.toBeUndefined)))

(defn- test-find-tab-on-port-finds-tab-on-matching-port []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}
                     2 {:ws/port 1341 :ws/tab-title "Tab 2"}}]
    (-> (expect (bg/find-tab-on-port connections 1340 nil))
        (.toBe "1"))))

(defn- test-find-tab-on-port-excludes-specified-tab-id []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}
                     2 {:ws/port 1340 :ws/tab-title "Tab 2"}}]
    ;; Should find tab 2 when excluding tab 1
    (-> (expect (bg/find-tab-on-port connections 1340 "1"))
        (.toBe "2"))))

(defn- test-find-tab-on-port-returns-nil-when-port-not-found []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}}]
    (-> (expect (bg/find-tab-on-port connections 9999 nil))
        (.toBeUndefined))))

(defn- test-find-tab-on-port-returns-nil-when-only-match-excluded []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Tab 1"}}]
    (-> (expect (bg/find-tab-on-port connections 1340 "1"))
        (.toBeUndefined))))

;; connections->display-list

(defn- test-connections-display-list-returns-empty-array-for-empty-connections []
  (let [result (bg/connections->display-list {})]
    (-> (expect (count result))
        (.toBe 0))))

(defn- test-connections-display-list-transforms-connections-map-to-sorted-list []
  (let [connections {2 {:ws/port 1341 :ws/tab-title "Second"}
                     1 {:ws/port 1340 :ws/tab-title "First"}}
        result (bg/connections->display-list connections)]
    (-> (expect (count result))
        (.toBe 2))
    ;; First item should have lower port (sorted)
    (-> (expect (:port (first result)))
        (.toBe 1340))
    (-> (expect (:title (first result)))
        (.toBe "First"))
    (-> (expect (:tab-id (first result)))
        (.toBe "1"))))

(defn- test-connections-display-list-uses-unknown-for-missing-title []
  (let [connections {1 {:ws/port 1340}}
        result (bg/connections->display-list connections)]
    (-> (expect (:title (first result)))
        (.toBe "Unknown"))))

(defn- test-connections-display-list-includes-favicon-when-present []
  (let [connections {1 {:ws/port 1340
                        :ws/tab-title "GitHub"
                        :ws/tab-favicon "https://github.com/favicon.ico"}}
        result (bg/connections->display-list connections)]
    (-> (expect (:favicon (first result)))
        (.toBe "https://github.com/favicon.ico"))))

(defn- test-connections-display-list-favicon-nil-when-missing []
  (let [connections {1 {:ws/port 1340 :ws/tab-title "Test"}}
        result (bg/connections->display-list connections)]
    (-> (expect (:favicon (first result)))
        (.toBeUndefined))))

;; tab-in-history?

(defn- test-tab-in-history-returns-true-when-tab-id-in-history []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/tab-in-history? history "123"))
        (.toBe true))))

(defn- test-tab-in-history-returns-false-when-tab-id-not-in-history []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/tab-in-history? history "789"))
        (.toBe false))))

(defn- test-tab-in-history-returns-false-for-empty-history []
  (-> (expect (bg/tab-in-history? {} "123"))
      (.toBe false)))

(defn- test-tab-in-history-returns-false-for-nil-history []
  (-> (expect (bg/tab-in-history? nil "123"))
      (.toBe false)))

;; get-history-port

(defn- test-get-history-port-returns-port-for-existing-tab []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/get-history-port history "123"))
        (.toBe 1340))))

(defn- test-get-history-port-returns-port-for-another-existing-tab []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/get-history-port history "456"))
        (.toBe 1341))))

(defn- test-get-history-port-returns-nil-for-non-existent-tab []
  (let [history {"123" {:port 1340}
                 "456" {:port 1341}}]
    (-> (expect (bg/get-history-port history "789"))
        (.toBeUndefined))))

(defn- test-get-history-port-returns-nil-for-empty-history []
  (-> (expect (bg/get-history-port {} "123"))
      (.toBeUndefined)))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "find-tab-on-port"
          (fn []
            (test "returns nil for empty connections" test-find-tab-on-port-returns-nil-for-empty-connections)
            (test "finds tab on matching port" test-find-tab-on-port-finds-tab-on-matching-port)
            (test "excludes specified tab-id" test-find-tab-on-port-excludes-specified-tab-id)
            (test "returns nil when port not found" test-find-tab-on-port-returns-nil-when-port-not-found)
            (test "returns nil when only match is excluded" test-find-tab-on-port-returns-nil-when-only-match-excluded)))

(describe "connections->display-list"
          (fn []
            (test "returns empty array for empty connections" test-connections-display-list-returns-empty-array-for-empty-connections)
            (test "transforms connections map to sorted list" test-connections-display-list-transforms-connections-map-to-sorted-list)
            (test "uses 'Unknown' for missing title" test-connections-display-list-uses-unknown-for-missing-title)
            (test "includes favicon when present" test-connections-display-list-includes-favicon-when-present)
            (test "favicon is nil when missing" test-connections-display-list-favicon-nil-when-missing)))

(describe "tab-in-history?"
          (fn []
            (test "returns true when tab-id is in history" test-tab-in-history-returns-true-when-tab-id-in-history)
            (test "returns false when tab-id is not in history" test-tab-in-history-returns-false-when-tab-id-not-in-history)
            (test "returns false for empty history" test-tab-in-history-returns-false-for-empty-history)
            (test "returns false for nil history" test-tab-in-history-returns-false-for-nil-history)))

(describe "get-history-port"
          (fn []
            (test "returns port for existing tab" test-get-history-port-returns-port-for-existing-tab)
            (test "returns port for another existing tab" test-get-history-port-returns-port-for-another-existing-tab)
            (test "returns nil for non-existent tab" test-get-history-port-returns-nil-for-non-existent-tab)
            (test "returns nil for empty history" test-get-history-port-returns-nil-for-empty-history)))
