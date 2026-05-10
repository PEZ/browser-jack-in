(ns background-effects.icon-effects
  (:require [bg-icon :as bg-icon]))

(defn ^:async perform-effect! [dispatch! effect args]
  (case effect
    :icon/fx.update-toolbar!
    (let [[tab-id display-state] args]
      (js-await (bg-icon/update-icon-with-state! tab-id display-state)))

    :icon/fx.update-icon-disconnected
    (let [[tab-id] args]
      (bg-icon/update-icon-for-tab! dispatch! tab-id :disconnected))))
