(ns bg-web-installer
  "Web userscript installer scanning and injection.
   Tracks which tabs have been scanned and injects the installer
   script when userscript blocks are detected on whitelisted origins."
  (:require [storage :as storage]
            [background-utils :as bg-utils]
            [log :as log]
            [bg-inject :as bg-inject]
            [dep-resolver :as dep-resolver]
            [permissions :as permissions]))

;; Ephemeral tracking - NOT Uniflow state. Tracks which tabs have had the
;; web installer injected to avoid redundant re-injection.
(def installer-injected-tabs* (atom #{}))

;; Ephemeral tracking - NOT Uniflow state. Tracks scans that have started but
;; have not yet reached the injected-tab mark, preventing overlapping events
;; from entering the installer scan concurrently for the same tab.
(def installer-in-flight-tabs* (atom #{}))

(defn- ^:async delay-ms!
  "Wait for the given number of milliseconds. No-op for zero or negative."
  [ms]
  (when (pos? ms)
    (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve ms))))))

(defn- ^:async scan-with-delay!
  "Wait, then scan for userscript blocks. Returns truthy if found."
  [tab-id delay-ms]
  (js-await (delay-ms! delay-ms))
  (js-await (bg-inject/execute-in-isolated tab-id bg-inject/scan-for-userscripts-fn)))

(defn- ^:async scan-for-userscripts-with-retry!
  "Try scanning for userscript blocks with bounded retry delays.
   Returns true if found, falsy if not."
  [tab-id]
  (loop [remaining bg-utils/installer-scan-delays]
    (when (seq remaining)
      (or (js-await (scan-with-delay! tab-id (first remaining)))
          (recur (rest remaining))))))

(defn- ^:async inject-installer-for-tab!
  "Inject the web userscript installer for a tab that has userscript blocks."
  [dispatch! tab-id]
  (let [installer (storage/get-script-by-name "epupp/web_userscript_installer.cljs")]
    (when (and installer (:script/enabled installer))
      (when (js-await (scan-for-userscripts-with-retry! tab-id))
        (js-await (bg-inject/ensure-scittle! dispatch! tab-id :disconnected))
        (let [all-scripts (storage/get-scripts)
              plan (dep-resolver/resolve-execution-plan [installer] all-scripts
                                                       (storage/get-ext-dep-cache))]
          (js-await (bg-inject/execute-plan! tab-id plan)))
        (swap! installer-injected-tabs* conj tab-id)))))

(defn ^:async maybe-inject-installer!
  "Scan a tab for userscript blocks and inject the installer if found.
   Only scans on whitelisted origins. Skips if already injected on this tab.
   Checks host permission before injection (Firefox treats these as revocable).
   ensure-initialized-fn is passed from background to avoid circular deps."
  [dispatch! ensure-initialized-fn tab-id url]
  (try
    (when (bg-utils/should-scan-for-installer? url
                                               (deref installer-injected-tabs*)
                                               (deref installer-in-flight-tabs*)
                                               tab-id)
      (swap! installer-in-flight-tabs* conj tab-id)
      (try
        (let [has-perm? (js-await (permissions/check-tab-permission tab-id))]
          (if has-perm?
            (do
              (js-await (ensure-initialized-fn dispatch!))
              (js-await (inject-installer-for-tab! dispatch! tab-id)))
            (log/debug "Background" "Installer scan skipped - host permission not granted for tab" tab-id)))
        (finally
          (swap! installer-in-flight-tabs* disj tab-id))))
    (catch :default err
      (log/warn "Background" "Installer scan failed for tab" tab-id ":" (.-message err)))))
