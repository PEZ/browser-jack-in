(ns panel.actions.banner-actions
  "Pure action handlers for panel system banners and bulk tracking.")

(defn- compute-bulk-state
  "Compute whether this is a bulk operation and its final state."
  [{:keys [event-type bulk-count bulk-index operation]}]
  {:bulk-final? (and (some? bulk-count)
                     (some? bulk-index)
                     (= bulk-index (dec bulk-count)))
   :bulk-op? (and (= event-type "success")
                  (some? bulk-count)
                  (or (= operation "save")
                      (= operation "delete")))})

(defn- compute-banner-message
  "Compute the banner message text."
  [{:keys [event-type error unchanged script-name operation bulk-count]} bulk-final?]
  (cond
    (= event-type "error")
    (str "FS sync error: " error)
    unchanged
    (str "Script \"" script-name "\" unchanged")
    (and bulk-final? (some? bulk-count))
    (str bulk-count (if (= bulk-count 1) " file " " files ")
         (if (= operation "delete") "deleted" "saved"))
    :else
    (str "Script \"" script-name "\" " operation "d")))

(defn- affects-current-script?
  "Check if the message affects the currently edited script."
  [state {:keys [event-type script-name from-name]}]
  (let [current-name (:panel/script-name state)
        original-name (:panel/original-name state)
        matches-name? (or (= script-name current-name) (= script-name original-name))
        matches-from? (or (= from-name current-name) (= from-name original-name))]
    (and (or (= event-type "success") (= event-type "info"))
         (or matches-name? matches-from?))))

(defn- should-show-banner?
  "Determine if the banner should be shown."
  [{:keys [event-type]} bulk-op? bulk-final?]
  (or (= event-type "error")
      (= event-type "info")
      (not bulk-op?)
      bulk-final?))

(defn- handle-show-system-banner [state uf-data args]
  (let [[event-type message category] args
        now (or (:system/now uf-data) (.now js/Date))
        banner-id (str "msg-" now "-" (count (:panel/system-banners state)))
        new-banner (cond-> {:id banner-id :type event-type :message message}
                     category (assoc :category category))
        banners (or (:panel/system-banners state) [])
        banners (if category
                  (filterv #(not= (:category %) category) banners)
                  banners)]
    {:uf/db (assoc state :panel/system-banners (conj banners new-banner))
     :uf/fxs [[:uf/fx.defer-dispatch [[:editor/ax.clear-system-banner banner-id]] 2000]]}))

(defn- mark-banner-leaving [banners banner-id]
  (mapv #(if (= (:id %) banner-id) (assoc % :leaving true) %) banners))

(defn- handle-clear-system-banner [state args]
  (let [[banner-id] args
        banners (or (:panel/system-banners state) [])
        target-banner (some #(when (= (:id %) banner-id) %) banners)]
    (when target-banner
      (if (:leaving target-banner)
        {:uf/db (assoc state :panel/system-banners (filterv #(not= (:id %) banner-id) banners))}
        {:uf/db (assoc state :panel/system-banners (mark-banner-leaving banners banner-id))
         :uf/fxs [[:uf/fx.defer-dispatch [[:editor/ax.clear-system-banner banner-id]] 250]]}))))

(defn- compute-tracked-bulk-names [state bulk-id script-name]
  (let [pre-bulk-names (get-in state [:panel/system-bulk-names bulk-id])]
    (if (and bulk-id script-name)
      ((fnil conj []) pre-bulk-names script-name)
      pre-bulk-names)))

(defn- build-banner-dxs
  "Build deferred dispatch actions for system banner handling."
  [{:keys [show-banner? skip-banner? affects-current? event-type banner-msg operation script-name]}]
  (cond-> []
    (and show-banner? (not skip-banner?))
    (conj [:editor/ax.show-system-banner event-type banner-msg])
    (and affects-current? (= operation "delete"))
    (conj [:editor/ax.new-script])
    (and affects-current? (not= operation "delete"))
    (conj [:editor/ax.reload-script-from-storage script-name])))

(defn- handle-system-banner-msg [state args]
  (let [[msg-data] args
        {:keys [event-type operation script-name bulk-id]} msg-data
        {:keys [bulk-final? bulk-op?]} (compute-bulk-state msg-data)
        affects-current? (affects-current-script? state msg-data)
        show-banner? (should-show-banner? msg-data bulk-op? bulk-final?)
        banner-msg (compute-banner-message msg-data bulk-final?)
        skip-banner? (and affects-current? (= operation "save"))
        tracked-bulk-names (compute-tracked-bulk-names state bulk-id script-name)
        new-state (cond-> state
                    (some? bulk-id)
                    (assoc-in [:panel/system-bulk-names bulk-id] tracked-bulk-names)
                    (and bulk-id bulk-final?)
                    (update :panel/system-bulk-names dissoc bulk-id))
        fxs (when (and show-banner? (not skip-banner?))
              (if (and bulk-op? bulk-final? (seq tracked-bulk-names))
                [[:panel/fx.log-system-banner banner-msg tracked-bulk-names]]
                [[:panel/fx.log-system-banner banner-msg nil]]))
        dxs (build-banner-dxs {:show-banner? show-banner? :skip-banner? skip-banner?
                              :affects-current? affects-current? :event-type event-type
                              :banner-msg banner-msg :operation operation
                              :script-name script-name})]
    (cond-> {}
      (not= state new-state) (assoc :uf/db new-state)
      (seq fxs) (assoc :uf/fxs fxs)
      (seq dxs) (assoc :uf/dxs dxs))))

(defn handle-action [state uf-data [action & args]]
  (case action
    :editor/ax.show-system-banner (handle-show-system-banner state uf-data args)
    :editor/ax.clear-system-banner (handle-clear-system-banner state args)
    :editor/ax.track-bulk-name
    (let [[bulk-id script-name] args]
      {:uf/db (update-in state [:panel/system-bulk-names bulk-id] (fnil conj []) script-name)})
    :editor/ax.clear-bulk-names
    (let [[bulk-id] args]
      {:uf/db (update state :panel/system-bulk-names dissoc bulk-id)})
    :panel/ax.handle-system-banner (handle-system-banner-msg state args)))
