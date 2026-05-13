(ns background-actions.nav-visibility-actions-test
  "Tests for navigation decision and visibility action handlers"
  (:require ["vitest" :refer [describe test expect]]
            [background-actions :as bg-actions]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def initial-state
  {:storage/scripts []
   :storage/granted-origins []
   :storage/ext-dep-cache {}})

(def uf-data {:system/now 1737100000000})

;; ============================================================
;; Navigation Decision Action Tests
;; ============================================================

(defn- assert-nav-connect-effect [context expected-tab-id expected-port]
  (let [result (bg-actions/handle-action {} uf-data [:nav/ax.decide-connection context])
        fxs (:uf/fxs result)]
    (-> (expect (some #(and (= :uf/await (first %))
                            (= :nav/fx.connect (second %))
                            (= expected-tab-id (nth % 2))
                            (= expected-port (nth % 3))) fxs))
        (.toBeTruthy))))

(defn- test-nav-decide-connection-returns-connect-effect-for-connect-all []
  (assert-nav-connect-effect
   {:nav/tab-id 123
    :nav/url "https://example.com"
    :nav/auto-connect-enabled? true
    :nav/auto-reconnect-enabled? false
    :nav/auto-connect-level "all-pages"
    :nav/in-history? false
    :nav/history-port nil
    :nav/saved-port "1340"}
   123 "1340"))

(defn- test-nav-decide-connection-returns-connect-effect-for-reconnect []
  (assert-nav-connect-effect
   {:nav/tab-id 456
    :nav/url "https://github.com"
    :nav/auto-connect-enabled? false
    :nav/auto-reconnect-enabled? true
    :nav/auto-connect-level "off"
    :nav/in-history? true
    :nav/history-port "1341"
    :nav/saved-port "1340"}
   456 "1341"))

(defn- test-nav-decide-connection-returns-no-connect-effect-when-none []
  (let [context {:nav/tab-id 789
                 :nav/url "https://example.com"
                 :nav/auto-connect-enabled? false
                 :nav/auto-reconnect-enabled? false
                 :nav/auto-connect-level "off"
                 :nav/in-history? false
                 :nav/history-port nil
                 :nav/saved-port "1340"}
        result (bg-actions/handle-action {} uf-data
                 [:nav/ax.decide-connection context])
        fxs (:uf/fxs result)]
    (-> (expect (some #(= :nav/fx.connect (if (= :uf/await (first %)) (second %) (first %))) fxs))
        (.toBeFalsy))))

(defn- test-nav-decide-connection-always-returns-process-navigation-effect []
  (let [context {:nav/tab-id 123
                 :nav/url "https://example.com"
                 :nav/auto-connect-enabled? false
                 :nav/auto-reconnect-enabled? false
                 :nav/auto-connect-level "off"
                 :nav/in-history? false
                 :nav/history-port nil
                 :nav/saved-port "1340"}
        result (bg-actions/handle-action {} uf-data
                 [:nav/ax.decide-connection context])
        fxs (:uf/fxs result)]
    (-> (expect (some #(and (= :nav/fx.process-navigation (if (= :uf/await (first %)) (second %) (first %)))
                            (= 123 (nth % (if (= :uf/await (first %)) 2 1)))
                            (= "https://example.com" (nth % (if (= :uf/await (first %)) 3 2)))) fxs))
        (.toBeTruthy))))

(describe ":nav/ax.decide-connection"
          (fn []
            (test "returns connect effect with saved-port for connect-all decision" test-nav-decide-connection-returns-connect-effect-for-connect-all)
            (test "returns connect effect with history-port for reconnect decision" test-nav-decide-connection-returns-connect-effect-for-reconnect)
            (test "returns no connect effect when decision is none" test-nav-decide-connection-returns-no-connect-effect-when-none)
            (test "always returns process-navigation effect" test-nav-decide-connection-always-returns-process-navigation-effect)))

;; ============================================================
;; Permission Granted Tests
;; ============================================================

(defn- assert-permission-granted-effect [state tab-id expected-icon-state]
  (let [result (bg-actions/handle-action state uf-data [:msg/ax.handle-permission-granted tab-id])
        [fx-name actual-tab-id icon-state] (first (:uf/fxs result))]
    (-> (expect fx-name) (.toBe :msg/fx.handle-permission-granted))
    (-> (expect actual-tab-id) (.toBe tab-id))
    (-> (expect icon-state) (.toBe expected-icon-state))))

(defn- test-permission-granted-triggers-effect-with-icon-state []
  (assert-permission-granted-effect
   (assoc initial-state :icon/states {42 :connected})
   42 :connected))

(defn- test-permission-granted-defaults-icon-state-to-disconnected []
  (assert-permission-granted-effect initial-state 99 :disconnected))

(describe ":msg/ax.handle-permission-granted"
          (fn []
            (test "triggers effect with tab icon state" test-permission-granted-triggers-effect-with-icon-state)
            (test "defaults icon state to disconnected" test-permission-granted-defaults-icon-state-to-disconnected)))

;; ============================================================
;; Visibility: Handle Tab Visible Tests
;; ============================================================

(defn- test-handle-tab-visible-returns-nil-when-ws-exists []
  (let [state (assoc initial-state :ws/connections {42 {:some "ws"}})
        result (bg-actions/handle-action state uf-data
                 [:visibility/ax.handle-tab-visible 42])]
    (-> (expect result)
        (.toBeFalsy))))

(defn- assert-gather-reconnect-context [result tab-id]
  (let [fxs (:uf/fxs result)]
    (-> (expect (some #(and (= :uf/await (first %))
                            (= :visibility/fx.gather-reconnect-context (second %))
                            (= tab-id (nth % 2))) fxs))
        (.toBeTruthy))))

