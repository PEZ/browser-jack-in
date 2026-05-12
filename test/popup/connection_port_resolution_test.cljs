(ns popup.connection-port-resolution-test
  "Tests for port normalization, initialization, and save-path logic"
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
;; normalize-domain-ports tests
;; ============================================================

(defn- test-normalize-no-domain-entry []
  (let [defaults {:nrepl "1339" :ws "1340"}
        result (popup-actions/normalize-domain-ports defaults nil)]
    (-> (expect (:nrepl (:effective-ports result))) (.toBe "1339"))
    (-> (expect (:ws (:effective-ports result))) (.toBe "1340"))
    (-> (expect (:persist? result)) (.toBe false))
    (-> (expect (:normalized-domain-ports result)) (.toBeNull))
    (-> (expect (:nrepl (:source result))) (.toBe :default))
    (-> (expect (:ws (:source result))) (.toBe :default))))

(defn- test-normalize-domain-differs-from-defaults []
  (let [defaults {:nrepl "1339" :ws "1340"}
        domain-ports {:nrepl "5678" :ws "5679"}
        result (popup-actions/normalize-domain-ports defaults domain-ports)]
    (-> (expect (:nrepl (:effective-ports result))) (.toBe "5678"))
    (-> (expect (:ws (:effective-ports result))) (.toBe "5679"))
    (-> (expect (:persist? result)) (.toBe true))
    (-> (expect (:nrepl (:normalized-domain-ports result))) (.toBe "5678"))
    (-> (expect (:ws (:normalized-domain-ports result))) (.toBe "5679"))
    (-> (expect (:nrepl (:source result))) (.toBe :override))
    (-> (expect (:ws (:source result))) (.toBe :override))))

(defn- test-normalize-domain-equals-defaults []
  (let [defaults {:nrepl "1339" :ws "1340"}
        domain-ports {:nrepl "1339" :ws "1340"}
        result (popup-actions/normalize-domain-ports defaults domain-ports)]
    (-> (expect (:nrepl (:effective-ports result))) (.toBe "1339"))
    (-> (expect (:ws (:effective-ports result))) (.toBe "1340"))
    (-> (expect (:persist? result)) (.toBe false))
    (-> (expect (:normalized-domain-ports result)) (.toBeNull))
    (-> (expect (:nrepl (:source result))) (.toBe :default))
    (-> (expect (:ws (:source result))) (.toBe :default))))

(defn- test-normalize-partial-domain-entry []
  (let [defaults {:nrepl "1339" :ws "1340"}
        domain-ports {:nrepl "5678"}
        result (popup-actions/normalize-domain-ports defaults domain-ports)]
    (-> (expect (:nrepl (:effective-ports result))) (.toBe "5678"))
    (-> (expect (:ws (:effective-ports result))) (.toBe "1340"))
    (-> (expect (:persist? result)) (.toBe true))
    (-> (expect (:nrepl (:normalized-domain-ports result))) (.toBe "5678"))
    (-> (expect (:ws (:normalized-domain-ports result))) (.toBe "1340"))
    (-> (expect (:nrepl (:source result))) (.toBe :override))
    (-> (expect (:ws (:source result))) (.toBe :default))))

(describe "normalize-domain-ports"
          (fn []
            (test "no domain entry uses defaults, persist? false"
                  test-normalize-no-domain-entry)
            (test "domain differs from defaults uses domain values, persist? true"
                  test-normalize-domain-differs-from-defaults)
            (test "domain equals defaults clears redundant, persist? false"
                  test-normalize-domain-equals-defaults)
            (test "partial domain entry fills missing from defaults"
                  test-normalize-partial-domain-entry)))

;; ============================================================
;; init-ports (consolidated startup) tests
;; ============================================================

(defn- test-init-ports-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.init-ports])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.init-ports))))

