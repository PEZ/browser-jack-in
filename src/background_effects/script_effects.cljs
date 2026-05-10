(ns background-effects.script-effects
  (:require [storage :as storage]
            [background-utils :as bg-utils]
            [dep-resolver :as dep-resolver]
            [bg-inject :as bg-inject]))

(defn ^:async perform-effect! [dispatch! effect args]
  (case effect
    :script/fx.evaluate
    (let [[tab-id script icon-state ext-dep-cache] args]
      (when (= bg-utils/sponsor-script-id (:script/id script))
        (dispatch! [[:sponsor/ax.set-pending tab-id]]))
      (try
        (let [all-scripts (storage/get-scripts)
              plan (dep-resolver/resolve-execution-plan [script] all-scripts ext-dep-cache)
              errors (:plan/errors plan)]
          (when (seq errors)
            (dispatch! [[:banner/ax.broadcast-resolution-errors errors]
                        [:runtime/ax.set-tab-errors tab-id errors]]))
          (js-await (bg-inject/ensure-scittle! dispatch! tab-id icon-state))
          (js-await (bg-inject/execute-plan! tab-id plan))
          {:success true})
        (catch :default err
          {:success false :error (.-message err)})))))
