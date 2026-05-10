(ns background-effects.msg-effects
  (:require [bg-inject :as bg-inject]
            [storage :as storage]
            [background-utils :as bg-utils]
            [test-logger :as test-logger]
            [log :as log]))

(defn ^:async perform-effect! [dispatch! effect args]
  (case effect
    :msg/fx.ensure-scittle
    (let [[send-response tab-id icon-state] args]
      ((^:async fn []
         (try
           (js-await (bg-inject/ensure-scittle! dispatch! tab-id icon-state))
           (dispatch! [[:msg/ax.ensure-scittle-result send-response {:ok? true}]])
           (catch :default err
             (dispatch! [[:msg/ax.ensure-scittle-result send-response {:ok? false
                                                                       :error (.-message err)}]]))))))

    :msg/fx.ensure-scittle-tab
    (let [[tab-id icon-state] args]
      (bg-inject/ensure-scittle! dispatch! tab-id icon-state))

    :msg/fx.execute-plan
    (let [[tab-id plan] args]
      (bg-inject/execute-plan! tab-id plan))

    :msg/fx.list-scripts
    (let [[send-response include-hidden?] args
          scripts (storage/get-scripts)]
      (dispatch! [[:msg/ax.list-scripts-result send-response {:include-hidden? include-hidden?
                                                              :scripts scripts}]]))

    :msg/fx.inject-bridge
    (let [[tab-id] args]
      (bg-inject/inject-content-script tab-id "content-bridge.js"))

    :msg/fx.wait-bridge-ready
    (let [[tab-id] args]
      (bg-inject/wait-for-bridge-ready tab-id))

    :msg/fx.inject-lib-file
    (let [[tab-id file] args]
      (bg-inject/inject-libs-sequentially! tab-id [file]))

    :msg/fx.inject-script-code
    (let [[tab-id script-id code] args]
      (bg-inject/send-tab-message tab-id {:type "inject-userscript"
                                          :id (str "userscript-" script-id)
                                          :code code}))

    :msg/fx.trigger-scittle
    (let [[tab-id] args]
      (bg-inject/execute-in-page tab-id bg-inject/trigger-scittle-fn))

    :msg/fx.log-resolution-error
    (let [[error-envelope] args]
      (log/error "Background:Resolve" (:error/message error-envelope)))

    :msg/fx.send-response
    (let [[send-response response-data] args]
      (send-response (clj->js response-data)))

    :msg/fx.get-script
    (let [[send-response script-name] args
          script (storage/get-script-by-name script-name)]
      (dispatch! [[:msg/ax.get-script-result send-response {:script-name script-name
                                                            :script script}]]))

    :msg/fx.get-connections
    (let [[send-response connections] args
          display-list (bg-utils/connections->display-list connections)]
      (test-logger/log-event! "GET_CONNECTIONS_RESPONSE"
                              {:raw-connection-count (count (keys connections))
                               :display-list-count (count display-list)
                               :connections-keys (vec (keys connections))})
      (send-response (clj->js {:success true
                               :connections display-list})))

    :msg/fx.e2e-get-test-events
    (let [[send-response] args]
      ((^:async fn []
         (let [events (js-await (test-logger/get-test-events))]
           (send-response #js {:success true :events events})))))))
