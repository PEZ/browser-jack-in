(ns userscript-loader
  "Early injection loader for userscripts (document-start/document-end).

   Runs as a registered content script in ISOLATED world.
   Reads userscripts from chrome.storage.local, resolves dependency graphs
   using dep-resolver, and injects vendor files, library scripts, and
   consumer scripts in correct order.

   Flow:
   1. Read all scripts from chrome.storage.local
   2. Parse scripts with manifest extraction (to derive :script/inject)
   3. Filter to enabled scripts with early timing matching current URL
   4. Resolve full dependency graph via dep-resolver
   5. Report resolution errors to background (for popup/panel indicators)
   6. Inject Scittle and wait for it to load
   7. Inject vendor files in dependency order
   8. Inject library scripts as &lt;script type='application/x-scittle'&gt;
   9. Inject root scripts as &lt;script type='application/x-scittle'&gt;
   10. Inject trigger-scittle.js to evaluate all scripts

   Note: Runs in ISOLATED world - can access page DOM but not page JS.
   Scittle loads asynchronously; userscripts execute after Scittle is ready."
  (:require [dep-resolver :as dep-resolver]
            [manifest-parser :as manifest-parser]
            [script-utils :as script-utils]))

;; ============================================================
;; Pure helpers (exported for unit testing)
;; ============================================================

(defn pattern->regex
  "Convert a URL match pattern to a RegExp.
   Handles '<all_urls>' and simple * wildcards."
  [pattern]
  (if (= pattern "<all_urls>")
    (js/RegExp. "^https?://.*$")
    (let [escaped (.replace pattern (js/RegExp. "[.+?^${}()|[\\]\\\\]" "g") "\\$&")
          with-wildcards (.replace escaped (js/RegExp. "\\*" "g") ".*")]
      (js/RegExp. (str "^" with-wildcards "$")))))

(defn url-matches-pattern?
  "Check if a URL matches a single pattern string."
  [url pattern]
  (.test (pattern->regex pattern) url))

;; ============================================================
;; Parsed script helpers (work on Clojure maps from parse-scripts)
;; ============================================================

(defn early-timing?
  "Check if a parsed script has early timing (document-start or document-end)."
  [script]
  (let [run-at (or (:script/run-at script) "document-idle")]
    (or (= run-at "document-start")
        (= run-at "document-end"))))