(defn- test-handle-tab-visible-returns-gather-effect-when-no-ws []
  (let [state (assoc initial-state :ws/connections {99 {:other "ws"}})
        result (bg-actions/handle-action state uf-data
                                         [:visibility/ax.handle-tab-visible 42])
        dxs (:uf/dxs result)]
    (assert-gather-reconnect-context result 42)
    (-> (expect (some #(= :visibility/ax.decide-reconnect (first %)) dxs))
        (.toBeTruthy))))

(defn- test-handle-tab-visible-passes-history-to-gather-effect []
  (let [history {42 {:port "1340"} 99 {:port "1341"}}
        state (assoc initial-state
                     :ws/connections {}
                     :connected-tabs/history history)
        result (bg-actions/handle-action state uf-data
                 [:visibility/ax.handle-tab-visible 42])
        gather-fx (first (filter #(= :visibility/fx.gather-reconnect-context (second %))
                                 (:uf/fxs result)))]
    (-> (expect (nth gather-fx 3))
        (.toEqual (clj->js history)))))

(defn- test-handle-tab-visible-with-empty-state []
  (let [result (bg-actions/handle-action {} uf-data
                                         [:visibility/ax.handle-tab-visible 42])]
    (assert-gather-reconnect-context result 42)))

(describe ":visibility/ax.handle-tab-visible"
          (fn []
            (test "returns nil when ws exists" test-handle-tab-visible-returns-nil-when-ws-exists)
            (test "returns gather effect when no ws" test-handle-tab-visible-returns-gather-effect-when-no-ws)
            (test "passes history to gather effect" test-handle-tab-visible-passes-history-to-gather-effect)
            (test "works with empty state" test-handle-tab-visible-with-empty-state)))

;; ============================================================
;; Visibility: Decide Reconnect Tests
;; ============================================================

(defn- assert-decide-reconnect-no-connect [context]
  (let [result (bg-actions/handle-action {} uf-data [:visibility/ax.decide-reconnect context])]
    (-> (expect result) (.toBeFalsy))))

(defn- assert-decide-reconnect-connect [state context expected-port]
  (let [result (bg-actions/handle-action state uf-data [:visibility/ax.decide-reconnect context])
        fxs (:uf/fxs result)]
    (-> (expect (some #(and (= :uf/await (first %))
                            (= :nav/fx.connect (second %))
                            (= (:visibility/tab-id context) (nth % 2))
                            (= expected-port (nth % 3))) fxs))
        (.toBeTruthy))))

(defn- assert-decide-reconnect-icon-state [state context expected-icon-state]
  (let [[_ _ _ _ icon-state] (first (:uf/fxs (bg-actions/handle-action state uf-data
                                                [:visibility/ax.decide-reconnect context])))]
    (-> (expect icon-state) (.toBe expected-icon-state))))

(defn- test-decide-reconnect-connects-at-all-tabs-level []
  (assert-decide-reconnect-connect
   {}
   {:visibility/tab-id 42
    :visibility/auto-connect-level "all-tabs"
    :visibility/history-port "1340"
    :visibility/saved-port "3340"}
   "1340"))

(defn- test-decide-reconnect-no-connect-at-off-level []
  (assert-decide-reconnect-no-connect
   {:visibility/tab-id 42
    :visibility/auto-connect-level "off"
    :visibility/history-port "1340"
    :visibility/saved-port "3340"}))

(defn- test-decide-reconnect-no-connect-at-all-pages-level []
  (assert-decide-reconnect-no-connect
   {:visibility/tab-id 42
    :visibility/auto-connect-level "all-pages"
    :visibility/history-port "1340"
    :visibility/saved-port "3340"}))

(defn- test-decide-reconnect-all-tabs-uses-saved-port-when-no-history []
  (assert-decide-reconnect-connect
   {}
   {:visibility/tab-id 42
    :visibility/auto-connect-level "all-tabs"
    :visibility/history-port nil
    :visibility/saved-port "3340"}
   "3340"))

(defn- test-decide-reconnect-all-tabs-no-connect-when-no-port []
  (assert-decide-reconnect-no-connect
   {:visibility/tab-id 42
    :visibility/auto-connect-level "all-tabs"
    :visibility/history-port nil
    :visibility/saved-port nil}))

(defn- test-decide-reconnect-uses-icon-state-from-state []
  (assert-decide-reconnect-icon-state
   {:icon/states {42 :connected}}
   {:visibility/tab-id 42
    :visibility/auto-connect-level "all-tabs"
    :visibility/history-port "1340"
    :visibility/saved-port "3340"}
   :connected))

(defn- test-decide-reconnect-defaults-icon-state-to-disconnected []
  (assert-decide-reconnect-icon-state
   {}
   {:visibility/tab-id 42
    :visibility/auto-connect-level "all-tabs"
    :visibility/history-port "1340"
    :visibility/saved-port "3340"}
   :disconnected))

(describe ":visibility/ax.decide-reconnect"
          (fn []
            (test "connects at all-tabs level" test-decide-reconnect-connects-at-all-tabs-level)
            (test "no connect at off level" test-decide-reconnect-no-connect-at-off-level)
            (test "no connect at all-pages level" test-decide-reconnect-no-connect-at-all-pages-level)
            (test "uses saved-port when no history port" test-decide-reconnect-all-tabs-uses-saved-port-when-no-history)
            (test "no connect when no port available" test-decide-reconnect-all-tabs-no-connect-when-no-port)
            (test "uses icon state from state" test-decide-reconnect-uses-icon-state-from-state)
            (test "defaults icon state to disconnected" test-decide-reconnect-defaults-icon-state-to-disconnected)))
