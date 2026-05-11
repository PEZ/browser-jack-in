(ns background-actions
  "Routes background actions to domain-specific handler modules."
  (:require [background-actions.msg-actions :as msg-actions]
            [background-actions.fs-guard-actions :as fs-guard-actions]
            [background-actions.nav-actions :as nav-actions]
            [background-actions.dep-actions :as dep-actions]
            [background-actions.repl-fs-actions :as repl-fs-actions]
            [background-actions.fs-actions :as fs-actions]
            [background-actions.icon-actions :as icon-actions]
            [background-actions.history-actions :as history-actions]
            [background-actions.ws-actions :as ws-actions]
            [background-actions.sponsor-actions :as sponsor-actions]
            [background-utils :as bg-utils]))

(def ^:private msg-action-set
  {:msg/ax.connect-tab true
   :msg/ax.check-status true
   :msg/ax.ensure-scittle true
   :msg/ax.ensure-scittle-result true
   :msg/ax.evaluate-script true
   :msg/ax.e2e-get-storage true
   :msg/ax.e2e-set-storage true
   :msg/ax.e2e-find-tab-id true
   :msg/ax.list-scripts-result true
   :msg/ax.get-script-result true
   :msg/ax.inject-libs true
   :msg/ax.inject-libs-ready true
   :msg/ax.load-manifest true
   :msg/ax.load-manifest-ready true
   :msg/ax.get-connections true
   :msg/ax.list-scripts true
   :msg/ax.get-script true
   :msg/ax.e2e-get-test-events true
   :msg/ax.handle-permission-granted true
   :msg/ax.e2e-get-icon-display-state true
   :msg/ax.log-resolution-error true})

(def ^:private fs-guard-action-set
  {:fs/ax.guard-list-scripts true
   :fs/ax.guard-get-script true
   :fs/ax.guard-rename-script true
   :fs/ax.guard-delete-script true
   :fs/ax.guard-save-script true})

(def ^:private nav-action-set
  {:nav/ax.decide-connection true
   :nav/ax.handle-navigation true
   :nav/ax.handle-before-navigate true
   :tab/ax.handle-removed true
   :visibility/ax.handle-tab-visible true
   :visibility/ax.decide-reconnect true})

(def ^:private dep-action-set
  {:runtime/ax.set-tab-errors true
   :runtime/ax.get-tab-errors true
   :runtime/ax.re-resolve-on-change true
   :ext-dep/ax.resolve-uncached-urls true
   :ext-dep/ax.cache-results true})

