(ns popup.connection-actions-test
  "Tests for popup connection action handlers - port setting, copy, connect, connect mode, load, default ports"
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
;; Port actions
;; ============================================================

(defn- test-set-nrepl-port-updates-and-saves []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-nrepl-port "12345"])]
    (-> (expect (:ports/nrepl (:uf/db result)))
        (.toBe "12345"))
    ;; Should trigger save effect
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-set-ws-port-updates-and-saves []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-ws-port "12346"])]
    (-> (expect (:ports/ws (:uf/db result)))
        (.toBe "12346"))
    ;; Should trigger save effect
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-ports))))

(defn- test-set-nrepl-port-preserves-other-port []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-nrepl-port "9999"])
        [_fx-name ports] (first (:uf/fxs result))]
    (-> (expect (:ports/nrepl ports))
        (.toBe "9999"))
    (-> (expect (:ports/ws ports))
        (.toBe "3340"))))

;; ============================================================
;; Copy command
;; ============================================================

(defn- test-copy-command-generates-with-ports []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.copy-command])
        [fx-name cmd] (first (:uf/fxs result))]
    (-> (expect fx-name)
        (.toBe :popup/fx.copy-command))
    ;; Command should contain the ports
    (-> (expect (.includes cmd "3339"))
        (.toBe true))
    (-> (expect (.includes cmd "3340"))
        (.toBe true))))

(defn- test-copy-command-uses-deps-string []
  (let [custom-uf-data {:config/deps-string "{:deps {foo/bar {:mvn/version \"1.0\"}}}"}
        result (popup-actions/handle-action initial-state custom-uf-data [:connection/ax.copy-command])
        [_fx-name cmd] (first (:uf/fxs result))]
    (-> (expect (.includes cmd "foo/bar"))
        (.toBe true))))

;; ============================================================
;; Connect
;; ============================================================

(defn- test-connect-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.connect])]
    ;; Action only triggers effect - status is set by the effect itself
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.connect))))

(defn- test-connect-passes-parsed-port []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.connect])
        [_fx-name port] (first (:uf/fxs result))]
    (-> (expect port)
        (.toBe 3340))))

(defn- test-connect-returns-nil-for-invalid-port []
  (let [state (assoc initial-state :ports/ws "invalid")
        result (popup-actions/handle-action state uf-data [:connection/ax.connect])]
    (-> (expect result)
        (.toBeUndefined))))

(defn- test-connect-returns-nil-for-out-of-range-port []
  (let [state (assoc initial-state :ports/ws "70000")
        result (popup-actions/handle-action state uf-data [:connection/ax.connect])]
    (-> (expect result)
        (.toBeUndefined))))

(defn- test-connect-sets-connecting-state []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.connect])]
    (-> (expect (:ui/connecting? (:uf/db result)))
        (.toBe true))))

(defn- test-connect-returns-nil-when-already-connecting []
  (let [state (assoc initial-state :ui/connecting? true)
        result (popup-actions/handle-action state uf-data [:connection/ax.connect])]
    (-> (expect result)
        (.toBeFalsy))))

(defn- test-cancel-connect-clears-connecting-state []
  (let [state (assoc initial-state :ui/connecting? true)
        result (popup-actions/handle-action state uf-data [:connection/ax.cancel-connect])]
    (-> (expect (:ui/connecting? (:uf/db result)))
        (.toBe false))))

(defn- test-connect-finished-clears-connecting-state []
  (let [state (assoc initial-state :ui/connecting? true)
        result (popup-actions/handle-action state uf-data [:connection/ax.connect-finished])]
    (-> (expect (:ui/connecting? (:uf/db result)))
        (.toBe false))))

;; ============================================================
;; Connect mode
;; ============================================================

(defn- test-set-connect-mode-updates-state []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-connect-mode "relay"])]
    (-> (expect (:ui/connect-mode (:uf/db result)))
        (.toBe "relay"))))

(defn- test-set-connect-mode-to-direct []
  (let [state (assoc initial-state :ui/connect-mode "relay")
        result (popup-actions/handle-action state uf-data [:connection/ax.set-connect-mode "direct"])]
    (-> (expect (:ui/connect-mode (:uf/db result)))
        (.toBe "direct"))))

