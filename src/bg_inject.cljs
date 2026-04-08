(ns bg-inject
  "Scittle injection pipeline for userscripts.
   Handles loading Scittle, content bridge, and script execution."
  (:require [log :as log]
            [test-logger :as test-logger]
            [bg-icon :as bg-icon]
            [permissions :as permissions]))

;; ============================================================
;; Page Context Execution
;; ============================================================

(defn ^:async execute-in-page
  "Execute a function in page context (MAIN world).
   Checks host permission first (Firefox treats these as revocable).
   Returns a promise."
  [tab-id func & args]
  (let [has-perm? (js-await (permissions/check-tab-permission tab-id))]
    (when-not has-perm?
      (throw (js/Error. "Missing host permission for the tab")))
    (js-await
     (js/Promise.
      (fn [resolve reject]
        (js/chrome.scripting.executeScript
         #js {:target #js {:tabId tab-id}
              :world "MAIN"
              :func func
              :args (clj->js (vec args))}
         (fn [results]
           (if js/chrome.runtime.lastError
             (reject (js/Error. (.-message js/chrome.runtime.lastError)))
             (resolve (when (seq results) (.-result (first results))))))))))))

(defn ^:async execute-in-isolated
  "Execute a function in ISOLATED world (content script context).
   Checks host permission first (Firefox treats these as revocable).
   Returns a promise. Safe from page CSP restrictions."
  [tab-id func & args]
  (let [has-perm? (js-await (permissions/check-tab-permission tab-id))]
    (when-not has-perm?
      (throw (js/Error. "Missing host permission for the tab")))
    (js-await
     (js/Promise.
      (fn [resolve reject]
        (js/chrome.scripting.executeScript
         #js {:target #js {:tabId tab-id}
              :world "ISOLATED"
              :func func
              :args (clj->js (vec args))}
         (fn [results]
           (if js/chrome.runtime.lastError
             (reject (js/Error. (.-message js/chrome.runtime.lastError)))
             (resolve (when (seq results) (.-result (first results))))))))))))

(defn ^:async inject-content-script
  "Inject a script file into ISOLATED world.
   Checks host permission first (Firefox treats these as revocable)."
  [tab-id file]
  (let [has-perm? (js-await (permissions/check-tab-permission tab-id))]
    (when-not has-perm?
      (throw (js/Error. "Missing host permission for the tab")))
    (js-await
     (js/Promise.
      (fn [resolve reject]
        (log/debug "Background:Inject" "Injecting" file "into tab" tab-id)
        (js/chrome.scripting.executeScript
         #js {:target #js {:tabId tab-id}
              :files #js [file]}
         (fn [results]
           (log/debug "Background:Inject" "executeScript callback, results:" results "lastError:" js/chrome.runtime.lastError)
           (if js/chrome.runtime.lastError
             (do
               (log/error "Background:Inject" "Error:" (.-message js/chrome.runtime.lastError))
               (reject (js/Error. (.-message js/chrome.runtime.lastError))))
             (do
               (log/debug "Background:Inject" "Success, results:" (js/JSON.stringify results))
               (resolve true))))))))))

;; ============================================================
;; Page-Context Functions (pure JS, no Squint runtime)
;; ============================================================