;; Simple delegation handlers for existing modules
(def ^:private delegation-handlers
  {:fs/ax.rename-script
   (fn [state uf-data args]
     (let [[from-name to-name force?] args]
       (repl-fs-actions/rename-script
        state
        (cond-> {:fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
                 :fs/from-name from-name
                 :fs/to-name to-name}
          force? (assoc :fs/force? true)))))

   :fs/ax.delete-script
   (fn [state _uf-data args]
     (let [[payload] args
           {:keys [script-name bulk-id bulk-index bulk-count]}
           (if (map? payload) payload {:script-name payload})]
       (repl-fs-actions/delete-script
        state
        {:fs/script-name script-name
         :fs/bulk-id bulk-id
         :fs/bulk-index bulk-index
         :fs/bulk-count bulk-count})))

   :fs/ax.save-script
   (fn [state uf-data args]
     (let [[script] args]
       (repl-fs-actions/save-script
        state
        {:fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
         :fs/script script})))

   :fs/ax.toggle-sync
   (fn [state _uf-data args]
     (let [[tab-id enabled send-response] args]
       (fs-actions/toggle-sync state {:fs/tab-id tab-id :fs/enabled enabled :fs/send-response send-response})))

   :fs/ax.get-sync-status
   (fn [state _uf-data args]
     (let [[send-response] args]
       (fs-actions/get-sync-status state {:fs/send-response send-response})))

   :icon/ax.set-state
   (fn [state _uf-data args]
     (let [[tab-id new-state] args]
       (icon-actions/set-state state {:icon/tab-id tab-id :icon/new-state new-state})))

   :icon/ax.clear
   (fn [state _uf-data args]
     (let [[tab-id] args]
       (icon-actions/clear-state state {:icon/tab-id tab-id})))

   :icon/ax.prune
   (fn [state _uf-data args]
     (let [[valid-tab-ids] args]
       (icon-actions/prune-states state {:icon/valid-tab-ids valid-tab-ids})))

   :history/ax.track
   (fn [state _uf-data args]
     (let [[tab-id port] args]
       (history-actions/track state {:history/tab-id tab-id :history/port port})))

   :history/ax.forget
   (fn [state _uf-data args]
     (let [[tab-id] args]
       (history-actions/forget state {:history/tab-id tab-id})))

   :ws/ax.register
   (fn [state _uf-data args]
     (let [[tab-id connection-info] args]
       (ws-actions/register state {:ws/tab-id tab-id :ws/connection-info connection-info})))

   :ws/ax.unregister
   (fn [state _uf-data args]
     (let [[tab-id] args]
       (ws-actions/unregister state {:ws/tab-id tab-id})))

   :ws/ax.broadcast
   (fn [state _uf-data _args]
     {:uf/fxs [[:ws/fx.broadcast-connections-changed! (:ws/connections state)]]})

   :ws/ax.handle-connect
   (fn [state _uf-data args]
     (let [[tab-id port] args]
       (ws-actions/handle-connect state {:ws/tab-id tab-id :ws/port port})))

   :ws/ax.handle-send
   (fn [state _uf-data args]
     (let [[tab-id data] args]
       (ws-actions/handle-send state {:ws/tab-id tab-id :ws/data data})))

   :ws/ax.handle-close
   (fn [state _uf-data args]
     (let [[tab-id] args]
       (ws-actions/handle-close state {:ws/tab-id tab-id})))

   :ws/ax.explicit-disconnect
   (fn [state _uf-data args]
     (let [[tab-id] args]
       (ws-actions/explicit-disconnect state {:ws/tab-id tab-id})))

   :sponsor/ax.set-pending
   (fn [state uf-data args]
     (let [[tab-id] args]
       (sponsor-actions/set-pending state {:sponsor/tab-id tab-id
                                           :sponsor/now (:system/now uf-data)})))

   :sponsor/ax.consume-pending
   (fn [state uf-data args]
     (let [[tab-id tab-url send-response] args]
       (sponsor-actions/consume-pending state {:sponsor/tab-id tab-id
                                               :sponsor/now (:system/now uf-data)
                                               :sponsor/tab-url tab-url
                                               :sponsor/send-response send-response})))

   :storage/ax.set-ext-dep-cache
   (fn [state _uf-data args]
     (let [[cache] args]
       {:uf/db (assoc state :storage/ext-dep-cache (or cache {}))}))

   :init/ax.clear-promise
   (fn [state _uf-data _args]
     {:uf/db (assoc state :init/promise nil)})})

;; Remaining actions handled individually
(defn- handle-ensure-initialized [state]
  (if-let [promise (:init/promise state)]
    {:uf/db state
     :uf/fxs [[:uf/await :init/fx.await-promise promise]]}
    (let [resolve-fn (volatile! nil)
          reject-fn (volatile! nil)
          promise (js/Promise. (fn [resolve reject]
                                 (vreset! resolve-fn resolve)
                                 (vreset! reject-fn reject)))]
      {:uf/db (assoc state :init/promise promise)
       :uf/fxs [[:uf/await :init/fx.initialize @resolve-fn @reject-fn]]})))

(defn- handle-alarm-tick [state]
  (let [connections (or (:ws/connections state) {})]
    (when (seq connections)
      {:uf/fxs [[:alarm/fx.log-tick (count connections)]]})))

(defn- handle-banner-broadcast [args]
  (let [[errors] args
        messages (mapv :error/message errors)]
    {:uf/fxs [[:banner/fx.broadcast-system {:event-type "error"
                                            :operation "library-resolution"
                                            :error (first messages)
                                            :errors messages}]]}))

(defn- handle-refresh-toolbar [state args]
  (let [[tab-id] args
        display-state (bg-utils/compute-display-icon-state (:icon/states state) tab-id)]
    {:uf/fxs [[:icon/fx.update-toolbar! tab-id display-state]]}))

(defn- handle-misc-action [state action args]
  (case action
    :init/ax.ensure-initialized (handle-ensure-initialized state)
    :alarm/ax.tick (handle-alarm-tick state)
    :banner/ax.broadcast-resolution-errors (handle-banner-broadcast args)
    :icon/ax.refresh-toolbar (handle-refresh-toolbar state args)
    :uf/unhandled-ax))

(defn handle-action
  "Routes background actions to domain-specific handler modules."
  [state uf-data [action & args :as action-vec]]
  (cond
    (get msg-action-set action)
    (msg-actions/handle-action state uf-data action-vec)

    (get fs-guard-action-set action)
    (fs-guard-actions/handle-action state uf-data action-vec)

    (get nav-action-set action)
    (nav-actions/handle-action state uf-data action-vec)

    (get dep-action-set action)
    (dep-actions/handle-action state uf-data action-vec)

    (get delegation-handlers action)
    ((get delegation-handlers action) state uf-data args)

    :else
    (handle-misc-action state action args)))
