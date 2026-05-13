(ns background-utils.connection-decisions-test
  (:require [background-utils :as bg]
            ["vitest" :refer [describe test expect]]))

;; ============================================================
;; decide-auto-connection Tests
;; ============================================================

;; Context shape:
;; {:nav/auto-connect-enabled? bool
;;  :nav/auto-reconnect-enabled? bool
;;  :nav/in-history? bool
;;  :nav/history-port string-or-nil
;;  :nav/saved-port string}

;; Decision shape:
;; {:decision :connect-all|:reconnect|:none
;;  :port string-or-nil}

(defn- check-auto-connection
  "Calls decide-auto-connection with ctx and asserts the decision and optional port."
  [ctx expected-decision expected-port]
  (let [result (bg/decide-auto-connection ctx)]
    (-> (expect (:decision result)) (.toBe expected-decision))
    (when expected-port
      (-> (expect (:port result)) (.toBe expected-port)))))

(defn- check-connection
  "Calls decide-connection with ctx and asserts the decision and optional port."
  [ctx expected-decision expected-port]
  (let [result (bg/decide-connection ctx)]
    (-> (expect (:decision result)) (.toBe expected-decision))
    (when expected-port
      (-> (expect (:port result)) (.toBe expected-port)))))

(defn- test-decide-auto-connection-returns-connect-all-when-enabled []
  (check-auto-connection
   {:nav/auto-connect-enabled? true
    :nav/auto-reconnect-enabled? false
    :nav/in-history? false
    :nav/history-port nil
    :nav/saved-port "1340"}
   "connect-all" "1340"))

(defn- test-decide-auto-connection-connect-all-supersedes-reconnect []
  ;; Even when reconnect conditions are met, connect-all wins
  ;; Uses saved-port, not history-port
  (check-auto-connection
   {:nav/auto-connect-enabled? true
    :nav/auto-reconnect-enabled? true
    :nav/in-history? true
    :nav/history-port "1341"
    :nav/saved-port "1340"}
   "connect-all" "1340"))

(defn- test-decide-auto-connection-returns-reconnect-when-conditions-met []
  ;; Uses history-port
  (check-auto-connection
   {:nav/auto-connect-enabled? false
    :nav/auto-reconnect-enabled? true
    :nav/in-history? true
    :nav/history-port "1341"
    :nav/saved-port "1340"}
   "reconnect" "1341"))

(defn- test-decide-auto-connection-returns-none-when-reconnect-disabled []
  (check-auto-connection
   {:nav/auto-connect-enabled? false
    :nav/auto-reconnect-enabled? false
    :nav/in-history? true
    :nav/history-port "1341"
    :nav/saved-port "1340"}
   "none" nil))

(defn- test-decide-auto-connection-returns-none-when-not-in-history []
  (check-auto-connection
   {:nav/auto-connect-enabled? false
    :nav/auto-reconnect-enabled? true
    :nav/in-history? false
    :nav/history-port nil
    :nav/saved-port "1340"}
   "none" nil))

(defn- test-decide-auto-connection-returns-none-when-no-history-port []
  ;; Edge case: in history but port is nil
  (check-auto-connection
   {:nav/auto-connect-enabled? false
    :nav/auto-reconnect-enabled? true
    :nav/in-history? true
    :nav/history-port nil
    :nav/saved-port "1340"}
   "none" nil))

(defn- test-decide-auto-connection-returns-none-when-all-disabled []
  (check-auto-connection
   {:nav/auto-connect-enabled? false
    :nav/auto-reconnect-enabled? false
    :nav/in-history? false
    :nav/history-port nil
    :nav/saved-port "1340"}
   "none" nil))

(describe "decide-auto-connection"
          (fn []
            (test "returns connect-all when auto-connect enabled" test-decide-auto-connection-returns-connect-all-when-enabled)
            (test "connect-all supersedes reconnect even when both conditions met" test-decide-auto-connection-connect-all-supersedes-reconnect)
            (test "returns reconnect when conditions met (enabled, in-history, has port)" test-decide-auto-connection-returns-reconnect-when-conditions-met)
            (test "returns none when auto-reconnect disabled" test-decide-auto-connection-returns-none-when-reconnect-disabled)
            (test "returns none when not in history" test-decide-auto-connection-returns-none-when-not-in-history)
            (test "returns none when no history port" test-decide-auto-connection-returns-none-when-no-history-port)
            (test "returns none when all settings disabled" test-decide-auto-connection-returns-none-when-all-disabled)))

