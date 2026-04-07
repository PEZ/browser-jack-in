(ns background-actions
  (:require [background-actions.repl-fs-actions :as repl-fs-actions]
            [background-actions.fs-actions :as fs-actions]
            [background-actions.icon-actions :as icon-actions]
            [background-actions.history-actions :as history-actions]
            [background-actions.ws-actions :as ws-actions]
            [background-actions.sponsor-actions :as sponsor-actions]
            [background-utils :as bg-utils]
            [dep-resolver :as dep-resolver]
            [ext-dep :as ext-dep]
            [scittle-libs :as scittle-libs]
            [script-utils :as script-utils]))

(defn handle-action
  "Pure function - no side effects allowed."
  [state uf-data [action & args]]
  (case action
    :fs/ax.guard-list-scripts
    (let [[tab-id send-response include-hidden?] args
          allowed? (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id)]
      (if allowed?
        {:uf/fxs [[:msg/fx.list-scripts send-response include-hidden?]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "FS Sync requires an active REPL connection and FS Sync enabled in settings"}]]}))

    :fs/ax.guard-get-script
    (let [[tab-id send-response script-name] args
          allowed? (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id)]
      (if allowed?
        {:uf/fxs [[:msg/fx.get-script send-response script-name]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "FS Sync requires an active REPL connection and FS Sync enabled in settings"}]]}))

    :fs/ax.guard-rename-script
    (let [[tab-id send-response from-name to-name] args
          allowed? (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id)]
      (if allowed?
        (let [name-error (script-utils/validate-script-name to-name)]
          (if name-error
            {:uf/fxs [[:msg/fx.send-response send-response {:success false :error name-error}]]}
            {:uf/fxs [[:fs/fx.dispatch-action send-response [:fs/ax.rename-script from-name to-name]]]}))
        {:uf/fxs [[:banner/fx.broadcast-system {:event-type "error"
                                                :operation "rename"
                                                :error "FS Sync requires an active REPL connection and FS Sync enabled in settings"}]
                  [:msg/fx.send-response send-response {:success false
                                                        :error "FS Sync requires an active REPL connection and FS Sync enabled in settings"}]]}))

    :fs/ax.guard-delete-script
    (let [[tab-id send-response delete-params] args
          allowed? (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id)]
      (if allowed?
        {:uf/fxs [[:fs/fx.dispatch-action send-response [:fs/ax.delete-script delete-params]]]}
        {:uf/fxs [[:banner/fx.broadcast-system {:event-type "error"
                                                :operation "delete"
                                                :error "FS Sync requires an active REPL connection and FS Sync enabled in settings"}]
                  [:msg/fx.send-response send-response {:success false
                                                        :error "FS Sync requires an active REPL connection and FS Sync enabled in settings"}]]}))

    :fs/ax.guard-save-script
    (let [[tab-id send-response raw-data web-install?] args
          allowed? (or web-install?
                       (bg-utils/fs-access-allowed? (:fs/sync-tab-id state) (:ws/connections state) tab-id))]
      (if allowed?
        {:uf/fxs [[:fs/fx.parse-and-save send-response raw-data]]}
        {:uf/fxs [[:banner/fx.broadcast-system {:event-type "error"
                                                :operation "save"
                                                :error "FS Sync requires an active REPL connection and FS Sync enabled in settings"}]
                  [:msg/fx.send-response send-response {:success false
                                                        :error "FS Sync requires an active REPL connection and FS Sync enabled in settings"}]]}))

    :fs/ax.rename-script
    (let [[from-name to-name] args]
      (repl-fs-actions/rename-script
       state
       {:fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
        :fs/from-name from-name
        :fs/to-name to-name}))

    :fs/ax.delete-script
    (let [[payload] args
          {:keys [script-name bulk-id bulk-index bulk-count]} (if (map? payload)
                                                                payload
                                                                {:script-name payload})]
      (repl-fs-actions/delete-script
       state
       {:fs/script-name script-name
        :fs/bulk-id bulk-id
        :fs/bulk-index bulk-index
        :fs/bulk-count bulk-count}))

    :fs/ax.save-script
    (let [[script] args]
      (repl-fs-actions/save-script
       state
       {:fs/now-iso (.toISOString (js/Date. (:system/now uf-data)))
        :fs/script script}))

    :fs/ax.toggle-sync
    (let [[tab-id enabled send-response] args]
      (fs-actions/toggle-sync state {:fs/tab-id tab-id :fs/enabled enabled :fs/send-response send-response}))

    :fs/ax.get-sync-status
    (let [[send-response] args]
      (fs-actions/get-sync-status state {:fs/send-response send-response}))

    :icon/ax.set-state
    (let [[tab-id new-state] args]
      (icon-actions/set-state
       state
       {:icon/tab-id tab-id
        :icon/new-state new-state}))

    :icon/ax.clear
    (let [[tab-id] args]
      (icon-actions/clear-state
       state
       {:icon/tab-id tab-id}))

    :icon/ax.prune
    (let [[valid-tab-ids] args]
      (icon-actions/prune-states
       state
       {:icon/valid-tab-ids valid-tab-ids}))

    :history/ax.track
    (let [[tab-id port] args]
      (history-actions/track
       state
       {:history/tab-id tab-id
        :history/port port}))

    :history/ax.forget
    (let [[tab-id] args]
      (history-actions/forget
       state
       {:history/tab-id tab-id}))

    :ws/ax.register
    (let [[tab-id connection-info] args]
      (ws-actions/register
       state
       {:ws/tab-id tab-id
        :ws/connection-info connection-info}))

    :ws/ax.unregister
    (let [[tab-id] args]
      (ws-actions/unregister
       state
       {:ws/tab-id tab-id}))

    :sponsor/ax.set-pending
    (let [[tab-id] args]
      (sponsor-actions/set-pending state {:sponsor/tab-id tab-id
                                          :sponsor/now (:system/now uf-data)}))

    :sponsor/ax.consume-pending
    (let [[tab-id tab-url send-response] args]
      (sponsor-actions/consume-pending state {:sponsor/tab-id tab-id
                                              :sponsor/now (:system/now uf-data)
                                              :sponsor/tab-url tab-url
                                              :sponsor/send-response send-response}))

    :ws/ax.broadcast
    {:uf/fxs [[:ws/fx.broadcast-connections-changed! (:ws/connections state)]]}

    :ws/ax.handle-connect
    (let [[tab-id port] args]
      (ws-actions/handle-connect state {:ws/tab-id tab-id :ws/port port}))

    :ws/ax.handle-send
    (let [[tab-id data] args]
      (ws-actions/handle-send state {:ws/tab-id tab-id :ws/data data}))

    :ws/ax.handle-close
    (let [[tab-id] args]
      (ws-actions/handle-close state {:ws/tab-id tab-id}))

    :ws/ax.explicit-disconnect
    (let [[tab-id] args]
      (ws-actions/explicit-disconnect state {:ws/tab-id tab-id}))

    :init/ax.ensure-initialized
    (if-let [promise (:init/promise state)]
      ;; Already initializing/initialized - await existing promise
      {:uf/db state
       :uf/fxs [[:uf/await :init/fx.await-promise promise]]}
      ;; First call - create promise and initialize
      (let [resolve-fn (volatile! nil)
            reject-fn (volatile! nil)
            promise (js/Promise. (fn [resolve reject]
                                   (vreset! resolve-fn resolve)
                                   (vreset! reject-fn reject)))]
        {:uf/db (assoc state :init/promise promise)
         :uf/fxs [[:uf/await :init/fx.initialize @resolve-fn @reject-fn]]}))

    :init/ax.clear-promise
    {:uf/db (assoc state :init/promise nil)}

    :msg/ax.connect-tab
    (let [[send-response tab-id ws-port] args
          icon-state (get-in state [:icon/states tab-id] :disconnected)]
      {:uf/fxs [[:uf/await :repl/fx.connect-tab tab-id ws-port icon-state]
                [:msg/fx.send-response send-response :uf/prev-result]]})

    :msg/ax.check-status
    (let [[send-response tab-id] args]
      {:uf/fxs [[:uf/await :page/fx.check-status tab-id]
                [:msg/fx.send-response send-response :uf/prev-result]]})

    :msg/ax.ensure-scittle
    (let [[send-response tab-id] args
          icon-state (get-in state [:icon/states tab-id] :disconnected)]
      {:uf/fxs [[:msg/fx.ensure-scittle send-response tab-id icon-state]]})

    :msg/ax.ensure-scittle-result
    (let [[send-response {:keys [ok? error]}] args
          response (cond-> {:success (boolean ok?)}
                     error (assoc :error error))]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.evaluate-script
    (let [[send-response tab-id code libs script-id] args
          icon-state (get-in state [:icon/states tab-id] :disconnected)
          script (cond-> {:script/id script-id
                          :script/name "popup-eval"
                          :script/code code}
                   libs (assoc :script/inject libs))]
      {:uf/fxs [[:uf/await :script/fx.evaluate tab-id script icon-state]
                [:msg/fx.send-response send-response :uf/prev-result]]})

    :msg/ax.e2e-get-storage
    (let [[send-response key] args]
      (if key
        {:uf/fxs [[:uf/await :storage/fx.get-local-storage key]
                  [:msg/fx.send-response send-response :uf/prev-result]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "Missing key"}]]}))

    :msg/ax.e2e-set-storage
    (let [[send-response key value] args]
      (if key
        {:uf/fxs [[:uf/await :storage/fx.set-local-storage key value]
                  [:msg/fx.send-response send-response :uf/prev-result]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "Missing key"}]]}))

    :msg/ax.inject-libs
    (let [[send-response tab-id libs all-scripts] args
          ;; Build a synthetic script with the inject list and resolve via dep-resolver
          synthetic-script {:script/id "panel-inject" :script/name "panel-inject"
                            :script/code "" :script/inject (or libs [])}
          ext-dep-cache (or (:storage/ext-dep-cache state) {})
          plan (dep-resolver/resolve-execution-plan [synthetic-script] (or all-scripts []) ext-dep-cache)
          errors (:plan/errors plan)
          vendor-files (dep-resolver/plan-vendor-files plan)
          ;; Only library-script and ext-dep-script steps (deps), not root-script (the synthetic placeholder)
          lib-steps (filterv #(contains? #{:library-script :ext-dep-script} (:step/type %)) (:plan/steps plan))
          ;; Build effect chain: bridge + vendor files + library scripts + response
          vendor-fxs (mapv (fn [path]
                             ;; plan-vendor-files returns "vendor/file.js", strip prefix
                             [:uf/await :msg/fx.inject-lib-file tab-id (subs path 7)])
                           vendor-files)
          lib-script-fxs (mapv (fn [step]
                                 [:uf/await :msg/fx.inject-script-code tab-id
                                  (:step/id step) (:step/code step)])
                               lib-steps)]
      (cond-> {}
        (seq errors) (assoc :uf/dxs (vec (cons [:banner/ax.broadcast-resolution-errors errors]
                                               (map (fn [e] [:msg/ax.log-resolution-error e]) errors))))
        (or (seq vendor-files) (seq lib-steps))
        (assoc :uf/fxs (-> [[:uf/await :msg/fx.inject-bridge tab-id]
                             [:uf/await :msg/fx.wait-bridge-ready tab-id]]
                            (into vendor-fxs)
                            (into lib-script-fxs)
                            (conj [:uf/await :msg/fx.trigger-scittle tab-id])
                            (conj [:uf/await :msg/fx.send-response send-response {:success true}])))
        (and (empty? vendor-files) (empty? lib-steps))
        (assoc :uf/fxs [[:msg/fx.send-response send-response {:success true}]])))

    :msg/ax.list-scripts-result
    (let [[send-response {:keys [include-hidden? scripts]}] args
          visible-scripts (script-utils/filter-visible-scripts scripts include-hidden?)
          public-scripts (mapv repl-fs-actions/script->base-info visible-scripts)]
      {:uf/fxs [[:msg/fx.send-response send-response {:success true
                                                      :scripts public-scripts}]]})

    :msg/ax.get-script-result
    (let [[send-response {:keys [script-name script]}] args
          response (if script
                     {:success true :code (:script/code script)}
                     {:success false :error (str "Script not found: " script-name)})]
      {:uf/fxs [[:msg/fx.send-response send-response response]]})

    :msg/ax.list-scripts
    (let [[send-response include-hidden?] args]
      {:uf/fxs [[:msg/fx.list-scripts send-response include-hidden?]]})

    :msg/ax.get-script
    (let [[send-response script-name] args]
      {:uf/fxs [[:msg/fx.get-script send-response script-name]]})

    :msg/ax.load-manifest
    (let [[send-response tab-id manifest all-scripts] args
          libs (when manifest (vec (aget manifest "inject")))
          ;; Use resolver for mixed scittle:// + epupp:// deps
          synthetic-script {:script/id "repl-manifest" :script/name "repl-manifest"
                            :script/code "" :script/inject (or libs [])}
          ext-dep-cache (or (:storage/ext-dep-cache state) {})
          plan (dep-resolver/resolve-execution-plan [synthetic-script] (or all-scripts []) ext-dep-cache)
          errors (:plan/errors plan)
          vendor-files (dep-resolver/plan-vendor-files plan)
          ;; Only library-script and ext-dep-script steps (deps)
          lib-steps (filterv #(contains? #{:library-script :ext-dep-script} (:step/type %)) (:plan/steps plan))
          vendor-fxs (mapv (fn [path]
                             [:uf/await :msg/fx.inject-lib-file tab-id (subs path 7)])
                           vendor-files)
          lib-script-fxs (mapv (fn [step]
                                 [:uf/await :msg/fx.inject-script-code tab-id
                                  (:step/id step) (:step/code step)])
                               lib-steps)]
      (cond-> {}
        (seq errors) (assoc :uf/dxs (vec (cons [:banner/ax.broadcast-resolution-errors errors]
                                               (map (fn [e] [:msg/ax.log-resolution-error e]) errors))))
        (or (seq vendor-files) (seq lib-steps))
        (assoc :uf/fxs (-> [[:uf/await :msg/fx.inject-bridge tab-id]
                            [:uf/await :msg/fx.wait-bridge-ready tab-id]]
                            (into vendor-fxs)
                            (into lib-script-fxs)
                            (conj [:uf/await :msg/fx.trigger-scittle tab-id])
                            (conj [:uf/await :msg/fx.send-response send-response {:success true}])))
        (and (empty? vendor-files) (empty? lib-steps))
        (assoc :uf/fxs [[:msg/fx.send-response send-response {:success true}]])))

    :msg/ax.get-connections
    (let [[send-response] args
          connections (:ws/connections state)]
      {:uf/fxs [[:msg/fx.get-connections send-response connections]]})

    :msg/ax.e2e-find-tab-id
    (let [[send-response url-pattern] args]
      (if url-pattern
        {:uf/fxs [[:uf/await :tabs/fx.find-by-url-pattern url-pattern]
                  [:msg/fx.send-response send-response :uf/prev-result]]}
        {:uf/fxs [[:msg/fx.send-response send-response {:success false
                                                        :error "Missing urlPattern"}]]}))

    :msg/ax.e2e-get-test-events
    (let [[send-response] args]
      {:uf/fxs [[:msg/fx.e2e-get-test-events send-response]]})

    :nav/ax.decide-connection
    (let [[context] args
          {:nav/keys [tab-id url]} context
          icon-state (get-in state [:icon/states tab-id] :disconnected)
          {:keys [decision port]} (bg-utils/decide-connection
                                   {:trigger "navigation"
                                    :auto-connect-level (:nav/auto-connect-level context)
                                    :reconnect-on-nav? (:nav/auto-reconnect-enabled? context)
                                    :in-history? (:nav/in-history? context)
                                    :history-port (:nav/history-port context)
                                    :saved-port (:nav/saved-port context)})
          connect-fxs (when (not= decision "none")
                        [[:uf/await :nav/fx.connect tab-id port icon-state]])]
      {:uf/fxs (vec (concat connect-fxs
                            [[:nav/fx.process-navigation tab-id url icon-state]]))})

    :nav/ax.handle-navigation
    (let [[tab-id url] args
          history (:connected-tabs/history state)]
      {:uf/db (update state :runtime/errors dissoc tab-id)
       :uf/fxs [[:icon/fx.update-icon-disconnected tab-id]
                [:runtime/fx.broadcast-tab-status tab-id {}]
                [:uf/await :nav/fx.gather-auto-connect-context tab-id url history]]
       :uf/dxs [[:nav/ax.decide-connection :uf/prev-result]]})

    :tab/ax.handle-removed
    (let [[tab-id] args
          connections (or (:ws/connections state) {})
          has-ws? (some? (get connections tab-id))]
      {:uf/db (update state :runtime/errors dissoc tab-id)
       :uf/fxs (when has-ws?
                 [[:ws/fx.handle-close connections tab-id]])
       :uf/dxs [[:icon/ax.clear tab-id]
                [:history/ax.forget tab-id]]})

    :nav/ax.handle-before-navigate
    (let [[tab-id] args
          connections (or (:ws/connections state) {})
          has-ws? (some? (get connections tab-id))]
      (when has-ws?
        {:uf/fxs [[:ws/fx.handle-close connections tab-id]]}))

    :icon/ax.refresh-toolbar
    (let [[tab-id] args
          display-state (bg-utils/compute-display-icon-state (:icon/states state) tab-id)]
      {:uf/fxs [[:icon/fx.update-toolbar! tab-id display-state]]})

    :msg/ax.e2e-get-icon-display-state
    (let [[send-response tab-id] args
          display-state (bg-utils/compute-display-icon-state (:icon/states state) tab-id)]
      {:uf/fxs [[:msg/fx.send-response send-response {:success true :state display-state}]]})

    :msg/ax.handle-permission-granted
    (let [[tab-id] args
          icon-state (get-in state [:icon/states tab-id] :disconnected)]
      {:uf/fxs [[:msg/fx.handle-permission-granted tab-id icon-state]]})

    :visibility/ax.handle-tab-visible
    (let [[tab-id] args
          connections (or (:ws/connections state) {})
          has-ws? (some? (get connections tab-id))]
      (when-not has-ws?
        (let [history (:connected-tabs/history state)]
          {:uf/fxs [[:uf/await :visibility/fx.gather-reconnect-context tab-id history]]
           :uf/dxs [[:visibility/ax.decide-reconnect :uf/prev-result]]})))

    :visibility/ax.decide-reconnect
    (let [[context] args
          {:visibility/keys [tab-id auto-connect-level history-port saved-port]} context
          icon-state (get-in state [:icon/states tab-id] :disconnected)
          {:keys [decision port]} (bg-utils/decide-connection
                                   {:trigger "visibility"
                                    :auto-connect-level auto-connect-level
                                    :history-port history-port
                                    :saved-port saved-port})]
      (when (not= decision "none")
        {:uf/fxs [[:uf/await :nav/fx.connect tab-id port icon-state]]}))

    :alarm/ax.tick
    (let [connections (or (:ws/connections state) {})]
      (when (seq connections)
        {:uf/fxs [[:alarm/fx.log-tick (count connections)]]}))

    :banner/ax.broadcast-resolution-errors
    (let [[errors] args
          messages (mapv :error/message errors)]
      {:uf/fxs [[:banner/fx.broadcast-system {:event-type "error"
                                              :operation "library-resolution"
                                              :error (first messages)
                                              :errors messages}]]})

    :msg/ax.log-resolution-error
    (let [[error-envelope] args]
      {:uf/fxs [[:msg/fx.log-resolution-error error-envelope]]})

    :runtime/ax.set-tab-errors
    (let [[tab-id errors] args
          errors-by-name (into {} (map (fn [e] [(:error/script-name e) e]) errors))]
      {:uf/db (assoc-in state [:runtime/errors tab-id] errors-by-name)
       :uf/fxs [[:runtime/fx.broadcast-tab-status tab-id errors-by-name]]})

    :runtime/ax.get-tab-errors
    (let [[send-response tab-id] args
          tab-errors (get-in state [:runtime/errors tab-id] {})]
      {:uf/fxs [[:msg/fx.send-response send-response {:success true :errors tab-errors}]]})

    :runtime/ax.re-resolve-on-change
    (let [[all-scripts] args
          errors-by-tab (:runtime/errors state)
          connected-tabs (set (keys (:ws/connections state)))
          error-tabs (set (keys errors-by-tab))
          all-tabs (into connected-tabs error-tabs)]
      (when (seq all-tabs)
        (let [scripts-with-deps (filterv #(seq (:script/inject %)) all-scripts)
              plan (when (seq scripts-with-deps)
                     (dep-resolver/resolve-execution-plan scripts-with-deps all-scripts
                                                         (or (:storage/ext-dep-cache state) {})))
              new-errors (if plan (:plan/errors plan) [])
              all-known-errors (reduce into #{} (vals errors-by-tab))
              truly-new (filterv (fn [e] (not (some #(= (:error/script-name %) (:error/script-name e))
                                                    all-known-errors)))
                                 new-errors)]
          {:uf/fxs (cond-> (vec (map (fn [tab-id]
                                       [:runtime/fx.set-tab-errors tab-id new-errors])
                                     all-tabs))
                     (seq truly-new)
                     (conj [:banner/fx.broadcast-system {:event-type "error"
                                                        :operation "library-resolution"
                                                        :error (:error/message (first truly-new))
                                                        :errors (mapv :error/message truly-new)}]))})))

    :ext-dep/ax.resolve-uncached-urls
    (let [[ext-urls] args
          existing-cache (or (:storage/ext-dep-cache state) {})
          uncached (filterv #(not (contains? existing-cache %)) ext-urls)]
      (when (seq uncached)
        {:uf/fxs [[:uf/await :ext-dep/fx.fetch-deps uncached existing-cache]]
         :uf/dxs [[:ext-dep/ax.cache-results :uf/prev-result]]}))

    :ext-dep/ax.cache-results
    (let [[fetch-result] args
          resolved (or (:resolved fetch-result) {})
          errors (or (:errors fetch-result) [])
          existing-cache (or (:storage/ext-dep-cache state) {})
          merged-cache (merge existing-cache resolved)]
      (cond-> {:uf/db (assoc state :storage/ext-dep-cache merged-cache)
               :uf/fxs [[:storage/fx.persist!]]}
        (seq errors)
        (update :uf/fxs conj [:banner/fx.broadcast-system
                               {:event-type "error"
                                :operation "ext-dep-resolution"
                                :error (:error/message (first errors))
                                :errors (mapv :error/message errors)}])))

    :uf/unhandled-ax))