(def inject-script-fn
  (js* "function(url, isModule) {
    var script = document.createElement('script');
    if (isModule) script.type = 'module';
    if (window.trustedTypes && window.trustedTypes.createPolicy) {
      try {
        var policy = window.trustedTypes.defaultPolicy;
        if (!policy) {
          policy = window.trustedTypes.createPolicy('default', {
            createHTML: function(s) { return s; },
            createScript: function(s) { return s; },
            createScriptURL: function(s) { return s; }
          });
        }
        script.src = policy.createScriptURL(url);
      } catch(e) {
        console.warn('[Epupp] TrustedTypes policy creation failed, using direct assignment:', e.message);
        script.src = url;
      }
    } else {
      script.src = url;
    }
    document.head.appendChild(script);
    return 'ok';
  }"))

(def check-scittle-fn
  (js* "function() {
    return {
      hasScittle: !!(window.scittle && window.scittle.core),
      hasWsBridge: !!window.__browserJackInWSBridge
    };
  }"))

(def check-namespaces-fn
  "JavaScript function to check if specific namespaces are registered in Scittle.
   Takes an array of namespace name strings.
   Returns {available: true} when all are found, or {available: false, missing: [...]}."
  (js* "function(namespaces) {
    if (!window.scittle || !window.scittle.core || !window.scittle.core.eval_string) {
      return {available: false, missing: namespaces};
    }
    var missing = [];
    for (var i = 0; i < namespaces.length; i++) {
      var nsName = namespaces[i];
      if (!/^[a-zA-Z][a-zA-Z0-9._-]*$/.test(nsName)) {
        missing.push(nsName);
        continue;
      }
      try {
        var result = window.scittle.core.eval_string('(boolean (find-ns \\'' + nsName + '))');
        if (!result) missing.push(nsName);
      } catch(e) {
        missing.push(nsName);
      }
    }
    return {available: missing.length === 0, missing: missing};
  }"))

(def trigger-scittle-fn
  "JavaScript function to trigger Scittle evaluation of script tags.
   Ensures a broad TrustedTypes default policy before evaluation.
   Called directly via chrome.scripting.executeScript."
  (js* "function() {
    if (window.trustedTypes && window.trustedTypes.createPolicy && !window.trustedTypes.defaultPolicy) {
      try {
        window.trustedTypes.createPolicy('default', {
          createHTML: function(s) { return s; },
          createScript: function(s) { return s; },
          createScriptURL: function(s) { return s; }
        });
      } catch(e) {
        console.warn('[Epupp] TrustedTypes default policy creation failed:', e.message);
      }
    }
    if (window.scittle && window.scittle.core && window.scittle.core.eval_script_tags) {
      window.scittle.core.eval_script_tags();
      return true;
    }
    return false;
  }"))

;; ============================================================
;; Polling Utilities
;; ============================================================

(def scan-for-userscripts-fn
  "Scan page DOM for code blocks containing Epupp userscript manifests.
   Checks specific formats first (GitHub, GitLab), then generic <pre> and
   <textarea>. Returns true on first match, false if none found."
  (js* "function() {
    function hasManifest(text) {
      if (!text || text.length < 10) return false;
      var trimmed = text.trimStart();
      if (trimmed.charAt(0) !== '{') return false;
      return /:epupp\\/script-name/.test(trimmed.slice(0, 500));
    }
    // 1. GitHub gist tables
    var tables = document.querySelectorAll('table.js-file-line-container');
    for (var i = 0; i < tables.length; i++) {
      var lines = tables[i].querySelectorAll('td.js-file-line');
      var text = '';
      for (var j = 0; j < lines.length; j++) text += lines[j].textContent + '\\n';
      if (hasManifest(text)) return true;
    }
    // 2. GitHub repo file view
    var repoCode = document.querySelector('.react-code-lines');
    if (repoCode && hasManifest(repoCode.textContent)) return true;
    // 3. GitLab snippets
    var holders = document.querySelectorAll('.file-holder');
    for (var i = 0; i < holders.length; i++) {
      var pre = holders[i].querySelector('pre');
      if (pre && hasManifest(pre.textContent)) return true;
    }
    // 4. Generic <pre>
    var pres = document.querySelectorAll('pre');
    for (var i = 0; i < pres.length; i++) {
      if (hasManifest(pres[i].textContent)) return true;
    }
    // 5. Textareas
    var textareas = document.querySelectorAll('textarea');
    for (var i = 0; i < textareas.length; i++) {
      if (hasManifest(textareas[i].value)) return true;
    }
    return false;
  }"))

(defn poll-until
  "Poll a check function until success or timeout.
   timeout-message: Optional custom message for timeout errors."
  [check-fn success? timeout timeout-message]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)]
       (letfn [(poll []
                 (-> (check-fn)
                     (.then (fn [result]
                              (cond
                                (success? result) (resolve result)
                                (> (- (js/Date.now) start) timeout)
                                (reject (js/Error. (or timeout-message "Timeout")))
                                :else (js/setTimeout poll 100))))
                     (.catch reject)))]
         (poll))))))

;; ============================================================
;; Scittle Loading
;; ============================================================

(defn ^:async ensure-scittle!
  "Ensure Scittle is loaded in the page.
   icon-state: Current icon state for the tab (keyword, e.g. :connected, :disconnected)"
  [dispatch! tab-id icon-state]
  (let [status (js-await (execute-in-page tab-id check-scittle-fn))]
    (when-not (and status (.-hasScittle status))
      (let [scittle-url (js/chrome.runtime.getURL "vendor/scittle.js")]
        (js-await (execute-in-page tab-id inject-script-fn scittle-url false))
        (js-await (poll-until
                   (fn [] (execute-in-page tab-id check-scittle-fn))
                   (fn [r] (and r (.-hasScittle r)))
                   5000
                   "Timeout waiting for Scittle"))
        ;; Update icon to show Scittle is injected (stays disconnected/white)
        ;; Only if not already connected (gold)
        (when (not= :connected icon-state)
          (js-await (bg-icon/update-icon-for-tab! dispatch! tab-id :disconnected)))
        ;; Log test event for E2E tests (after icon update so tests see stable state)
        (js-await (test-logger/log-event! "SCITTLE_LOADED" {:tab-id tab-id}))))
    true))

;; ============================================================
;; Bridge Communication
;; ============================================================

(defn wait-for-bridge-ready
  "Wait for content bridge to be ready by pinging it.
   Returns a promise that resolves when bridge responds."
  [tab-id]
  (js/Promise.
   (fn [resolve reject]
     (let [start (js/Date.now)
           timeout 5000]
       (letfn [(ping []
                 (js/chrome.tabs.sendMessage
                  tab-id
                  #js {:type "bridge-ping"}
                  (fn [response]
                    (cond
                      ;; Success - bridge responded
                      (and response (.-ready response))
                      (do
                        (log/debug "Background:Bridge" "Content bridge ready for tab:" tab-id)
                        (resolve true))

                      ;; Timeout
                      (> (- (js/Date.now) start) timeout)
                      (reject (js/Error. "Timeout waiting for content bridge"))

                      ;; Not ready yet or error - retry
                      :else
                      (js/setTimeout ping 50)))))]
         (ping))))))

