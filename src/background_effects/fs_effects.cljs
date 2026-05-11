(ns background-effects.fs-effects
  (:require [manifest-parser :as manifest-parser]
            [script-utils :as script-utils]
            [bg-fs-dispatch :as fs-dispatch]))

(defn- generate-script-id
  "Generate a unique script ID using crypto.randomUUID or fallback."
  []
  (let [crypto (.-crypto js/globalThis)]
    (if (and crypto (.-randomUUID crypto))
      (str "script-" (.randomUUID crypto))
      (str "script-" (.now js/Date) "-" (.random js/Math)))))

(defn- build-script-map
  "Build a script map from parsed manifest and raw data."
  [raw-name manifest-data raw-data]
  (let [{:keys [auto-run-match inject run-at]} manifest-data
        {:keys [code enabled force? bulk-id bulk-index bulk-count script-source]} raw-data
        run-at (script-utils/normalize-run-at run-at)]
    (cond-> {:script/id (generate-script-id)
             :script/name raw-name
             :script/code code
             :script/match (script-utils/normalize-match-patterns auto-run-match)
             :script/inject (or inject [])
             :script/enabled enabled
             :script/run-at run-at
             :script/force? force?}
      (some? script-source) (assoc :script/source script-source)
      (some? bulk-id) (assoc :script/bulk-id bulk-id)
      (some? bulk-index) (assoc :script/bulk-index bulk-index)
      (some? bulk-count) (assoc :script/bulk-count bulk-count))))

(defn- parse-and-save-script!
  "Parse manifest from code and save the script via FS dispatch."
  [send-response raw-data]
  (try
    (let [manifest (manifest-parser/extract-manifest (:code raw-data))
          raw-name (or (:raw-script-name manifest) (:script-name manifest))
          name-error (script-utils/validate-script-name raw-name)]
      (cond
        (nil? raw-name)
        (send-response #js {:success false :error "Missing :epupp/script-name in manifest"})

        name-error
        (send-response #js {:success false :error name-error})

        :else
        (let [script (build-script-map raw-name manifest raw-data)]
          (fs-dispatch/dispatch-fs-action! send-response [:fs/ax.save-script script]))))
    (catch :default err
      (send-response #js {:success false :error (str "Parse error: " (.-message err))}))))

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
    (let [[send-response raw-data] args]
      (parse-and-save-script! send-response raw-data))

    :fs/fx.dispatch-action
    (let [[send-response action] args]
      (fs-dispatch/dispatch-fs-action! send-response action))))