;; ============================================================
;; decide-connection Tests (unified decision model)
;; ============================================================

;; Navigation trigger tests

(defn- test-decide-connection-nav-off-reconnect-on-in-history []
  (check-connection
   {:trigger "navigation"
    :auto-connect-level "off"
    :reconnect-on-nav? true
    :in-history? true
    :history-port "1341"
    :saved-port "1340"}
   "reconnect" "1341"))

(defn- test-decide-connection-nav-off-reconnect-off []
  (check-connection
   {:trigger "navigation"
    :auto-connect-level "off"
    :reconnect-on-nav? false
    :in-history? true
    :history-port "1341"
    :saved-port "1340"}
   "none" nil))

(defn- test-decide-connection-nav-off-reconnect-on-not-in-history []
  (check-connection
   {:trigger "navigation"
    :auto-connect-level "off"
    :reconnect-on-nav? true
    :in-history? false
    :history-port nil
    :saved-port "1340"}
   "none" nil))

(defn- test-decide-connection-nav-all-pages []
  (check-connection
   {:trigger "navigation"
    :auto-connect-level "all-pages"
    :reconnect-on-nav? false
    :in-history? false
    :history-port nil
    :saved-port "1340"}
   "connect-all" "1340"))

(defn- test-decide-connection-nav-all-tabs []
  (check-connection
   {:trigger "navigation"
    :auto-connect-level "all-tabs"
    :reconnect-on-nav? false
    :in-history? false
    :history-port nil
    :saved-port "1340"}
   "connect-all" "1340"))

;; Visibility trigger tests

(defn- test-decide-connection-vis-off []
  (check-connection
   {:trigger "visibility"
    :auto-connect-level "off"
    :reconnect-on-nav? true
    :in-history? true
    :history-port "1341"
    :saved-port "1340"}
   "none" nil))

(defn- test-decide-connection-vis-all-pages []
  (check-connection
   {:trigger "visibility"
    :auto-connect-level "all-pages"
    :reconnect-on-nav? false
    :in-history? true
    :history-port "1341"
    :saved-port "1340"}
   "none" nil))

(defn- test-decide-connection-vis-all-tabs-has-port []
  ;; Prefers history-port
  (check-connection
   {:trigger "visibility"
    :auto-connect-level "all-tabs"
    :reconnect-on-nav? false
    :in-history? true
    :history-port "1341"
    :saved-port "1340"}
   "connect-all" "1341"))

(defn- test-decide-connection-vis-all-tabs-no-port []
  (check-connection
   {:trigger "visibility"
    :auto-connect-level "all-tabs"
    :reconnect-on-nav? false
    :in-history? false
    :history-port nil
    :saved-port nil}
   "none" nil))

(describe "decide-connection - navigation trigger"
          (fn []
            (test "nav + off + reconnect ON + in-history -> reconnects using history-port"
                  test-decide-connection-nav-off-reconnect-on-in-history)
            (test "nav + off + reconnect OFF -> no connection"
                  test-decide-connection-nav-off-reconnect-off)
            (test "nav + off + reconnect ON + not-in-history -> no connection"
                  test-decide-connection-nav-off-reconnect-on-not-in-history)
            (test "nav + all-pages -> connects using saved-port"
                  test-decide-connection-nav-all-pages)
            (test "nav + all-tabs -> connects using saved-port"
                  test-decide-connection-nav-all-tabs)))

(describe "decide-connection - visibility trigger"
          (fn []
            (test "vis + off -> no connection"
                  test-decide-connection-vis-off)
            (test "vis + all-pages -> no connection"
                  test-decide-connection-vis-all-pages)
            (test "vis + all-tabs + has history-port -> connects using history-port"
                  test-decide-connection-vis-all-tabs-has-port)
            (test "vis + all-tabs + no ports -> no connection"
                  test-decide-connection-vis-all-tabs-no-port)))
