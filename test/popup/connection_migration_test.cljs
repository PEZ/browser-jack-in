(ns popup.connection-migration-test
  "Tests for port migration cleanup"
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
;; Port migration cleanup tests
;; ============================================================

(defn- test-migration-removes-redundant-entries []
  ;; ports_foo.com has same ports as defaults => redundant => remove
  (let [defaults {:nrepl "1339" :ws "1340"}
        storage-data {"ports_foo.com" {:nrepl "1339" :ws "1340"}
                      "ports_bar.com" {:nrepl "1339" :ws "1340"}}
        migration-data {:defaults defaults :port-entries storage-data}
        result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.apply-port-migration migration-data])
        fxs (:uf/fxs result)
        remove-fx (first (filter #(= :popup/fx.remove-storage-keys (first %)) fxs))
        marker-fx (first (filter #(= :popup/fx.set-storage-key (first %)) fxs))]
    ;; Should remove both redundant keys
    (-> (expect (set (second remove-fx)))
        (.toEqual (set ["ports_foo.com" "ports_bar.com"])))
    ;; Should set the marker key
    (-> (expect (second marker-fx))
        (.toBe "epupp_migration_ports_normalized_v1"))))

(defn- assert-no-keys-removed [storage-data]
  (let [result (popup-actions/handle-action initial-state uf-data
                                            [:connection/ax.apply-port-migration
                                             {:defaults {:nrepl "1339" :ws "1340"} :port-entries storage-data}])
        fxs (:uf/fxs result)
        remove-fx (first (filter #(= :popup/fx.remove-storage-keys (first %)) fxs))]
    (-> (expect (second remove-fx))
        (.toEqual []))))

(defn- test-migration-preserves-explicit-overrides []
  ;; ports_custom.com has different ports => keep
  (assert-no-keys-removed {"ports_custom.com" {:nrepl "9999" :ws "9998"}}))

(defn- test-migration-mixed-redundant-and-overrides []
  ;; Mix of redundant and explicit overrides
  (let [defaults {:nrepl "1339" :ws "1340"}
        storage-data {"ports_default.com" {:nrepl "1339" :ws "1340"}
                      "ports_custom.com" {:nrepl "5555" :ws "5556"}
                      "ports_also-default.com" {:nrepl "1339" :ws "1340"}}
        migration-data {:defaults defaults :port-entries storage-data}
        result (popup-actions/handle-action initial-state uf-data
                 [:connection/ax.apply-port-migration migration-data])
        fxs (:uf/fxs result)
        remove-fx (first (filter #(= :popup/fx.remove-storage-keys (first %)) fxs))]
    ;; Should only remove redundant keys
    (-> (expect (set (second remove-fx)))
        (.toEqual (set ["ports_default.com" "ports_also-default.com"])))))

(defn- apply-empty-migration-fxs []
  (:uf/fxs (popup-actions/handle-action initial-state uf-data
                                        [:connection/ax.apply-port-migration
                                         {:defaults {:nrepl "1339" :ws "1340"} :port-entries {}}])))

(defn- test-migration-sets-marker-key []
  (let [fxs (apply-empty-migration-fxs)
        marker-fx (first (filter #(= :popup/fx.set-storage-key (first %)) fxs))]
    (-> (expect (second marker-fx))
        (.toBe "epupp_migration_ports_normalized_v1"))
    (-> (expect (nth marker-fx 2))
        (.toBe true))))

(defn- test-migration-handles-empty-storage []
  (let [fxs (apply-empty-migration-fxs)
        remove-fx (first (filter #(= :popup/fx.remove-storage-keys (first %)) fxs))]
    (-> (expect (second remove-fx))
        (.toEqual []))))

(defn- test-migration-partial-override-kept []
  ;; One port matches default, other is overridden => keep (has real override)
  (assert-no-keys-removed {"ports_partial.com" {:nrepl "1339" :ws "9999"}}))

(describe "port migration cleanup"
          (fn []
            (test "removes redundant entries matching defaults"
                  test-migration-removes-redundant-entries)
            (test "preserves explicit overrides"
                  test-migration-preserves-explicit-overrides)
            (test "mixed redundant and override entries"
                  test-migration-mixed-redundant-and-overrides)
            (test "sets marker key after completion"
                  test-migration-sets-marker-key)
            (test "handles empty storage gracefully"
                  test-migration-handles-empty-storage)
            (test "keeps entry when only one port is overridden"
                  test-migration-partial-override-kept)))
