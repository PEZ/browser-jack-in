(ns background-actions.nav-actions
  (:require [background-utils :as bg-utils]))

(defn- handle-decide-connection [state args]
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
                          [[:nav/fx.process-navigation tab-id url icon-state]]))}))

(defn- handle-navigation [state args]
  (let [[tab-id url] args
        history (:connected-tabs/history state)]
    {:uf/db (update state :runtime/errors dissoc tab-id)
     :uf/fxs [[:icon/fx.update-icon-disconnected tab-id]
              [:runtime/fx.broadcast-tab-status tab-id {}]
              [:uf/await :nav/fx.gather-auto-connect-context tab-id url history]]
     :uf/dxs [[:nav/ax.decide-connection :uf/prev-result]]}))

(defn- handle-tab-removed [state args]
  (let [[tab-id] args
        connections (or (:ws/connections state) {})
        has-ws? (some? (get connections tab-id))]
    {:uf/db (update state :runtime/errors dissoc tab-id)
     :uf/fxs (when has-ws?
               [[:ws/fx.handle-close connections tab-id]])
     :uf/dxs [[:icon/ax.clear tab-id]
              [:history/ax.forget tab-id]]}))

(defn- handle-before-navigate [state args]
  (let [[tab-id] args
        connections (or (:ws/connections state) {})
        has-ws? (some? (get connections tab-id))]
    (when has-ws?
      {:uf/fxs [[:ws/fx.handle-close connections tab-id]]})))

(defn- handle-tab-visible [state args]
  (let [[tab-id] args
        connections (or (:ws/connections state) {})
        has-ws? (some? (get connections tab-id))]
    (when-not has-ws?
      (let [history (:connected-tabs/history state)]
        {:uf/fxs [[:uf/await :visibility/fx.gather-reconnect-context tab-id history]]
         :uf/dxs [[:visibility/ax.decide-reconnect :uf/prev-result]]}))))

(defn- handle-decide-reconnect [state args]
  (let [[context] args
        {:visibility/keys [tab-id auto-connect-level history-port saved-port]} context
        icon-state (get-in state [:icon/states tab-id] :disconnected)
        {:keys [decision port]} (bg-utils/decide-connection
                                 {:trigger "visibility"
                                  :auto-connect-level auto-connect-level
                                  :history-port history-port
                                  :saved-port saved-port})]
    (when (not= decision "none")
      {:uf/fxs [[:uf/await :nav/fx.connect tab-id port icon-state]]})))

(defn handle-action [state _uf-data [action & args]]
  (case action
    :nav/ax.decide-connection (handle-decide-connection state args)
    :nav/ax.handle-navigation (handle-navigation state args)
    :nav/ax.handle-before-navigate (handle-before-navigate state args)
    :tab/ax.handle-removed (handle-tab-removed state args)
    :visibility/ax.handle-tab-visible (handle-tab-visible state args)
    :visibility/ax.decide-reconnect (handle-decide-reconnect state args)))
