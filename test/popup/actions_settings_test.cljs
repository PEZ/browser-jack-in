(ns popup.actions-settings-test
  "Tests for popup settings-related action handlers"
  (:require ["vitest" :refer [describe test expect]]
            [popup.actions :as popup-actions]))

;; ============================================================
;; Shared Setup
;; ============================================================

(def initial-state
  {:ports/nrepl "3339"
   :ports/ws "3340"
   :ui/status nil
   :ui/copy-feedback nil
   :ui/has-connected false
   :ui/sections-collapsed {:repl-connect false
                           :matching-scripts false
                           :other-scripts false
                           :settings true}})

(def uf-data {:system/now 1234567890
              :config/deps-string "{:deps {}}"})

;; ============================================================
;; Auto-Reconnect Tests
;; ============================================================

(defn- ^:async test-load-auto-reconnect-setting-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:settings/ax.load-auto-reconnect-setting])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-auto-reconnect-setting))))

(defn- check-settings-action! [state action-vec [db-key expected-value] fx-key]
  (let [result (popup-actions/handle-action state uf-data action-vec)]
    (-> (expect (get (:uf/db result) db-key)) (.toBe expected-value))
    (let [[fx-name value] (first (:uf/fxs result))]
      (-> (expect fx-name) (.toBe fx-key))
      (-> (expect value) (.toBe expected-value)))))

(defn- ^:async test-toggle-auto-reconnect-repl-toggles-true-to-false []
  (check-settings-action! (assoc initial-state :settings/auto-reconnect-repl true)
                          [:settings/ax.toggle-auto-reconnect-repl]
                          [:settings/auto-reconnect-repl false] :popup/fx.save-auto-reconnect-setting))

(defn- ^:async test-toggle-auto-reconnect-repl-toggles-false-to-true []
  (check-settings-action! (assoc initial-state :settings/auto-reconnect-repl false)
                          [:settings/ax.toggle-auto-reconnect-repl]
                          [:settings/auto-reconnect-repl true] :popup/fx.save-auto-reconnect-setting))

;; ============================================================
;; Host Permission Tests
;; ============================================================

(defn- ^:async test-check-host-permission-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:permission/ax.check-host-permission])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.check-host-permission))))

(defn- ^:async test-request-host-permission-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:permission/ax.request-host-permission])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.request-host-permission))))

(defn- ^:async test-request-host-permission-passes-tab-id []
  (let [state (assoc initial-state :scripts/current-tab-id 42)
        result (popup-actions/handle-action state uf-data [:permission/ax.request-host-permission])
        [fx-name tab-id] (first (:uf/fxs result))]
    (-> (expect fx-name)
        (.toBe :popup/fx.request-host-permission))
    (-> (expect tab-id)
        (.toBe 42))))

(defn- ^:async test-request-host-permission-passes-nil-tab-id-when-missing []
  (let [result (popup-actions/handle-action initial-state uf-data [:permission/ax.request-host-permission])
        [fx-name tab-id] (first (:uf/fxs result))]
    (-> (expect fx-name)
        (.toBe :popup/fx.request-host-permission))
    (-> (expect tab-id)
        (.toBeNull))))

;; ============================================================
;; Auto-Connect Level Tests
;; ============================================================

(defn- ^:async test-load-auto-connect-level-triggers-effect []
  (let [result (popup-actions/handle-action initial-state uf-data [:settings/ax.load-auto-connect-level])]
    (-> (expect (first (first (:uf/fxs result))))
        (.toBe :popup/fx.load-auto-connect-level))))

(defn- ^:async test-set-auto-connect-level-sets-all-pages []
  (check-settings-action! (assoc initial-state :settings/auto-connect-level "off")
                          [:settings/ax.set-auto-connect-level "all-pages"]
                          [:settings/auto-connect-level "all-pages"] :popup/fx.save-auto-connect-level))

(defn- ^:async test-set-auto-connect-level-sets-all-tabs []
  (check-settings-action! (assoc initial-state :settings/auto-connect-level "off")
                          [:settings/ax.set-auto-connect-level "all-tabs"]
                          [:settings/auto-connect-level "all-tabs"] :popup/fx.save-auto-connect-level))

(defn- ^:async test-set-auto-connect-level-sets-off []
  (check-settings-action! (assoc initial-state :settings/auto-connect-level "all-pages")
                          [:settings/ax.set-auto-connect-level "off"]
                          [:settings/auto-connect-level "off"] :popup/fx.save-auto-connect-level))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "Popup Settings Actions"
  (fn []
    ;; Auto-connect level
    (test "load-auto-connect-level triggers effect" test-load-auto-connect-level-triggers-effect)
    (test "set-auto-connect-level sets all-pages" test-set-auto-connect-level-sets-all-pages)
    (test "set-auto-connect-level sets all-tabs" test-set-auto-connect-level-sets-all-tabs)
    (test "set-auto-connect-level sets off" test-set-auto-connect-level-sets-off)
    ;; Auto-reconnect
    (test "load-auto-reconnect-setting triggers effect" test-load-auto-reconnect-setting-triggers-effect)
    (test "toggle-auto-reconnect-repl toggles true to false" test-toggle-auto-reconnect-repl-toggles-true-to-false)
    (test "toggle-auto-reconnect-repl toggles false to true" test-toggle-auto-reconnect-repl-toggles-false-to-true)
    ;; Host permissions
    (test "check-host-permission triggers effect" test-check-host-permission-triggers-effect)
    (test "request-host-permission triggers effect" test-request-host-permission-triggers-effect)
    (test "request-host-permission passes current tab-id" test-request-host-permission-passes-tab-id)
    (test "request-host-permission passes nil when no tab-id" test-request-host-permission-passes-nil-tab-id-when-missing)))
