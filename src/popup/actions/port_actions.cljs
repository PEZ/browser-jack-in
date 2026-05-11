(ns popup.actions.port-actions
  (:require [popup.utils :as popup-utils]))

(defn normalize-domain-ports
  "Pure helper: given default ports and per-domain ports, computes
   effective ports, whether to persist, and the source of each port.
   Returns {:effective-ports {:nrepl str :ws str}
            :persist? boolean
            :normalized-domain-ports map-or-nil
            :source {:nrepl :default|:override :ws :default|:override}}"
  [defaults domain-ports]
  (let [nrepl-default (:nrepl defaults)
        ws-default (:ws defaults)
        nrepl-domain (:nrepl domain-ports)
        ws-domain (:ws domain-ports)
        nrepl-effective (or nrepl-domain nrepl-default)
        ws-effective (or ws-domain ws-default)
        nrepl-overridden? (and (some? nrepl-domain) (not= nrepl-domain nrepl-default))
        ws-overridden? (and (some? ws-domain) (not= ws-domain ws-default))
        any-override? (or nrepl-overridden? ws-overridden?)]
    {:effective-ports {:nrepl nrepl-effective :ws ws-effective}
     :persist? any-override?
     :normalized-domain-ports (when any-override?
                                 {:nrepl nrepl-effective :ws ws-effective})
     :source {:nrepl (if nrepl-overridden? :override :default)
              :ws (if ws-overridden? :override :default)}}))

(defn set-port [state port-key port]
  (let [new-state (assoc state port-key port)
        defaults {:nrepl (:settings/default-nrepl-port state)
                  :ws (:settings/default-ws-port state)}
        domain-ports {:nrepl (:ports/nrepl new-state)
                      :ws (:ports/ws new-state)}
        {:keys [persist? source]} (normalize-domain-ports defaults domain-ports)]
    {:uf/db (assoc new-state :ports/source source)
     :uf/fxs [(if persist?
                [:popup/fx.save-ports (select-keys new-state [:ports/nrepl :ports/ws])]
                [:popup/fx.clear-domain-ports])]}))

(defn copy-command [state uf-data]
  (let [deps-string (:config/deps-string uf-data)
        cmd (popup-utils/generate-server-cmd
             {:deps-string deps-string
              :nrepl-port (:ports/nrepl state)
              :ws-port (:ports/ws state)})]
    {:uf/fxs [[:popup/fx.copy-command cmd]]}))

(defn connect [state]
  (when-not (:ui/connecting? state)
    (let [port (js/parseInt (:ports/ws state) 10)]
      (when (and (not (js/isNaN port)) (<= 1 port 65535))
        {:uf/db (assoc state :ui/connecting? true)
         :uf/fxs [[:popup/fx.connect port]]}))))

(defn apply-init-ports [state storage-data]
  (let [{:keys [stored-defaults domain-ports]} storage-data
        defaults {:nrepl (or (:nrepl stored-defaults) "3339")
                  :ws (or (:ws stored-defaults) "3340")}
        {:keys [effective-ports source]} (normalize-domain-ports defaults domain-ports)]
    {:uf/db (-> state
                (assoc :settings/default-nrepl-port (:nrepl defaults))
                (assoc :settings/default-ws-port (:ws defaults))
                (assoc :ports/nrepl (:nrepl effective-ports))
                (assoc :ports/ws (:ws effective-ports))
                (assoc :ports/source source))}))

(defn set-default-port [state settings-key port]
  (let [other-key (if (= settings-key :settings/default-nrepl-port)
                    :settings/default-ws-port
                    :settings/default-nrepl-port)
        new-defaults (if (= settings-key :settings/default-nrepl-port)
                       {:nrepl port :ws (get state other-key)}
                       {:nrepl (get state other-key) :ws port})
        source (:ports/source state)
        domain-ports (cond-> {}
                       (= :override (:nrepl source)) (assoc :nrepl (:ports/nrepl state))
                       (= :override (:ws source)) (assoc :ws (:ports/ws state)))
        {:keys [effective-ports persist?]
         new-source :source} (normalize-domain-ports new-defaults domain-ports)
        new-state (-> state
                      (assoc settings-key port)
                      (assoc :ports/nrepl (:nrepl effective-ports))
                      (assoc :ports/ws (:ws effective-ports))
                      (assoc :ports/source new-source))]
    {:uf/db new-state
     :uf/fxs [[:popup/fx.save-default-ports-setting (select-keys new-state [:settings/default-nrepl-port :settings/default-ws-port])]
              (if persist?
                [:popup/fx.save-ports (select-keys new-state [:ports/nrepl :ports/ws])]
                [:popup/fx.clear-domain-ports])]}))

(defn on-default-ports-changed [state new-defaults domain-ports]
  (let [{:keys [effective-ports source]} (normalize-domain-ports new-defaults domain-ports)
        new-db (-> state
                   (assoc :settings/default-nrepl-port (:nrepl new-defaults))
                   (assoc :settings/default-ws-port (:ws new-defaults))
                   (assoc :ports/nrepl (:nrepl effective-ports))
                   (assoc :ports/ws (:ws effective-ports))
                   (assoc :ports/source source))]
    {:uf/db (if (= new-db state) state new-db)}))

(defn apply-port-migration [_state migration-data]
  (let [{:keys [defaults port-entries]} migration-data
        redundant-keys (reduce-kv (fn [acc storage-key domain-ports]
                                    (let [{:keys [persist?]} (normalize-domain-ports defaults domain-ports)]
                                      (if persist? acc (conj acc storage-key))))
                                  []
                                  port-entries)]
    {:uf/fxs [[:popup/fx.remove-storage-keys redundant-keys]
              [:popup/fx.set-storage-key "epupp_migration_ports_normalized_v1" true]]}))
