(ns bg-fs-dispatch
  "Uniflow dispatch for background FS operations.
   Bridges pure action handlers with storage side effects."
  (:require [background-actions :as bg-actions]
            [bg-icon :as bg-icon]
            [storage :as storage]
            [log :as log]))

(defn- ^:async perform-fs-effect!
  "Execute FS effects. Called by dispatch-fs-action! after pure handler runs."
  [send-response [effect & args]]
  (log/debug "Background" "perform-fs-effect! START:" effect)
  (let [start (.now js/Date)
        effect-result
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

          (log/warn "Background" "Unknown FS effect:" effect))]
    (let [elapsed (- (.now js/Date) start)]
      (when (> elapsed 50)
        (log/warn "Background" "SLOW effect" effect "took" elapsed "ms")))
    effect-result))

(defn ^:async dispatch-fs-action!
  "Dispatch an FS action through pure handler, then execute effects.
   Mirrors the real Uniflow event loop: update atom first, then run effects."
  [send-response action]
  (log/debug "Background" "dispatch-fs-action! START:" (first action))
  (try
    (let [start (.now js/Date)
          state {:storage/scripts (storage/get-scripts)}
          _ (log/debug "Background" "Got scripts, count:" (count (:storage/scripts state)))
          uf-data {:system/now (.now js/Date)}
          result (bg-actions/handle-action state uf-data action)
          _ (log/debug "Background" "Handler result keys:" (keys result))
          {:uf/keys [db fxs]} result
          _ (log/debug "Background" "Effects to execute:" (count fxs) (mapv first fxs))]
      ;; Update atom before effects (mirrors event_handler/dispatch! pattern)
      (when db
        (swap! storage/!db merge db))
      ;; Execute effects in order, waiting for each one before continuing
      (-> (reduce (fn [effect-chain fx]
                    (.then effect-chain
                           (fn [_]
                             (perform-fs-effect! send-response fx))))
                  (.resolve js/Promise nil)
                  fxs)
          (.then
           (fn [_]
             (let [elapsed (- (.now js/Date) start)]
               (log/debug "Background" "dispatch-fs-action! DONE:" (first action) "in" elapsed "ms")
               (when (> elapsed 100)
                 (log/error "Background" "SLOW dispatch" (first action) "took" elapsed "ms")))))
          (.catch
           (fn [e]
             (log/error "Background" "dispatch-fs-action! ERROR:" e)
             (send-response #js {:success false
                                 :error (str "Dispatch error: " (.-message e))})))))
    (catch :default e
      (log/error "Background" "dispatch-fs-action! ERROR:" e)
      (send-response #js {:success false :error (str "Dispatch error: " (.-message e))}))))
