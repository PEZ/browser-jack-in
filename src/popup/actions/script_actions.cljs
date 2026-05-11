(ns popup.actions.script-actions)

(defn delete-script [state script-id]
  (let [updated (filterv (fn [s] (not= (:script/id s) script-id)) (:scripts/list state))]
    {:uf/db (assoc state :scripts/list updated)
     :uf/fxs [[:popup/fx.delete-script updated script-id]]}))

(defn inspect-script [state script-id]
  (let [script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
    (when script
      {:uf/fxs [[:popup/fx.inspect-script script]
                [:uf/fx.defer-dispatch [[:banner/ax.show-system-banner "info" "Open the Epupp panel in Developer Tools"]] 0]]})))

(defn evaluate-script [state script-id]
  (let [script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
    (when script
      {:uf/fxs [[:popup/fx.evaluate-script script]]})))

(defn reveal-script [state script-name]
  {:uf/db (-> state
              (assoc-in [:ui/sections-collapsed :matching-scripts] false)
              (assoc-in [:ui/sections-collapsed :other-scripts] false)
              (assoc-in [:ui/sections-collapsed :manual-scripts] false)
              (assoc-in [:ui/sections-collapsed :libraries] false)
              (assoc :ui/reveal-highlight-script-name script-name))
   :uf/fxs [[:popup/fx.reveal-script script-name]
            [:uf/fx.defer-dispatch [[:db/ax.assoc :ui/reveal-highlight-script-name nil]] 2000]]})

(defn handle-action [state _uf-data [action & args]]
  (case action
    :script/ax.load-scripts {:uf/fxs [[:popup/fx.load-scripts]]}
    :script/ax.toggle-script {:uf/fxs [[:popup/fx.toggle-script (:scripts/list state) (first args) (second args)]]}
    :script/ax.delete-script (delete-script state (first args))
    :script/ax.load-current-url {:uf/fxs [[:popup/fx.load-current-url]]}
    :script/ax.inspect-script (inspect-script state (first args))
    :script/ax.evaluate-script (evaluate-script state (first args))
    :script/ax.export-scripts {:uf/fxs [[:popup/fx.export-scripts]]}
    :script/ax.import-scripts {:uf/fxs [[:popup/fx.trigger-import]]}
    :script/ax.handle-import {:uf/fxs [[:popup/fx.import-scripts (first args)]]}
    :script/ax.reveal-script (reveal-script state (first args))
    :uf/unhandled-ax))
