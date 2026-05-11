(ns panel.actions.eval-actions
  "Pure action handlers for panel eval lifecycle.")

(defn- eval-with-scittle
  "Build eval action result when Scittle is loaded."
  [state code libs]
  {:uf/db (-> state
              (assoc :panel/evaluating? true)
              (update :panel/results conj {:type :input :text code}))
   :uf/fxs [[:editor/fx.eval-in-page code libs]]})

(defn- eval-inject-first
  "Build eval action result when Scittle needs injection first."
  [state code libs]
  {:uf/db (-> state
              (assoc :panel/evaluating? true)
              (assoc :panel/scittle-status :loading)
              (update :panel/results conj {:type :input :text code}))
   :uf/fxs [[:editor/fx.inject-and-eval code libs]]})

(defn- handle-eval
  "Shared eval logic for both full-script and selection eval."
  [state code]
  (let [scittle-status (:panel/scittle-status state)
        libs (:inject (:panel/manifest-hints state))]
    (cond
      (or (empty? code) (:panel/evaluating? state))
      nil

      (= :loaded scittle-status)
      (eval-with-scittle state code libs)

      :else
      (eval-inject-first state code libs))))

(defn handle-action [state _uf-data [action & args]]
  (case action
    :editor/ax.eval
    (handle-eval state (:panel/code state))

    :editor/ax.eval-selection
    (let [selection (:panel/selection state)
          selection-text (when selection (:text selection))
          code (if (seq selection-text) selection-text (:panel/code state))]
      (handle-eval state code))

    :editor/ax.do-eval
    (let [[code] args]
      {:uf/fxs [[:editor/fx.eval-in-page code nil]]})

    :editor/ax.handle-eval-result
    (let [[result] args]
      {:uf/db (cond-> state
                :always (assoc :panel/evaluating? false)
                (:error result) (update :panel/results conj {:type :error :text (:error result)})
                (not (:error result)) (update :panel/results conj {:type :output :text (:result result)}))})

    :editor/ax.check-scittle
    {:uf/db (assoc state :panel/scittle-status :checking)
     :uf/fxs [[:editor/fx.check-scittle]]}

    :editor/ax.update-scittle-status
    (let [[status] args]
      {:uf/db (assoc state :panel/scittle-status (or status :unknown))})))