(defn send-tab-message
  "Send message to a tab and return a promise."
  [tab-id message]
  (js/Promise.
   (fn [resolve reject]
     (js/chrome.tabs.sendMessage
      tab-id
      (clj->js message)
      (fn [response]
        (if js/chrome.runtime.lastError
          (reject (js/Error. (.-message js/chrome.runtime.lastError)))
          (resolve response)))))))

;; ============================================================
;; Script Injection
;; ============================================================

(defn ^:async inject-libs-sequentially!
  "Inject library files sequentially, awaiting each load.
   Checks each response for errors before continuing.
   Uses loop/recur instead of doseq because doseq doesn't properly
   await js-await calls in Squint."
  [tab-id files]
  (loop [remaining files]
    (when (seq remaining)
      (let [file (first remaining)
            url (js/chrome.runtime.getURL (str "vendor/" file))
            response (js-await (send-tab-message tab-id {:type "inject-script" :url url}))]
        (when (and response (false? (.-success response)))
          (throw (js/Error. (str "Failed to inject library " file ": "
                                 (or (.-error response) "unknown error")))))
        (recur (rest remaining))))))

(defn ^:async execute-plan!
  "Execute a resolved dependency plan on a tab.
   Processes steps in order: vendor files -> library scripts -> root scripts -> trigger.
   The plan comes from dep-resolver/resolve-execution-plan.

   Parameters:
   - tab-id: Chrome tab to inject into
   - plan: resolved execution plan with :plan/steps and :plan/vendor-namespaces"
  [tab-id plan]
  (let [steps (:plan/steps plan)
        vendor-namespaces (:plan/vendor-namespaces plan)
        vendor-steps (filterv #(= :vendor-file (:step/type %)) steps)
        script-steps (filterv #(contains? #{:library-script :root-script :ext-dep-script} (:step/type %))
                              steps)]
    (js-await (test-logger/log-event! "EXECUTE_PLAN_START"
                                      {:tab-id tab-id
                                       :vendor-count (count vendor-steps)
                                       :script-count (count script-steps)}))
    (when (or (seq vendor-steps) (seq script-steps))
      (try
        ;; Inject content bridge and wait for readiness
        (js-await (inject-content-script tab-id "content-bridge.js"))
        (js-await (wait-for-bridge-ready tab-id))
        ;; Clear any old userscript tags (prevents re-execution on bfcache navigation)
        (js-await (send-tab-message tab-id {:type "clear-userscripts"}))
        ;; Inject vendor files sequentially via bridge
        (when (seq vendor-steps)
          (let [vendor-files (mapv (fn [step]
                                     ;; step/path is "vendor/file.js", strip prefix for inject fn
                                     (let [path (:step/path step)]
                                       (subs path 7)))
                                   vendor-steps)]
            (js-await (test-logger/log-event! "INJECTING_LIBS" {:files vendor-files}))
            (js-await (inject-libs-sequentially! tab-id vendor-files))
            (js-await (test-logger/log-event! "LIBS_INJECTED" {:count (count vendor-files)}))
            ;; Verify vendor namespace availability
            (when (seq vendor-namespaces)
              (js-await (poll-until
                         (fn [] (execute-in-page tab-id check-namespaces-fn vendor-namespaces))
                         (fn [r] (and r (.-available r)))
                         5000
                         (str "Timeout waiting for library namespaces: "
                              (.join (clj->js vendor-namespaces) ", "))))
              (js-await (test-logger/log-event! "NAMESPACES_VERIFIED"
                                                {:namespaces vendor-namespaces})))))
        ;; Inject script tags sequentially to preserve dependency order in the DOM.
        ;; Library scripts must appear before root scripts so that eval_script_tags
        ;; evaluates them in the correct namespace-definition order.
        (loop [remaining script-steps]
          (when (seq remaining)
            (let [step (first remaining)]
              (js-await (send-tab-message tab-id {:type "inject-userscript"
                                                  :id (str "userscript-" (:step/id step))
                                                  :code (:step/code step)}))
              (js-await (test-logger/log-event! "SCRIPT_INJECTED"
                                                {:script-id (:step/id step)
                                                 :script-name (:step/name step)
                                                 :step-type (:step/type step)
                                                 :tab-id tab-id}))
              (recur (rest remaining)))))
        ;; Trigger Scittle to evaluate all script tags
        (js-await (execute-in-page tab-id trigger-scittle-fn))
        (js-await (test-logger/log-event! "EXECUTE_PLAN_COMPLETE" {:tab-id tab-id}))
        (catch :default err
          (log/error "Background:Inject" "Plan execution error:" err)
          (js-await (test-logger/log-event! "EXECUTE_PLAN_ERROR"
                                            {:error (.-message err)})))))))