;; ============================================================
;; Load actions
;; ============================================================

(defn- test-check-status-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.check-status])
        [fx-name ws-port] (first (:uf/fxs result))]
    (-> (expect fx-name)
        (.toBe :popup/fx.check-status))
    (-> (expect ws-port)
        (.toBe "3340"))))

(defn- test-load-saved-ports-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.load-saved-ports])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-saved-ports))))

(defn- test-load-scripts-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:script/ax.load-scripts])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-scripts))))

(defn- test-load-current-url-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:script/ax.load-current-url])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-current-url))))

;; ============================================================
;; Default port settings
;; ============================================================

(defn- test-set-default-nrepl-port-updates-and-saves []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-default-nrepl-port "12345"])]
    (-> (expect (:settings/default-nrepl-port (:uf/db result)))
        (.toBe "12345"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-default-ports-setting))))

(defn- test-set-default-ws-port-updates-and-saves []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-default-ws-port "12346"])]
    (-> (expect (:settings/default-ws-port (:uf/db result)))
        (.toBe "12346"))
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.save-default-ports-setting))))

(defn- test-set-default-nrepl-port-preserves-other-default []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.set-default-nrepl-port "9999"])
        [_fx-name ports] (first (:uf/fxs result))]
    (-> (expect (:settings/default-nrepl-port ports))
        (.toBe "9999"))
    (-> (expect (:settings/default-ws-port ports))
        (.toBe "3340"))))

(defn- test-load-default-ports-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:connection/ax.load-default-ports-setting])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-default-ports-setting))))

;; ============================================================
;; Describe block
;; ============================================================

(describe "popup connection actions"
          (fn []
            ;; Port actions
            (test ":popup/ax.set-nrepl-port updates and saves" test-set-nrepl-port-updates-and-saves)
            (test ":popup/ax.set-ws-port updates and saves" test-set-ws-port-updates-and-saves)
            (test ":popup/ax.set-nrepl-port preserves other port" test-set-nrepl-port-preserves-other-port)

            ;; Copy command
            (test ":popup/ax.copy-command generates with ports" test-copy-command-generates-with-ports)
            (test ":popup/ax.copy-command uses deps string" test-copy-command-uses-deps-string)

            ;; Connect
            (test ":popup/ax.connect triggers effect" test-connect-triggers-effect)
            (test ":popup/ax.connect passes parsed port" test-connect-passes-parsed-port)
            (test ":popup/ax.connect returns nil for invalid port" test-connect-returns-nil-for-invalid-port)
            (test ":popup/ax.connect returns nil for out of range port" test-connect-returns-nil-for-out-of-range-port)
            (test ":popup/ax.connect sets connecting state" test-connect-sets-connecting-state)
            (test ":popup/ax.connect returns nil when already connecting" test-connect-returns-nil-when-already-connecting)
            (test ":popup/ax.cancel-connect clears connecting state" test-cancel-connect-clears-connecting-state)
            (test ":popup/ax.connect-finished clears connecting state" test-connect-finished-clears-connecting-state)

            ;; Connect mode
            (test ":popup/ax.set-connect-mode updates state to relay" test-set-connect-mode-updates-state)
            (test ":popup/ax.set-connect-mode updates state to direct" test-set-connect-mode-to-direct)

            ;; Load actions
            (test ":popup/ax.check-status triggers effect" test-check-status-triggers-effect)
            (test ":popup/ax.load-saved-ports triggers effect" test-load-saved-ports-triggers-effect)
            (test ":popup/ax.load-scripts triggers effect" test-load-scripts-triggers-effect)
            (test ":popup/ax.load-current-url triggers effect" test-load-current-url-triggers-effect)

            ;; Default port settings
            (test ":popup/ax.set-default-nrepl-port updates and saves" test-set-default-nrepl-port-updates-and-saves)
            (test ":popup/ax.set-default-ws-port updates and saves" test-set-default-ws-port-updates-and-saves)
            (test ":popup/ax.set-default-nrepl-port preserves other default" test-set-default-nrepl-port-preserves-other-default)
            (test ":popup/ax.load-default-ports-setting triggers effect" test-load-default-ports-triggers-effect)))
