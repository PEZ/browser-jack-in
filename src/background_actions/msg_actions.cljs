(ns background-actions.msg-actions
  (:require [background-actions.repl-fs-actions :as repl-fs-actions]
            [background-utils :as bg-utils]
            [dep-resolver :as dep-resolver]
            [ext-dep :as ext-dep]
            [script-utils :as script-utils]))

;; --- Dependency resolution helpers ---

(defn- deps-only-plan [plan]
  (assoc plan :plan/steps
         (filterv #(not= :root-script (:step/type %))
                  (:plan/steps plan))))

(defn- resolution-error-dxs [errors]
  (when (seq errors)
    (vec (cons [:banner/ax.broadcast-resolution-errors errors]
               (map (fn [error] [:msg/ax.log-resolution-error error]) errors)))))

(defn- resolution-error-response [errors]
  (let [messages (mapv :error/message errors)]
    {:success false
     :error (first messages)
     :errors messages}))

(defn- uncached-ext-dep-urls [libs ext-dep-cache]
  (let [passed-cache (or ext-dep-cache {})]
    (->> (ext-dep/extract-ext-dep-urls (or libs []))
         distinct
         (remove #(contains? passed-cache %))
         vec)))

(defn- dedupe-fetch-cache-miss-errors [fetch-errors plan-errors]
  (let [failed-urls (->> fetch-errors
                         (filter #(= :ext-dep/fetch-failed (:error/type %)))
                         (keep :error/dep-raw)
                         set)]
    (filterv (fn [error]
               (not (and (= :ext-dep/cache-miss (:error/type error))
                         (contains? failed-urls (:error/dep-raw error)))))
             plan-errors)))

(defn- combined-resolution-errors [fetch-errors plan-errors]
  (into (vec fetch-errors)
        (dedupe-fetch-cache-miss-errors fetch-errors plan-errors)))

(defn- build-dep-fxs [{:keys [base-fxs send-response response errors deps-plan tab-id icon-state]}]
  (cond
    (seq errors)
    (conj base-fxs [:msg/fx.send-response send-response response])

    (seq (:plan/steps deps-plan))
    (into base-fxs [[:uf/await :msg/fx.ensure-scittle-tab tab-id icon-state]
                    [:uf/await :msg/fx.execute-plan tab-id deps-plan]
                    [:msg/fx.send-response send-response response]])

    :else
    (conj base-fxs [:msg/fx.send-response send-response response])))

(defn- manual-dep-ready
  [state {:keys [send-response tab-id synthetic-script all-scripts fetch-result]}]
  (let [icon-state (get-in state [:icon/states tab-id] :disconnected)
        existing-cache (or (:storage/ext-dep-cache state) {})
        fetched-cache (or (:resolved fetch-result) {})
        merged-cache (merge existing-cache fetched-cache)
        plan (dep-resolver/resolve-execution-plan [synthetic-script]
                                                  (or all-scripts [])
                                                  merged-cache)
        errors (combined-resolution-errors (or (:errors fetch-result) [])
                                           (:plan/errors plan))
        deps-plan (deps-only-plan plan)
        response (if (seq errors)
                   (resolution-error-response errors)
                   {:success true})
        base-fxs (cond-> []
                   (seq fetched-cache) (conj [:storage/fx.persist-ext-dep-cache! merged-cache]))
        fxs (build-dep-fxs {:base-fxs base-fxs
                            :send-response send-response
                            :response response
                            :errors errors
                            :deps-plan deps-plan
                            :tab-id tab-id
                            :icon-state icon-state})]
    (cond-> {:uf/fxs fxs}
      (seq fetched-cache) (assoc :uf/db (assoc state :storage/ext-dep-cache merged-cache))
      (seq errors) (assoc :uf/dxs (resolution-error-dxs errors)))))

(defn- manual-dep-fetch-or-ready
  [state {:keys [send-response tab-id libs all-scripts synthetic-script ready-dispatch]}]
  (let [existing-cache (or (:storage/ext-dep-cache state) {})
        uncached (uncached-ext-dep-urls libs existing-cache)]
    (if (seq uncached)
      {:uf/fxs [[:uf/await :ext-dep/fx.fetch-deps uncached existing-cache]]
       :uf/dxs [(conj ready-dispatch :uf/prev-result)]}
      (manual-dep-ready state {:send-response send-response
                               :tab-id tab-id
                               :synthetic-script synthetic-script
                               :all-scripts all-scripts}))))

;; --- Individual action handlers ---

(defn- handle-connect-tab [state args]
  (let [[send-response tab-id ws-port] args
        icon-state (get-in state [:icon/states tab-id] :disconnected)]
    {:uf/fxs [[:uf/await :repl/fx.connect-tab tab-id ws-port icon-state]
              [:msg/fx.send-response send-response :uf/prev-result]]}))

(defn- handle-check-status [_state args]
  (let [[send-response tab-id] args]
    {:uf/fxs [[:uf/await :page/fx.check-status tab-id]
              [:msg/fx.send-response send-response :uf/prev-result]]}))

(defn- handle-ensure-scittle [state args]
  (let [[send-response tab-id] args
        icon-state (get-in state [:icon/states tab-id] :disconnected)]
    {:uf/fxs [[:msg/fx.ensure-scittle send-response tab-id icon-state]]}))

(defn- handle-ensure-scittle-result [_state args]
  (let [[send-response {:keys [ok? error]}] args
        response (cond-> {:success (boolean ok?)}
                   error (assoc :error error))]
    {:uf/fxs [[:msg/fx.send-response send-response response]]}))

(defn- handle-evaluate-script [state args]
  (let [[send-response tab-id code libs script-id] args
        icon-state (get-in state [:icon/states tab-id] :disconnected)
        ext-dep-cache (or (:storage/ext-dep-cache state) {})
        script (cond-> {:script/id script-id
                        :script/name "popup-eval"
                        :script/code code}
                 libs (assoc :script/inject libs))]
    {:uf/fxs [[:uf/await :script/fx.evaluate tab-id script icon-state ext-dep-cache]
              [:msg/fx.send-response send-response :uf/prev-result]]}))

(defn- handle-e2e-get-storage [_state args]
  (let [[send-response key] args]
    (if key
      {:uf/fxs [[:uf/await :storage/fx.get-local-storage key]
                [:msg/fx.send-response send-response :uf/prev-result]]}
      {:uf/fxs [[:msg/fx.send-response send-response {:success false :error "Missing key"}]]})))

(defn- handle-e2e-set-storage [_state args]
  (let [[send-response key value] args]
    (if key
      {:uf/fxs [[:uf/await :storage/fx.set-local-storage key value]
                [:msg/fx.send-response send-response :uf/prev-result]]}
      {:uf/fxs [[:msg/fx.send-response send-response {:success false :error "Missing key"}]]})))

(defn- handle-e2e-find-tab-id [_state args]
  (let [[send-response url-pattern] args]
    (if url-pattern
      {:uf/fxs [[:uf/await :tabs/fx.find-by-url-pattern url-pattern]
                [:msg/fx.send-response send-response :uf/prev-result]]}
      {:uf/fxs [[:msg/fx.send-response send-response {:success false :error "Missing urlPattern"}]]})))

(defn- handle-list-scripts-result [_state args]
  (let [[send-response {:keys [include-hidden? scripts]}] args
        visible-scripts (script-utils/filter-visible-scripts scripts include-hidden?)
        public-scripts (mapv repl-fs-actions/script->base-info visible-scripts)]
    {:uf/fxs [[:msg/fx.send-response send-response {:success true :scripts public-scripts}]]}))

(defn- handle-get-script-result [_state args]
  (let [[send-response {:keys [script-name script]}] args
        response (if script
                   {:success true :code (:script/code script)}
                   {:success false :error (str "Script not found: " script-name)})]
    {:uf/fxs [[:msg/fx.send-response send-response response]]}))

(defn- build-synthetic-script [script-id libs]
  {:script/id script-id
   :script/name script-id
   :script/code ""
   :script/inject libs})

(defn- dep-fetch-or-ready
  [state {:keys [send-response tab-id libs all-scripts script-id ready-action passthrough-arg]}]
  (let [scripts (or all-scripts [])
        synthetic-script (build-synthetic-script script-id libs)]
    (manual-dep-fetch-or-ready
     state
     {:send-response send-response
      :tab-id tab-id
      :libs libs
      :all-scripts scripts
      :synthetic-script synthetic-script
      :ready-dispatch [ready-action send-response tab-id passthrough-arg scripts]})))

(defn- dep-ready
  [state {:keys [send-response tab-id libs all-scripts script-id fetch-result]}]
  (let [synthetic-script (build-synthetic-script script-id libs)]
    (manual-dep-ready state {:send-response send-response
                             :tab-id tab-id
                             :synthetic-script synthetic-script
                             :all-scripts (or all-scripts [])
                             :fetch-result fetch-result})))

(defn- extract-manifest-libs [manifest]
  (or (when manifest (vec (aget manifest "inject"))) []))

(defn- handle-inject-libs [state args]
  (let [[send-response tab-id libs all-scripts] args
        requested-libs (or libs [])]
    (dep-fetch-or-ready state {:send-response send-response :tab-id tab-id
                               :libs requested-libs :all-scripts all-scripts
                               :script-id "panel-inject"
                               :ready-action :msg/ax.inject-libs-ready
                               :passthrough-arg requested-libs})))

(defn- handle-inject-libs-ready [state args]
  (let [[send-response tab-id libs all-scripts fetch-result] args]
    (dep-ready state {:send-response send-response :tab-id tab-id
                      :libs (or libs []) :all-scripts all-scripts
                      :script-id "panel-inject" :fetch-result fetch-result})))

(defn- handle-load-manifest [state args]
  (let [[send-response tab-id manifest all-scripts] args]
    (dep-fetch-or-ready state {:send-response send-response :tab-id tab-id
                               :libs (extract-manifest-libs manifest)
                               :all-scripts all-scripts
                               :script-id "repl-manifest"
                               :ready-action :msg/ax.load-manifest-ready
                               :passthrough-arg manifest})))

(defn- handle-load-manifest-ready [state args]
  (let [[send-response tab-id manifest all-scripts fetch-result] args]
    (dep-ready state {:send-response send-response :tab-id tab-id
                      :libs (extract-manifest-libs manifest) :all-scripts all-scripts
                      :script-id "repl-manifest" :fetch-result fetch-result})))

(defn- handle-get-connections [state args]
  (let [[send-response] args
        connections (:ws/connections state)]
    {:uf/fxs [[:msg/fx.get-connections send-response connections]]}))

(defn- handle-list-scripts [_state args]
  (let [[send-response include-hidden?] args]
    {:uf/fxs [[:msg/fx.list-scripts send-response include-hidden?]]}))

(defn- handle-get-script [_state args]
  (let [[send-response script-name] args]
    {:uf/fxs [[:msg/fx.get-script send-response script-name]]}))

(defn- handle-e2e-get-test-events [_state args]
  (let [[send-response] args]
    {:uf/fxs [[:msg/fx.e2e-get-test-events send-response]]}))

(defn- handle-permission-granted [state args]
  (let [[tab-id] args
        icon-state (get-in state [:icon/states tab-id] :disconnected)]
    {:uf/fxs [[:msg/fx.handle-permission-granted tab-id icon-state]]}))

(defn- handle-e2e-get-icon-display-state [state args]
  (let [[send-response tab-id] args
        display-state (bg-utils/compute-display-icon-state (:icon/states state) tab-id)]
    {:uf/fxs [[:msg/fx.send-response send-response {:success true :state display-state}]]}))

(defn- handle-log-resolution-error [_state args]
  (let [[error-envelope] args]
    {:uf/fxs [[:msg/fx.log-resolution-error error-envelope]]}))

;; --- Action handler map ---

(def ^:private action-handlers
  {:msg/ax.connect-tab handle-connect-tab
   :msg/ax.check-status handle-check-status
   :msg/ax.ensure-scittle handle-ensure-scittle
   :msg/ax.ensure-scittle-result handle-ensure-scittle-result
   :msg/ax.evaluate-script handle-evaluate-script
   :msg/ax.e2e-get-storage handle-e2e-get-storage
   :msg/ax.e2e-set-storage handle-e2e-set-storage
   :msg/ax.e2e-find-tab-id handle-e2e-find-tab-id
   :msg/ax.list-scripts-result handle-list-scripts-result
   :msg/ax.get-script-result handle-get-script-result
   :msg/ax.inject-libs handle-inject-libs
   :msg/ax.inject-libs-ready handle-inject-libs-ready
   :msg/ax.load-manifest handle-load-manifest
   :msg/ax.load-manifest-ready handle-load-manifest-ready
   :msg/ax.get-connections handle-get-connections
   :msg/ax.list-scripts handle-list-scripts
   :msg/ax.get-script handle-get-script
   :msg/ax.e2e-get-test-events handle-e2e-get-test-events
   :msg/ax.handle-permission-granted handle-permission-granted
   :msg/ax.e2e-get-icon-display-state handle-e2e-get-icon-display-state
   :msg/ax.log-resolution-error handle-log-resolution-error})

(defn handle-action [state _uf-data [action & args]]
  (when-let [handler (get action-handlers action)]
    (handler state args)))
