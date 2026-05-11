(ns popup.actions.port-actions
  (:require [popup.utils :as popup-utils]))

(defn- port-override? [domain-value default-value]
  (and (some? domain-value) (not= domain-value default-value)))

(defn normalize-domain-ports
  "Pure helper: given default ports and per-domain ports, computes
   effective ports, whether to persist, and the source of each port.
   Returns {:effective-ports {:nrepl str :ws str}
            :persist? boolean
            :normalized-domain-ports map-or-nil
            :source {:nrepl :default|:override :ws :default|:override}}"
  [defaults domain-ports]
  (let [nrepl-effective (or (:nrepl domain-ports) (:nrepl defaults))
        ws-effective (or (:ws domain-ports) (:ws defaults))
        nrepl-overridden? (port-override? (:nrepl domain-ports) (:nrepl defaults))
        ws-overridden? (port-override? (:ws domain-ports) (:ws defaults))
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

(defn cancel-connect [state]
  {:uf/db (assoc state :ui/connecting? false)})

(defn connect-finished [state]
  {:uf/db (assoc state :ui/connecting? false)})

(defn set-connect-mode [state mode]
  {:uf/db (assoc state :ui/connect-mode mode)})

(defn check-status [state]
  {:uf/fxs [[:popup/fx.check-status (:ports/ws state)]]})

(defn load-saved-ports [state]
  {:uf/fxs [[:popup/fx.load-saved-ports (:settings/default-nrepl-port state) (:settings/default-ws-port state)]]})

(defn init-ports []
  {:uf/fxs [[:popup/fx.init-ports]]})

(defn load-default-ports-setting []
  {:uf/fxs [[:popup/fx.load-default-ports-setting]]})

(defn run-port-migration []
  {:uf/fxs [[:popup/fx.run-port-migration]]})

(defn load-connections []
  {:uf/fxs [[:popup/fx.load-connections]]})

(defn handle-action [state uf-data [action & args]]
  (case action
    :connection/ax.set-nrepl-port (set-port state :ports/nrepl (first args))
    :connection/ax.set-ws-port (set-port state :ports/ws (first args))
    :connection/ax.copy-command (copy-command state uf-data)
    :connection/ax.connect (connect state)
    :connection/ax.cancel-connect (cancel-connect state)
    :connection/ax.connect-finished (connect-finished state)
    :connection/ax.set-connect-mode (set-connect-mode state (first args))
    :connection/ax.check-status (check-status state)
    :connection/ax.load-saved-ports (load-saved-ports state)
    :connection/ax.init-ports (init-ports)
    :connection/ax.apply-init-ports (apply-init-ports state (first args))
    :connection/ax.set-default-nrepl-port (set-default-port state :settings/default-nrepl-port (first args))
    :connection/ax.set-default-ws-port (set-default-port state :settings/default-ws-port (first args))
    :connection/ax.load-default-ports-setting (load-default-ports-setting)
    :connection/ax.on-default-ports-changed (on-default-ports-changed state (first args) (second args))
    :connection/ax.run-port-migration (run-port-migration)
    :connection/ax.apply-port-migration (apply-port-migration state (first args))
    :connection/ax.load-connections (load-connections)
    :uf/unhandled-ax))
