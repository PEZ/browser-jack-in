(ns popup.actions.banner-actions)

(defn show-system-banner [state uf-data event-type message
                          {:keys [bulk-op? bulk-final? bulk-names favicon category]}]
  (let [banner-id (str "msg-" (:system/now uf-data) "-" (count (:ui/system-banners state)))
        new-banner (cond-> {:id banner-id :type event-type :message message}
                     favicon (assoc :favicon favicon)
                     category (assoc :category category))
        banners (or (:ui/system-banners state) [])
        banners (if category
                  (filterv #(not= (:category %) category) banners)
                  banners)]
    {:uf/db (assoc state :ui/system-banners (conj banners new-banner))
     :uf/fxs [[:popup/fx.log-system-banner message bulk-op? bulk-final? bulk-names]
              [:uf/fx.defer-dispatch [[:banner/ax.clear-system-banner banner-id]] 2000]]}))

(defn clear-system-banner [state banner-id]
  (let [banners (or (:ui/system-banners state) [])
        target-banner (some #(when (= (:id %) banner-id) %) banners)]
    (when target-banner
      (if (:leaving target-banner)
        {:uf/db (assoc state :ui/system-banners (filterv #(not= (:id %) banner-id) banners))}
        {:uf/db (assoc state :ui/system-banners
                       (mapv #(if (= (:id %) banner-id)
                                (assoc % :leaving true)
                                %)
                             banners))
         :uf/fxs [[:uf/fx.defer-dispatch [[:banner/ax.clear-system-banner banner-id]] 250]]}))))

(defn handle-system-banner [state {:keys [event-type operation script-name error unchanged
                                          bulk-id bulk-count bulk-index]}]
  (let [bulk-final? (and (some? bulk-count)
                         (some? bulk-index)
                         (= bulk-index (dec bulk-count)))
        bulk-op? (and (= event-type "success")
                      (some? bulk-count)
                      (or (= operation "save")
                          (= operation "delete")))
        show-banner? (or (= event-type "error")
                         (= event-type "info")
                         (not bulk-op?)
                         bulk-final?)
        banner-msg (cond
                     (= event-type "error")
                     (str "FS sync error: " error)

                     unchanged
                     (str "Script \"" script-name "\" unchanged")

                     (and bulk-op? bulk-final?)
                     (str bulk-count (if (= bulk-count 1) " file " " files ")
                          (if (= operation "delete") "deleted" "saved"))

                     :else
                     (str "Script \"" script-name "\" " operation "d"))
        pre-bulk-names (get-in state [:ui/system-bulk-names bulk-id])
        tracked-bulk-names (if (and bulk-id script-name)
                             ((fnil conj []) pre-bulk-names script-name)
                             pre-bulk-names)
        new-state (cond-> state
                    (some? bulk-id)
                    (assoc-in [:ui/system-bulk-names bulk-id] tracked-bulk-names)
                    (and bulk-id bulk-final?)
                    (update :ui/system-bulk-names dissoc bulk-id))
        dxs (cond-> []
              (and (or (= event-type "error") unchanged)
                   (= operation "save")
                   script-name
                   (not bulk-id))
              (conj [:ui/ax.mark-scripts-modified [script-name]])
              show-banner?
              (conj [:banner/ax.show-system-banner event-type banner-msg
                     {:bulk-op? bulk-op? :bulk-final? bulk-final?
                      :bulk-names tracked-bulk-names}]))]
    (cond-> {}
      (not= state new-state) (assoc :uf/db new-state)
      (seq dxs) (assoc :uf/dxs dxs))))

(defn track-bulk-name [state bulk-id script-name]
  {:uf/db (update-in state [:ui/system-bulk-names bulk-id] (fnil conj []) script-name)})

(defn clear-bulk-names [state bulk-id]
  {:uf/db (update state :ui/system-bulk-names dissoc bulk-id)})

(defn handle-action [state uf-data [action & args]]
  (case action
    :banner/ax.show-system-banner
    (let [[event-type message bulk-info category] args]
      (show-system-banner state uf-data event-type message
                          (cond-> (or bulk-info {})
                            category (assoc :category category))))
    :banner/ax.clear-system-banner (clear-system-banner state (first args))
    :banner/ax.track-bulk-name (track-bulk-name state (first args) (second args))
    :banner/ax.clear-bulk-names (clear-bulk-names state (first args))
    :banner/ax.handle-system-banner (handle-system-banner state (first args))
    :uf/unhandled-ax))
