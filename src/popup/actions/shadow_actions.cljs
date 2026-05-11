(ns popup.actions.shadow-actions)

(defn sync-scripts-shadow [state added-items removed-ids]
  (let [shadow (:ui/scripts-shadow state)
        source-list (:scripts/list state)
        source-by-id (into {} (map (fn [s] [(:script/id s) s]) source-list))
        shadow-with-updates (mapv (fn [s]
                                    (let [script-id (get-in s [:item :script/id])]
                                      (cond
                                        (contains? removed-ids script-id)
                                        (assoc s :ui/leaving? true)
                                        (contains? source-by-id script-id)
                                        (assoc s :item (get source-by-id script-id))
                                        :else s)))
                                  shadow)
        new-shadow-items (mapv (fn [item] {:item item :ui/entering? true :ui/leaving? false}) added-items)
        updated-shadow (into shadow-with-updates new-shadow-items)
        added-ids (set (map :script/id added-items))]
    {:uf/db (assoc state :ui/scripts-shadow updated-shadow)
     :uf/fxs [[:uf/fx.defer-dispatch [[:shadow-list/ax.clear-entering-scripts added-ids]] 50]
              [:uf/fx.defer-dispatch [[:shadow-list/ax.remove-leaving-scripts removed-ids]] 250]]}))

(defn clear-entering-scripts [state ids]
  {:uf/db (update state :ui/scripts-shadow
                  (fn [shadow]
                    (mapv (fn [s]
                            (if (contains? ids (get-in s [:item :script/id]))
                              (assoc s :ui/entering? false)
                              s))
                          shadow)))})

(defn remove-leaving-scripts [state ids]
  {:uf/db (update state :ui/scripts-shadow
                  (fn [shadow]
                    (filterv (fn [s] (not (contains? ids (get-in s [:item :script/id])))) shadow)))})

(defn handle-action [state _uf-data [action & args]]
  (case action
    :shadow-list/ax.sync-scripts-shadow
    (let [[{:keys [added-items removed-ids]}] args]
      (sync-scripts-shadow state added-items removed-ids))
    :shadow-list/ax.clear-entering-scripts (clear-entering-scripts state (first args))
    :shadow-list/ax.remove-leaving-scripts (remove-leaving-scripts state (first args))
    :uf/unhandled-ax))
