(ns background-effects.ws-effects
  (:require [bg-ws :as bg-ws]))

(defn ^:async perform-effect! [dispatch! effect args]
  (case effect
    :ws/fx.broadcast-connections-changed!
    (let [[connections] args]
      (bg-ws/broadcast-connections-changed! connections))

    :ws/fx.handle-connect
    (let [[connections tab-id port] args]
      (bg-ws/handle-ws-connect connections dispatch! tab-id port))

    :ws/fx.handle-send
    (let [[connections tab-id data] args]
      (bg-ws/handle-ws-send connections tab-id data))

    :ws/fx.handle-close
    (let [[connections tab-id] args]
      (bg-ws/handle-ws-close connections dispatch! tab-id))))
