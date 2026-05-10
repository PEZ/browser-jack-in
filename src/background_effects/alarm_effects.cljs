(ns background-effects.alarm-effects
  (:require [log :as log]))

(defn perform-effect! [_dispatch! effect args]
  (case effect
    :alarm/fx.start
    (do
      (log/debug "Background:Alarm" "Starting keepalive alarm")
      (js/chrome.alarms.create "ws-keepalive" #js {:periodInMinutes 0.5}))

    :alarm/fx.stop
    (do
      (log/debug "Background:Alarm" "Stopping keepalive alarm")
      (js/chrome.alarms.clear "ws-keepalive"))

    :alarm/fx.log-tick
    (let [[connection-count] args]
      (log/debug "Background:Alarm" "Keepalive tick," connection-count "active connections"))))
