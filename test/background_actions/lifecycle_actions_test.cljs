(ns background-actions.lifecycle-actions-test
  "Tests for background lifecycle action handlers - icon, history, ws, init, alarm, disconnect"
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
;; Icon State Tests
;; ============================================================

(defn- test-icon-set-state-sets-icon-state-and-triggers-toolbar-update []
  (let [state {:icon/states {1 :disconnected}}
        result (bg-actions/handle-action state uf-data
                 [:icon/ax.set-state 1 :connected])]
    (-> (expect (get-in result [:uf/db :icon/states 1]))
        (.toBe :connected))
    (-> (expect (some #(= [:icon/fx.update-toolbar! 1 :connected] %) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":icon/ax.set-state"
          (fn []
            (test "sets icon state and triggers toolbar update" test-icon-set-state-sets-icon-state-and-triggers-toolbar-update)))

(defn- test-icon-clear-removes-icon-state-and-updates-toolbar []
  (let [state {:icon/states {1 :connected 2 :disconnected}}
        result (bg-actions/handle-action state uf-data
                 [:icon/ax.clear 1])]
    (-> (expect (get-in result [:uf/db :icon/states 1]))
        (.toBeUndefined))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 1))
    (-> (expect (first (:uf/fxs result)))
        (.toEqual [:icon/fx.update-toolbar! 1 :disconnected]))))

(describe ":icon/ax.clear"
          (fn []
            (test "removes icon state and updates toolbar" test-icon-clear-removes-icon-state-and-updates-toolbar)))

(defn- test-icon-prune-keeps-only-valid-tab-ids []
  (let [state {:icon/states {1 :connected 2 :disconnected}}
        result (bg-actions/handle-action state uf-data
                 [:icon/ax.prune #{2}])]
    (-> (expect (get-in result [:uf/db :icon/states 1]))
        (.toBeUndefined))
    (-> (expect (get-in result [:uf/db :icon/states 2]))
        (.toBe :disconnected))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 0))))

(describe ":icon/ax.prune"
          (fn []
            (test "keeps only valid tab ids" test-icon-prune-keeps-only-valid-tab-ids)))

;; ============================================================
;; Connection History Tests
;; ============================================================

(defn- test-history-track-adds-tab-and-port-to-history []
  (let [state {:connected-tabs/history {1 {:port 12345}}}
        result (bg-actions/handle-action state uf-data
                 [:history/ax.track 2 23456])]
    (-> (expect (get-in result [:uf/db :connected-tabs/history 2 :port]))
        (.toBe 23456))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 0))))

(describe ":history/ax.track"
          (fn []
            (test "adds tab and port to history" test-history-track-adds-tab-and-port-to-history)))

(defn- test-history-forget-removes-tab-from-history []
  (let [state {:connected-tabs/history {1 {:port 12345} 2 {:port 23456}}}
        result (bg-actions/handle-action state uf-data
                 [:history/ax.forget 1])]
    (-> (expect (get-in result [:uf/db :connected-tabs/history 1]))
        (.toBeUndefined))
    (-> (expect (get-in result [:uf/db :connected-tabs/history 2 :port]))
        (.toBe 23456))
    (-> (expect (count (:uf/fxs result)))
        (.toBe 0))))

(describe ":history/ax.forget"
          (fn []
            (test "removes tab from history" test-history-forget-removes-tab-from-history)))

;; ============================================================
;; WebSocket Connection Tests
;; ============================================================

(defn- test-ws-register-registers-connection-info-and-starts-alarm-when-first []
  (let [conn {:ws/socket :dummy-ws
              :ws/port 5555
              :ws/tab-title "Example"
              :ws/tab-favicon "favicon.png"
              :ws/tab-url "https://example.com"}
        result (bg-actions/handle-action {:ws/connections {}} uf-data
                 [:ws/ax.register 9 conn])]
    (-> (expect (get-in result [:uf/db :ws/connections 9 :ws/port]))
        (.toBe 5555))
    (-> (expect (some #(= [:alarm/fx.start] %) (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":ws/ax.register"
          (fn []
            (test "registers connection info and starts alarm when first connection" test-ws-register-registers-connection-info-and-starts-alarm-when-first)))

(defn- test-ws-unregister-removes-connection-and-broadcasts-change []
  (let [conn {:ws/socket :dummy-ws
              :ws/port 5555}
        result (bg-actions/handle-action {:ws/connections {9 conn}} uf-data
                 [:ws/ax.unregister 9])]
    (-> (expect (get-in result [:uf/db :ws/connections 9]))
        (.toBeUndefined))
    (-> (expect (some #(= :ws/fx.broadcast-connections-changed! (first %))
                      (:uf/fxs result)))
        (.toBeTruthy))))

(describe ":ws/ax.unregister"
          (fn []
            (test "removes connection and broadcasts change" test-ws-unregister-removes-connection-and-broadcasts-change)))

;; ============================================================
;; Initialization Tests
;; ============================================================

(defn- test-init-ensure-initialized-returns-await-effect-when-promise-exists []
  (let [existing-promise (js/Promise.resolve true)
        state {:init/promise existing-promise}
        result (bg-actions/handle-action state uf-data
                 [:init/ax.ensure-initialized])]
    (-> (expect (count (:uf/fxs result)))
        (.toBe 1))
    (-> (expect (first (first (:uf/fxs result))))
        (.toEqual :uf/await))))

(defn- test-init-ensure-initialized-creates-promise-and-initialization-effect-when-missing []
  (let [result (bg-actions/handle-action {} uf-data
                 [:init/ax.ensure-initialized])
        promise (get-in result [:uf/db :init/promise])
        fxs (:uf/fxs result)
        fx (first fxs)]
    (-> (expect promise)
        (.toBeTruthy))
    (-> (expect (count fxs))
        (.toBe 1))
    (-> (expect (first fx))
        (.toBe :uf/await))
    (-> (expect (second fx))
        (.toBe :init/fx.initialize))
    (-> (expect (fn? (nth fx 2)))
        (.toBe true))
    (-> (expect (fn? (nth fx 3)))
        (.toBe true))))

(describe ":init/ax.ensure-initialized"
          (fn []
            (test "returns await effect when promise already exists" test-init-ensure-initialized-returns-await-effect-when-promise-exists)
            (test "creates promise and initialization effect when missing" test-init-ensure-initialized-creates-promise-and-initialization-effect-when-missing)))

(defn- test-init-clear-promise-clears-init-promise []
  (let [promise (js/Promise.resolve true)
        result (bg-actions/handle-action {:init/promise promise} uf-data
                 [:init/ax.clear-promise])]
    (-> (expect (get-in result [:uf/db :init/promise]))
        (.toBe nil))))

(describe ":init/ax.clear-promise"
          (fn []
            (test "clears init promise" test-init-clear-promise-clears-init-promise)))

;; ============================================================
;; Alarm Tick Tests
;; ============================================================

(defn- test-alarm-tick-returns-log-effect-when-connections-exist []
  (let [state {:ws/connections {1 {:ws/port 1234} 2 {:ws/port 5678}}}
        result (bg-actions/handle-action state uf-data [:alarm/ax.tick])]
    (-> (expect (:uf/fxs result))
        (.toEqual [[:alarm/fx.log-tick 2]]))))

(defn- test-alarm-tick-returns-nil-when-no-connections []
  (let [result (bg-actions/handle-action {} uf-data [:alarm/ax.tick])]
    (-> (expect result)
        (.toBeFalsy))))

(describe ":alarm/ax.tick"
          (fn []
            (test "returns log effect when connections exist"
                  test-alarm-tick-returns-log-effect-when-connections-exist)
            (test "returns nil when no connections"
                  test-alarm-tick-returns-nil-when-no-connections)))

;; ============================================================
;; Explicit Disconnect Tests
;; ============================================================

(defn- test-explicit-disconnect-produces-ws-close-effect []
  (let [state {:ws/connections {42 {:ws/port 1340}}}
        result (bg-actions/handle-action state uf-data
                 [:ws/ax.explicit-disconnect 42])
        fxs (:uf/fxs result)]
    (-> (expect (some #(= :ws/fx.handle-close (first %)) fxs))
        (.toBeTruthy))))

(defn- test-explicit-disconnect-does-not-modify-state []
  (let [state {:ws/connections {42 {:ws/port 1340}}}
        result (bg-actions/handle-action state uf-data
                 [:ws/ax.explicit-disconnect 42])]
    (-> (expect (:uf/db result))
        (.toBeUndefined))))

(defn- test-explicit-disconnect-forgets-history []
  (let [state {:ws/connections {42 {:ws/port 1340}}
               :connected-tabs/history {42 {:port "1340"}}}
        result (bg-actions/handle-action state uf-data
                 [:ws/ax.explicit-disconnect 42])
        dxs (:uf/dxs result)]
    (-> (expect (some #(= [:history/ax.forget 42] %) dxs))
        (.toBeTruthy))))

(describe ":ws/ax.explicit-disconnect"
          (fn []
            (test "produces WS close effect"
                  test-explicit-disconnect-produces-ws-close-effect)
            (test "does not modify state"
                  test-explicit-disconnect-does-not-modify-state)
            (test "forgets history to prevent reconnect-on-nav"
                  test-explicit-disconnect-forgets-history)))