(defn- test-apply-init-ports-fresh-install []
  ;; No stored defaults, no domain ports => hardcoded fallbacks
  (let [init-data {:stored-defaults nil :domain-ports nil}
        result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.apply-init-ports init-data])
        db (:uf/db result)]
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "3339"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "3340"))
    (-> (expect (:ports/nrepl db)) (.toBe "3339"))
    (-> (expect (:ports/ws db)) (.toBe "3340"))))

(defn- test-apply-init-ports-stored-defaults-no-domain []
  ;; Stored defaults "5555"/"5556", no domain override => uses stored defaults
  (let [init-data {:stored-defaults {:nrepl "5555" :ws "5556"} :domain-ports nil}
        result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.apply-init-ports init-data])
        db (:uf/db result)]
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "5555"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "5556"))
    (-> (expect (:ports/nrepl db)) (.toBe "5555"))
    (-> (expect (:ports/ws db)) (.toBe "5556"))))

(defn- test-apply-init-ports-stored-defaults-with-domain-override []
  ;; Stored defaults "5555"/"5556", domain override "7777"/"7778"
  (let [init-data {:stored-defaults {:nrepl "5555" :ws "5556"}
                   :domain-ports {:nrepl "7777" :ws "7778"}}
        result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.apply-init-ports init-data])
        db (:uf/db result)]
    ;; Settings still reflect the stored defaults
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "5555"))
    (-> (expect (:settings/default-ws-port db)) (.toBe "5556"))
    ;; Effective ports use domain override
    (-> (expect (:ports/nrepl db)) (.toBe "7777"))
    (-> (expect (:ports/ws db)) (.toBe "7778"))))

(defn- test-apply-init-ports-sets-source-default []
  ;; No domain override => source is both :default
  (let [init-data {:stored-defaults {:nrepl "5555" :ws "5556"} :domain-ports nil}
        result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.apply-init-ports init-data])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :default))
    (-> (expect (:ws (:ports/source db))) (.toBe :default))))

(defn- test-apply-init-ports-sets-source-override []
  ;; Domain override => source reflects overrides
  (let [init-data {:stored-defaults {:nrepl "5555" :ws "5556"}
                   :domain-ports {:nrepl "7777" :ws "7778"}}
        result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.apply-init-ports init-data])
        db (:uf/db result)]
    (-> (expect (:nrepl (:ports/source db))) (.toBe :override))
    (-> (expect (:ws (:ports/source db))) (.toBe :override))))

(describe "init-ports (consolidated startup)"
          (fn []
            (test ":popup/ax.init-ports triggers effect"
                  test-init-ports-triggers-effect)
            (test ":popup/ax.apply-init-ports fresh install uses hardcoded fallbacks"
                  test-apply-init-ports-fresh-install)
            (test ":popup/ax.apply-init-ports uses stored defaults when no domain override"
                  test-apply-init-ports-stored-defaults-no-domain)
            (test ":popup/ax.apply-init-ports resolves domain override over stored defaults"
                  test-apply-init-ports-stored-defaults-with-domain-override)
            (test ":popup/ax.apply-init-ports sets :ports/source :default when no override"
                  test-apply-init-ports-sets-source-default)
            (test ":popup/ax.apply-init-ports sets :ports/source :override when overridden"
                  test-apply-init-ports-sets-source-override)))

;; ============================================================
;; Save-path normalization tests
;; ============================================================

(defn- test-set-nrepl-port-equal-to-default-clears-domain-ports []
  ;; Both ports match defaults -> should clear domain ports, not save
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-nrepl-port "3339"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "3339"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.clear-domain-ports))))

(defn- test-set-ws-port-equal-to-default-clears-domain-ports []
  ;; Both ports match defaults -> should clear domain ports, not save
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-ws-port "3340"])]
    (-> (expect (:ports/ws (:uf/db result)))
        (.toBe "3340"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.clear-domain-ports))))

