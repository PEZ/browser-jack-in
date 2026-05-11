(ns background-actions.dep-actions
  (:require [dep-resolver :as dep-resolver]))

(defn- known-runtime-errors [errors-by-tab]
  (->> errors-by-tab
       vals
       (mapcat vals)
       set))

(defn- handle-set-tab-errors [state args]
  (let [[tab-id errors] args
        errors-by-name (into {} (map (fn [e] [(:error/script-name e) e]) errors))]
    {:uf/db (assoc-in state [:runtime/errors tab-id] errors-by-name)
     :uf/fxs [[:runtime/fx.broadcast-tab-status tab-id errors-by-name]]}))

(defn- handle-get-tab-errors [state args]
  (let [[send-response tab-id] args
        tab-errors (get-in state [:runtime/errors tab-id] {})]
    {:uf/fxs [[:msg/fx.send-response send-response {:success true :errors tab-errors}]]}))

(defn- build-re-resolve-fxs [all-tabs new-errors truly-new]
  (cond-> (vec (map (fn [tab-id]
                      [:runtime/fx.set-tab-errors tab-id new-errors])
                    all-tabs))
    (seq truly-new)
    (conj [:banner/fx.broadcast-system {:event-type "error"
                                        :operation "library-resolution"
                                        :error (:error/message (first truly-new))
                                        :errors (mapv :error/message truly-new)}])))

(defn- handle-re-resolve-on-change [state args]
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
            all-known-errors (known-runtime-errors errors-by-tab)
            truly-new (filterv (fn [error]
                                 (not (contains? all-known-errors error)))
                               new-errors)]
        {:uf/fxs (build-re-resolve-fxs all-tabs new-errors truly-new)}))))

(defn- handle-resolve-uncached-urls [state args]
  (let [[ext-urls follow-up-actions] args
        existing-cache (or (:storage/ext-dep-cache state) {})
        uncached (filterv #(not (contains? existing-cache %)) ext-urls)]
    (cond
      (seq uncached)
      {:uf/fxs [[:uf/await :ext-dep/fx.fetch-deps uncached existing-cache]]
       :uf/dxs [[:ext-dep/ax.cache-results :uf/prev-result follow-up-actions]]}

      (seq follow-up-actions)
      {:uf/dxs follow-up-actions})))

(defn- handle-cache-results [state args]
  (let [[fetch-result follow-up-actions] args
        resolved (or (:resolved fetch-result) {})
        errors (or (:errors fetch-result) [])
        existing-cache (or (:storage/ext-dep-cache state) {})
        merged-cache (merge existing-cache resolved)]
    (cond-> {:uf/db (assoc state :storage/ext-dep-cache merged-cache)
             :uf/fxs [[:storage/fx.persist-ext-dep-cache! merged-cache]]}
      (seq follow-up-actions)
      (assoc :uf/dxs follow-up-actions)
      (seq errors)
      (update :uf/fxs conj [:banner/fx.broadcast-system
                             {:event-type "error"
                              :operation "ext-dep-resolution"
                              :error (:error/message (first errors))
                              :errors (mapv :error/message errors)}]))))

(defn handle-action [state _uf-data [action & args]]
  (case action
    :runtime/ax.set-tab-errors (handle-set-tab-errors state args)
    :runtime/ax.get-tab-errors (handle-get-tab-errors state args)
    :runtime/ax.re-resolve-on-change (handle-re-resolve-on-change state args)
    :ext-dep/ax.resolve-uncached-urls (handle-resolve-uncached-urls state args)
    :ext-dep/ax.cache-results (handle-cache-results state args)))
