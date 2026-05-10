(ns background-effects.runtime-effects)

(defn perform-effect! [dispatch! effect args]
  (case effect
    :runtime/fx.broadcast-tab-status
    (let [[tab-id errors] args]
      (js/chrome.runtime.sendMessage
       #js {:type "runtime-status"
            :tab-id tab-id
            :errors (clj->js errors)}
       (fn [_] (when js/chrome.runtime.lastError nil))))

    :runtime/fx.set-tab-errors
    (let [[tab-id errors] args]
      (dispatch! [[:runtime/ax.set-tab-errors tab-id errors]]))))