(defn- test-set-nrepl-port-different-from-default-saves []
  ;; nrepl differs from default -> should persist
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-nrepl-port "5678"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "5678"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-set-ws-port-different-from-default-saves []
  ;; ws differs from default -> should persist
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-ws-port "5679"])]
    (-> (expect (:ports/ws (:uf/db result)))
        (.toBe "5679"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-return-to-default-nrepl-clears-when-both-match []
  ;; State has non-default nrepl, ws is already default
  ;; Setting nrepl back to default -> both match -> clear
  (let [state (assoc initial-state :ports/nrepl "5678")
        result (popup-actions/handle-action state uf-data [:connection/ax.set-nrepl-port "3339"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "3339"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.clear-domain-ports))))

(defn- test-return-to-default-nrepl-saves-when-ws-differs []
  ;; State has non-default nrepl and non-default ws
  ;; Setting nrepl back to default -> ws still differs -> save
  (let [state (assoc initial-state :ports/nrepl "5678" :ports/ws "5679")
        result (popup-actions/handle-action state uf-data [:connection/ax.set-nrepl-port "3339"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "3339"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-set-nrepl-port-updates-source-to-override []
  ;; Setting nrepl to a non-default value should mark nrepl source as :override
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-nrepl-port "7777"])
        source (:ports/source (:uf/db result))]
    (-> (expect (:nrepl source)) (.toBe :override))
    (-> (expect (:ws source)) (.toBe :default))))

(defn- test-set-ws-port-updates-source-to-override []
  ;; Setting ws to a non-default value should mark ws source as :override
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-ws-port "7778"])
        source (:ports/source (:uf/db result))]
    (-> (expect (:nrepl source)) (.toBe :default))
    (-> (expect (:ws source)) (.toBe :override))))

(defn- test-set-nrepl-port-to-default-marks-source-default []
  ;; Setting nrepl back to default value should mark nrepl source as :default
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-nrepl-port "3339"])
        source (:ports/source (:uf/db result))]
    (-> (expect (:nrepl source)) (.toBe :default))
    (-> (expect (:ws source)) (.toBe :default))))

(defn- test-override-sticky-through-default-change []
  ;; Full scenario: set override, then change defaults - override should stick
  (let [;; Step 1: Set explicit override on nrepl port
        step1 (popup-actions/handle-action initial-state uf-data [:connection/ax.set-nrepl-port "7777"])
        ;; Step 2: Change default nrepl port
        step2 (popup-actions/handle-action (:uf/db step1) uf-data [:connection/ax.set-default-nrepl-port "8888"])
        db (:uf/db step2)]
    ;; Override should stick at 7777, not cascade to 8888
    (-> (expect (:ports/nrepl db)) (.toBe "7777"))
    ;; Default should be updated
    (-> (expect (:settings/default-nrepl-port db)) (.toBe "8888"))))

(describe "save-path normalization"
          (fn []
            (test "setting nrepl port equal to default clears domain ports"
                  test-set-nrepl-port-equal-to-default-clears-domain-ports)
            (test "setting ws port equal to default clears domain ports"
                  test-set-ws-port-equal-to-default-clears-domain-ports)
            (test "setting nrepl port different from default saves"
                  test-set-nrepl-port-different-from-default-saves)
            (test "setting ws port different from default saves"
                  test-set-ws-port-different-from-default-saves)
            (test "returning nrepl to default clears when both ports match defaults"
                  test-return-to-default-nrepl-clears-when-both-match)
            (test "returning nrepl to default saves when ws still differs"
                  test-return-to-default-nrepl-saves-when-ws-differs)
            (test "set-nrepl-port updates source to override"
                  test-set-nrepl-port-updates-source-to-override)
            (test "set-ws-port updates source to override"
                  test-set-ws-port-updates-source-to-override)
            (test "set-nrepl-port to default marks source as default"
                  test-set-nrepl-port-to-default-marks-source-default)
            (test "override remains sticky through default change"
                  test-override-sticky-through-default-change)))
