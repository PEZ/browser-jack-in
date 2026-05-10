(ns background-effects.fs-effects
  (:require [manifest-parser :as manifest-parser]
            [script-utils :as script-utils]
            [bg-fs-dispatch :as fs-dispatch]))

(defn perform-effect! [_dispatch! effect args]
  (case effect
    :fs/fx.broadcast-sync-status!
    (let [[sync-tab-id] args]
      (js/chrome.runtime.sendMessage
       #js {:type "fs-sync-status-changed"
            :fsSyncTabId sync-tab-id}
       (fn [_response]
         (when js/chrome.runtime.lastError nil))))

    :fs/fx.parse-and-save
    (let [[send-response raw-data] args
          {:keys [code enabled force? bulk-id bulk-index bulk-count script-source]} raw-data]
      (try
        (let [{:keys [raw-script-name script-name auto-run-match inject run-at]}
              (manifest-parser/extract-manifest code)
              raw-name (or raw-script-name script-name)
              name-error (script-utils/validate-script-name raw-name)
              run-at (script-utils/normalize-run-at run-at)]
          (cond
            (nil? raw-name)
            (send-response #js {:success false :error "Missing :epupp/script-name in manifest"})

            name-error
            (send-response #js {:success false :error name-error})

            :else
            (let [crypto (.-crypto js/globalThis)
                  script-id (if (and crypto (.-randomUUID crypto))
                              (str "script-" (.randomUUID crypto))
                              (str "script-" (.now js/Date) "-" (.random js/Math)))
                  script (cond-> {:script/id script-id
                                  :script/name raw-name
                                  :script/code code
                                  :script/match (cond
                                                  (nil? auto-run-match) []
                                                  (vector? auto-run-match) auto-run-match
                                                  :else [auto-run-match])
                                  :script/inject (or inject [])
                                  :script/enabled enabled
                                  :script/run-at run-at
                                  :script/force? force?}
                           (some? script-source) (assoc :script/source script-source)
                           (some? bulk-id) (assoc :script/bulk-id bulk-id)
                           (some? bulk-index) (assoc :script/bulk-index bulk-index)
                           (some? bulk-count) (assoc :script/bulk-count bulk-count))]
              (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.save-script script]))))
        (catch :default err
          (send-response #js {:success false :error (str "Parse error: " (.-message err))}))))

    :fs/fx.dispatch-action
    (let [[send-response action] args]
      (fs-dispatch/dispatch-fs-action! send-response action))))
