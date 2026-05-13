(ns popup.connection-reactivity-test
  "Tests for live reactivity when default ports change"
  (:require ["vitest" :refer [describe test expect]]
            [popup.actions :as popup-actions]))

;; ============================================================
;; Shared Setup
;; ============================================================

(def initial-state
  {:ports/nrepl "3339"
   :ports/ws "3340"
   :ui/status nil
   :ui/connecting? false
   :ui/copy-feedback nil
   :ui/has-connected false
   :ui/sections-collapsed {:repl-connect false
                           :matching-scripts false
                           :other-scripts false
                           :settings true}
   :ui/leaving-scripts #{}
   :ui/leaving-origins #{}
   :ui/leaving-tabs #{}
   :browser/brave? false
   :scripts/list []
   :scripts/current-url nil
   :settings/user-origins []
   :settings/new-origin ""
   :settings/default-origins []
   :settings/default-nrepl-port "3339"
   :settings/default-ws-port "3340"})

(def uf-data {:system/now 1234567890
              :config/deps-string "{:deps {}}"})

;; ============================================================
;; Default ports changed (live reactivity) tests
;; ============================================================

(defn- test-default-change-updates-inherited-domain []
  ;; Domain is using defaults (no override stored)
  ;; When defaults change, effective ports should update
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"} nil])
        db (:uf/db result)]
    ;; Settings update to new defaults
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "5555"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "5556"))
    ;; Effective ports cascade to new defaults (no override)
    (-> (expect (:ports/nrepl db)) (.toBe "5555"))
    (-> (expect (:ports/ws db)) (.toBe "5556"))))

(defn- assert-on-default-changed-with-override [initial-ws domain-ports expected-ws]
  (let [state (assoc initial-state :ports/nrepl "7777" :ports/ws initial-ws)
        db (:uf/db (popup-actions/handle-action state uf-data
                                                [:connection/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"}
                                                 domain-ports]))]
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "5555"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "5556"))
    (-> (expect (:ports/nrepl db)) (.toBe "7777"))
    (-> (expect (:ports/ws db)) (.toBe expected-ws))))

(defn- test-default-change-preserves-explicit-override []
  ;; Domain has explicit override "7777"/"7778"
  ;; When defaults change, effective ports should keep the override
  (assert-on-default-changed-with-override "7778" {:nrepl "7777" :ws "7778"} "7778"))

(defn- test-default-change-partial-override-keeps-override-cascades-default []
  ;; Domain has override only for nrepl "7777", ws inherits
  ;; When defaults change to "5555"/"5556", nrepl keeps "7777" but ws cascades to "5556"
  (assert-on-default-changed-with-override "3340" {:nrepl "7777"} "5556"))

(defn- test-default-change-no-op-when-unchanged []
  ;; Defaults "change" to the same values they already are
  ;; Should return same state reference (unchanged guard) with no effects
  (let [state (assoc initial-state :ports/source {:nrepl :default :ws :default})
        result (popup-actions/handle-action state uf-data
                 [:connection/ax.on-default-ports-changed {:nrepl "3339" :ws "3340"} nil])
        db (:uf/db result)]
    ;; Same reference returned (unchanged guard)
    (-> (expect db) (.toBe state))
    ;; No effects
    (-> (expect (:uf/fxs result)) (.toBeUndefined))))

(defn- test-default-change-sets-source-both-default []
  ;; No domain override => both ports sourced from defaults
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"} nil])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :default))
    (-> (expect (:ws (:ports/source db))) (.toBe :default))))

(defn- test-default-change-sets-source-with-overrides []
  ;; Both ports overridden => both sourced from override
  (let [state (assoc initial-state :ports/nrepl "7777" :ports/ws "7778")
        result (popup-actions/handle-action state uf-data
                 [:connection/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"}
                  {:nrepl "7777" :ws "7778"}])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :override))
    (-> (expect (:ws (:ports/source db))) (.toBe :override))))

(defn- test-default-change-sets-source-partial-override []
  ;; nrepl overridden, ws inherits default
  (let [state (assoc initial-state :ports/nrepl "7777" :ports/ws "3340")
        result (popup-actions/handle-action state uf-data
                 [:connection/ax.on-default-ports-changed {:nrepl "5555" :ws "5556"}
                  {:nrepl "7777"}])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :override))
    (-> (expect (:ws (:ports/source db))) (.toBe :default))))

(defn- test-set-default-nrepl-port-cascades-to-inherited-ports []
  ;; set-default-nrepl-port should cascade to :ports/* when domain uses defaults
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.set-default-nrepl-port "9999"])
        db (:uf/db result)]
    ;; Settings updated
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "9999"))
    ;; Effective ports cascade - nrepl follows new default, ws unchanged
    (-> (expect (:ports/nrepl db)) (.toBe "9999"))
    (-> (expect (:ports/ws db)) (.toBe "3340"))))

(defn- test-set-default-ws-port-cascades-to-inherited-ports []
  ;; set-default-ws-port should cascade to :ports/* when domain uses defaults
  (let [result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.set-default-ws-port "9999"])
        db (:uf/db result)]
    ;; Settings updated
    (-> (expect (:settings/default-ws-port db)) (.toBe "9999"))
    ;; Effective ports cascade - ws follows new default, nrepl unchanged
    (-> (expect (:ports/nrepl db)) (.toBe "3339"))
    (-> (expect (:ports/ws db)) (.toBe "9999"))))

(defn- test-set-default-nrepl-port-preserves-explicit-override []
  ;; When domain has explicit port override, changing default should NOT override it
  (let [state (assoc initial-state
                :ports/nrepl "7777" :ports/ws "7778"
                :ports/source {:nrepl :override :ws :override})
        result (popup-actions/handle-action state uf-data
                 [:connection/ax.set-default-nrepl-port "9999"])
        db (:uf/db result)]
    ;; Settings updated
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "9999"))
    ;; Domain ports differ from new defaults, so they stay as overrides
    (-> (expect (:ports/nrepl db)) (.toBe "7777"))
    (-> (expect (:ports/ws db)) (.toBe "7778"))))

(describe "default ports changed (live reactivity)"
          (fn []
            (test "default change updates inherited domain ports"
                  test-default-change-updates-inherited-domain)
            (test "default change preserves explicit override"
                  test-default-change-preserves-explicit-override)
            (test "partial override keeps override, cascades default for other port"
                  test-default-change-partial-override-keeps-override-cascades-default)
            (test "no-op when defaults unchanged"
                  test-default-change-no-op-when-unchanged)
            (test "sets :ports/source both :default when no overrides"
                  test-default-change-sets-source-both-default)
            (test "sets :ports/source both :override when both overridden"
                  test-default-change-sets-source-with-overrides)
            (test "sets :ports/source mixed when partial override"
                  test-default-change-sets-source-partial-override)
            (test "set-default-nrepl-port cascades to inherited ports"
                  test-set-default-nrepl-port-cascades-to-inherited-ports)
            (test "set-default-ws-port cascades to inherited ports"
                  test-set-default-ws-port-cascades-to-inherited-ports)
            (test "set-default-nrepl-port preserves explicit override"
                  test-set-default-nrepl-port-preserves-explicit-override)))
