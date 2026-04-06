(ns bg-fs-dispatch
  "Uniflow dispatch for background FS operations.
   Bridges pure action handlers with storage side effects."
  (:require [background-actions :as bg-actions]
            [bg-icon :as bg-icon]
            [storage :as storage]
            [log :as log]))

(defn- perform-fs-effect!
  "Execute FS effects. Called by dispatch-fs-action! after pure handler runs."
  [send-response [effect & args]]
  (log/debug "Background" "perform-fs-effect! START:" effect)
  (let [start (.now js/Date)]
    (case effect
      :storage/fx.persist!
      (storage/persist!)

      :bg/fx.broadcast-system-banner!
      (let [[event-data] args]
        (bg-icon/broadcast-system-banner! event-data))

      :bg/fx.send-response
      (let [[response-data] args]
        (log/debug "Background" "Sending response:" response-data)
        (send-response (clj->js response-data)))

      (log/warn "Background" "Unknown FS effect:" effect))
    (let [elapsed (- (.now js/Date) start)]
      (when (> elapsed 50)
        (log/warn "Background" "SLOW effect" effect "took" elapsed "ms")))))

(defn dispatch-fs-action!
  "Dispatch an FS action through pure handler, then execute effects.
   Mirrors the real Uniflow event loop: update atom first, then run effects.
   When dispatch-fn is provided, deferred actions (:uf/dxs) are dispatched
   through it after effects complete."
  [send-response action & [{:keys [dispatch-fn]}]]
  (log/debug "Background" "dispatch-fs-action! START:" (first action))
  (try
    (let [start (.now js/Date)
          state {:storage/scripts (storage/get-scripts)}
          _ (log/debug "Background" "Got scripts, count:" (count (:storage/scripts state)))
          uf-data {:system/now (.now js/Date)}
          result (bg-actions/handle-action state uf-data action)
          _ (log/debug "Background" "Handler result keys:" (keys result))
          {:uf/keys [db fxs dxs]} result
          _ (log/debug "Background" "Effects to execute:" (count fxs) (mapv first fxs))]
      ;; Update atom before effects (mirrors event_handler/dispatch! pattern)
      (when db
        (swap! storage/!db merge db))
      ;; Execute effects
      (doseq [fx fxs]
        (perform-fs-effect! send-response fx))
      ;; Dispatch deferred actions if dispatch-fn provided
      (when (and dispatch-fn (seq dxs))
        (log/debug "Background" "Dispatching deferred actions:" (count dxs) (mapv first dxs))
        (dispatch-fn dxs))
      (let [elapsed (- (.now js/Date) start)]
        (log/debug "Background" "dispatch-fs-action! DONE:" (first action) "in" elapsed "ms")
        (when (> elapsed 100)
          (log/error "Background" "SLOW dispatch" (first action) "took" elapsed "ms"))))
    (catch :default e
      (log/error "Background" "dispatch-fs-action! ERROR:" e)
      (send-response #js {:success false :error (str "Dispatch error: " (.-message e))}))))
