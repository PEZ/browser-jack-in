(ns popup.actions.script-actions)

(defn toggle-script [state script-id matching-pattern]
  {:uf/fxs [[:popup/fx.toggle-script (:scripts/list state) script-id matching-pattern]]})

(defn delete-script [state script-id]
  (let [updated (filterv (fn [s] (not= (:script/id s) script-id)) (:scripts/list state))]
    {:uf/db (assoc state :scripts/list updated)
     :uf/fxs [[:popup/fx.delete-script updated script-id]]}))

(defn inspect-script [state script-id]
  (let [script (some #(when (= (:script/id %) script-id) %) (:scripts/list state))]
    (when script
      {:uf/fxs [[:popup/fx.inspect-script script]
                [:uf/fx.defer-dispatch [[:popup/ax.show-system-banner "info" "Open the Epupp panel in Developer Tools"]] 0]]})))

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

(defn load-scripts []
  {:uf/fxs [[:popup/fx.load-scripts]]})

(defn load-current-url []
  {:uf/fxs [[:popup/fx.load-current-url]]})

(defn export-scripts []
  {:uf/fxs [[:popup/fx.export-scripts]]})

(defn import-scripts []
  {:uf/fxs [[:popup/fx.trigger-import]]})

(defn handle-import [scripts-data]
  {:uf/fxs [[:popup/fx.import-scripts scripts-data]]})
