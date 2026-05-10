(ns background-effects.banner-effects
  (:require [bg-icon :as bg-icon]))

(defn perform-effect! [_dispatch! effect args]
  (case effect
    :banner/fx.broadcast-system
    (let [[event] args]
      (bg-icon/broadcast-system-banner! event))))