(defn- matches-any-pattern?
  "Check if a URL matches any pattern in a Clojure vector."
  [url patterns]
  (and (seq patterns)
       (boolean (some #(url-matches-pattern? url %) patterns))))

(defn parsed-script-matches-url?
  "Check if a parsed Clojure-map script is enabled, has early timing,
   and matches the given URL."
  [script url]
  (and (:script/enabled script)
       (early-timing? script)
       (matches-any-pattern? url (:script/match script))))

(defn get-matching-early-scripts
  "Filter parsed scripts to those matching a URL for early injection."
  [scripts url]
  (filterv #(parsed-script-matches-url? % url) scripts))

(defn errors->js
  "Convert resolution error envelopes to JS objects for chrome.runtime messaging."
  [errors]
  (clj->js (mapv (fn [err]
                   {:errorType (:error/type err)
                    :scriptName (:error/script-name err)
                    :depRaw (:error/dep-raw err)
                    :depChain (:error/dep-chain err)
                    :message (:error/message err)})
                 errors)))

;; ============================================================
;; Side effects
;; ============================================================

(defn- log-test-event!
  "Log a test event to chrome.storage.local (for E2E assertions).
   No-op when test-mode is not enabled."
  [event data test-mode?]
  (when test-mode?
    (.get js/chrome.storage.local
          #js ["test-events"]
          (fn [result]
            (let [events (or (aget result "test-events") #js [])]
              (.push events #js {:event event
                                 :ts (.now js/Date)
                                 :perf (.now js/performance)
                                 :data data})
              (.set js/chrome.storage.local #js {"test-events" events}))))))

(defn- inject-script!
  "Inject a <script src=...> into the page. Returns the element."
  [src]
  (let [el (.createElement js/document "script")]
    (set! (.-src el) src)
    (.appendChild (or js/document.head js/document.documentElement) el)
    el))

(defn- inject-script-and-wait!
  "Inject a script src tag and return a Promise that resolves on load."
  [src]
  (js/Promise.
   (fn [resolve reject]
     (let [el (.createElement js/document "script")]
       (set! (.-src el) src)
       (set! (.-onload el) (fn [] (resolve el)))
       (set! (.-onerror el) (fn [_e] (reject (js/Error. (str "Failed to load: " src)))))
       (.appendChild (or js/document.head js/document.documentElement) el)))))

(defn- inject-userscript!
  "Inject userscript code as <script type='application/x-scittle'>."
  [id code]
  (let [el (.createElement js/document "script")]
    (set! (.-type el) "application/x-scittle")
    (set! (.-id el) id)
    (set! (.. el -dataset -epuppUserscript) "true")
    (set! (.-textContent el) code)
    (.appendChild (or js/document.head js/document.documentElement) el)
    (js/console.log "[Epupp Loader] Injected userscript:" id)))

;; ============================================================
;; Main loader
;; ============================================================

(defn- report-errors-to-background!
  "Report resolution errors to background via chrome.runtime.sendMessage.
   Best-effort: background may not be ready yet."
  [errors url]
  (try
    (.sendMessage js/chrome.runtime
                  #js {:type "loader-resolution-errors"
                       :errors (errors->js errors)
                       :url url})
    (catch :default _
      nil)))

(defn- report-resolution-errors!
  "Report dependency resolution errors to console and background."
  [errors current-url test-mode?]
  (doseq [err errors]
    (js/console.error "[Epupp Loader] Resolution error:" (:error/message err)))
  (report-errors-to-background! errors current-url)
  (log-test-event! "LOADER_RESOLUTION_ERROR"
                   #js {:count (count errors)
                        :messages (clj->js (mapv :error/message errors))}
                   test-mode?))

(defn- ^:async inject-vendor-files!
  "Inject vendor files sequentially in dependency order."
  [vendor-steps]
  (loop [remaining vendor-steps]
    (when (seq remaining)
      (let [url (.getURL js/chrome.runtime (:step/path (first remaining)))]
        (js-await (inject-script-and-wait! url))
        (recur (rest remaining))))))

(defn- ^:async inject-scittle-and-vendor!
  "Inject Scittle core, disable auto-eval, and inject vendor files sequentially."
  [vendor-steps]
  (let [scittle-url (.getURL js/chrome.runtime "vendor/scittle.js")
        scittle-start (.now js/performance)]
    (js-await (inject-script-and-wait! scittle-url))
    (js/console.log "[Epupp Loader] Scittle loaded in"
                    (.toFixed (- (.now js/performance) scittle-start) 1) "ms")
    (js-await (inject-script-and-wait!
               (.getURL js/chrome.runtime "disable-scittle-auto-eval.js")))
    (when (seq vendor-steps)
      (js/console.log "[Epupp Loader] Injecting" (count vendor-steps) "vendor files")
      (js-await (inject-vendor-files! vendor-steps)))))

(defn- inject-css-file!
  "Inject a CSS file as a <link rel=\"stylesheet\"> tag.
   Resolves epupp:// paths via chrome.runtime.getURL."
  [step]
  (let [url (if (= :epupp (:step/source step))
              (.getURL js/chrome.runtime (:step/path step))
              (:step/url step))
        link (js/document.createElement "link")]
    (set! (.-rel link) "stylesheet")
    (set! (.-href link) url)
    (.appendChild (or js/document.head js/document.documentElement) link)
    (js/console.log "[Epupp Loader] Injected CSS:" url)))

(defn- inject-css-files!
  "Inject all CSS file steps synchronously (no load waiting needed for CSS)."
  [css-steps]
  (doseq [step css-steps]
    (inject-css-file! step)))

(defn- inject-scripts-and-trigger!
  "Inject library/root scripts and trigger Scittle evaluation."
  [script-steps]
  (doseq [step script-steps]
    (inject-userscript! (str "userscript-" (:step/id step))
                        (:step/code step)))
  (let [trigger-url (.getURL js/chrome.runtime "trigger-scittle.js")
        trigger-el (inject-script! trigger-url)]
    (set! (.-onerror trigger-el)
          (fn [e]
            (js/console.error "[Epupp Loader] Failed to load trigger-scittle.js!" e)))))

(defn ^:async load-scripts!
  "Main loader: read storage, parse manifests, resolve dependencies,
   inject CSS + Scittle + vendor files + scripts in correct order."
  [current-url]
  (try
    (let [result (js-await (.get js/chrome.storage.local
                                 #js ["scripts" "test-mode" "extDepCache"]))
          raw-scripts (or (.-scripts result) #js [])
          test-mode? (= (aget result "test-mode") true)
          ext-dep-cache (or (aget result "extDepCache") {})
          all-scripts (script-utils/parse-scripts
                       raw-scripts
                       {:extract-manifest manifest-parser/extract-manifest})]
      (log-test-event! "LOADER_RUN"
                       #js {:url current-url
                            :readyState js/document.readyState}
                       test-mode?)
      (let [matching (get-matching-early-scripts all-scripts current-url)]
        (if (empty? matching)
          (js/console.log "[Epupp Loader] No matching early scripts for" current-url)
          (let [plan (dep-resolver/resolve-execution-plan (vec matching) all-scripts ext-dep-cache)
                errors (:plan/errors plan)
                steps (:plan/steps plan)
                css-steps (filterv #(= :css-file (:step/type %)) steps)
                vendor-steps (filterv #(= :vendor-file (:step/type %)) steps)
                script-steps (filterv #(contains? #{:library-script :root-script :ext-dep-script} (:step/type %))
                                      steps)]
            (when (seq errors)
              (report-resolution-errors! errors current-url test-mode?))
            (js/console.log "[Epupp Loader] Found" (count matching) "matching scripts")
            (when (seq css-steps)
              (js/console.log "[Epupp Loader] Injecting" (count css-steps) "CSS files")
              (inject-css-files! css-steps))
            (js-await (inject-scittle-and-vendor! vendor-steps))
            (inject-scripts-and-trigger! script-steps)))))
    (catch :default err
      (js/console.error "[Epupp Loader] Error:" err))))

;; ============================================================
;; Entry point
;; ============================================================

(when-not js/window.__epuppLoaderInjected
  (set! js/window.__epuppLoaderInjected true)
  (let [current-url js/window.location.href]
    (js/console.log "[Epupp Loader] Running at" js/document.readyState "for" current-url)
    (load-scripts! current-url)))
