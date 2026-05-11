(ns panel.effects
  "Side-effecting functions for the DevTools panel.
   Each function receives dispatch as first parameter."
  (:require [clojure.string :as str]
            [log :as log]
            [manifest-parser :as mp]
            [script-utils :as script-utils]
            [test-logger :as test-logger]))

;; ============================================================
;; Panel State Persistence (per hostname)
;; ============================================================

(def ^:private panel-state-prefix "panelState:")

(defn- panel-state-key [hostname]
  (str panel-state-prefix hostname))

(defn get-inspected-hostname
  "Get the hostname of the inspected page."
  [callback]
  (js/chrome.devtools.inspectedWindow.eval
   "window.location.hostname"
   (fn [hostname _exception]
     (callback (or hostname "unknown")))))

(defn save-panel-state!
  "Persist editor state per hostname. Receives state snapshot to ensure consistency."
  [state]
  (when-let [hostname (:panel/current-hostname state)]
    (let [{:panel/keys [code]} state
          key (panel-state-key hostname)
          state-to-save #js {:code code}]
      (js/chrome.storage.local.set
       (js-obj key state-to-save)
       (fn []
         (when js/chrome.runtime.lastError
           (log/error "Panel" "Failed to save state:"
                      (.-message js/chrome.runtime.lastError))))))))

(defn restore-panel-state!
  "Restore persisted panel state for current hostname."
  [dispatch callback]
  (get-inspected-hostname
   (fn [hostname]
     (let [key (panel-state-key hostname)]
       (js/chrome.storage.local.get
        #js [key]
        (fn [result]
          (let [saved (aget result key)
                code (when saved (.-code saved))]
            (dispatch [[:editor/ax.initialize-editor
                        {:code code
                         :hostname hostname}]])
            (when callback (callback)))))))))

;; ============================================================
;; Evaluation
;; ============================================================

(defn eval-in-page!
  "Evaluate code in the inspected page context."
  [code callback]
  (let [wrapper (str "(() => {"
                     "  if (!window.scittle || !window.scittle.core) {"
                     "    return {error: 'Scittle not loaded. Connect REPL first via popup.'};"
                     "  }"
                     "  try {"
                     "    const result = scittle.core.eval_string(" (js/JSON.stringify code) ");"
                     "    return {success: true, result: String(result)};"
                     "  } catch(e) {"
                     "    return {error: e.message};"
                     "  }"
                     "})()")]
    (js/chrome.devtools.inspectedWindow.eval
     wrapper
     (fn [result exception-info]
       (if exception-info
         (callback {:error (or (.-value exception-info) "Evaluation failed")})
         (if (and result (.-error result))
           (callback {:error (.-error result)})
           (callback {:result (when result (.-result result))})))))))

;; ============================================================
;; Scittle Status & Injection
;; ============================================================

(defn check-scittle-status!
  "Check if Scittle is loaded in the inspected page."
  [callback]
  (js/chrome.devtools.inspectedWindow.eval
   "(function() {
      if (window.scittle && window.scittle.core) return {status: 'loaded'};
      return {status: 'not-loaded'};
    })()"
   (fn [result _exception]
     (callback (if result (.-status result) "not-loaded")))))

(defn ensure-scittle!
  "Request background worker to inject Scittle."
  [callback]
  (let [tab-id js/chrome.devtools.inspectedWindow.tabId]
    (js/chrome.runtime.sendMessage
     #js {:type "ensure-scittle" :tabId tab-id}
     (fn [response]
       (if (and response (.-success response))
         (callback nil)
         (callback {:error (or (and response (.-error response)) "Failed to inject Scittle")}))))))

(defn- inject-libs-error
  [response]
  (or (when js/chrome.runtime.lastError
        (.-message js/chrome.runtime.lastError))
      (and response (.-error response))
      (when-let [errors (and response (.-errors response))]
        (aget errors 0))
      "Failed to inject libs"))

(defn- request-lib-injection!
  [libs on-success on-failure]
  (if (seq libs)
    (js/chrome.runtime.sendMessage
     #js {:type "inject-libs"
          :tabId js/chrome.devtools.inspectedWindow.tabId
          :libs (clj->js libs)}
     (fn [response]
       (if (and (not js/chrome.runtime.lastError)
                response
                (.-success response))
         (on-success)
         (on-failure {:error (inject-libs-error response)}))))
    (on-success)))

;; ============================================================
;; Effect Router
;; ============================================================

(defn- handle-eval-in-page! [dispatch code libs]
  (request-lib-injection!
   libs
   (fn []
     (eval-in-page!
      code
      (fn [result]
        (dispatch [[:editor/ax.handle-eval-result result]]))))
   (fn [err]
     (dispatch [[:editor/ax.handle-eval-result err]]))))

(defn- handle-inject-and-eval! [dispatch code libs]
  (request-lib-injection!
   libs
   (fn []
     (ensure-scittle!
      (fn [err]
        (if err
          (dispatch [[:editor/ax.update-scittle-status "error"]
                     [:editor/ax.handle-eval-result err]])
          (dispatch [[:editor/ax.update-scittle-status "loaded"]
                     [:editor/ax.do-eval code]])))))
   (fn [err]
     (dispatch [[:editor/ax.update-scittle-status "error"]
                [:editor/ax.handle-eval-result err]]))))

(defn- handle-save-script! [dispatch script normalized-name action-text]
  (js/chrome.runtime.sendMessage
   #js {:type "panel-save-script"
        :script (script-utils/script->panel-js script)}
   (fn [response]
     (let [error (or (when js/chrome.runtime.lastError
                       (.-message js/chrome.runtime.lastError))
                     (when response (.-error response)))
           unchanged? (and response (.-unchanged response))]
       (dispatch [[:editor/ax.handle-save-response
                   {:success (and (not js/chrome.runtime.lastError)
                                  response
                                  (.-success response))
                    :error error
                    :name normalized-name
                    :action-text action-text
                    :unchanged unchanged?
                    :is-update (when response (.-isUpdate response))}]])))))

(defn- handle-rename-script! [dispatch from-name to-name]
  (js/chrome.runtime.sendMessage
   #js {:type "panel-rename-script"
        :from from-name
        :to to-name}
   (fn [response]
     (dispatch [[:editor/ax.handle-rename-response
                 {:success (and response (.-success response))
                  :error (when response (.-error response))
                  :from-name from-name
                  :to-name to-name}]]))))

(defn- handle-reload-script! [dispatch script-name]
  (js/chrome.storage.local.get
   #js ["scripts"]
   (fn [result]
     (when-let [scripts-raw (.-scripts result)]
       (let [scripts (script-utils/parse-scripts scripts-raw {:extract-manifest mp/extract-manifest})
             script (some #(when (= (:script/name %) script-name) %) scripts)]
         (when script
           (dispatch [[:editor/ax.load-script-for-editing
                       (:script/id script)
                       (:script/name script)
                       (str/join "\n" (:script/match script))
                       (:script/code script)
                       (:script/description script)]])))))))

(defn- handle-load-scripts-list! [dispatch]
  (js/chrome.storage.local.get
   #js ["scripts"]
   (fn [result]
     (let [scripts-raw (.-scripts result)
           scripts (if scripts-raw
                     (script-utils/parse-scripts scripts-raw {:extract-manifest mp/extract-manifest})
                     [])]
       (dispatch [[:editor/ax.update-scripts-list scripts]])))))

(defn- handle-load-connections! [dispatch]
  (let [inspected-tab-id js/chrome.devtools.inspectedWindow.tabId]
    (js/chrome.runtime.sendMessage
     #js {:type "get-connections"}
     (fn [response]
       (when (and response (.-success response))
         (let [connections (.-connections response)
               tab-id-str (str inspected-tab-id)
               connected? (boolean (some #(= tab-id-str (str (:tab-id %))) connections))]
           (dispatch [[:editor/ax.set-tab-connected connected?]])))))))

(defn- handle-check-editing-script! [dispatch]
  (js/chrome.storage.local.get
   #js ["editingScript"]
   (fn [result]
     (when-let [script (.-editingScript result)]
       (dispatch [[:editor/ax.load-script-for-editing
                   (.-id script)
                   (.-name script)
                   (.-match script)
                   (.-code script)
                   (.-description script)]])
       (js/chrome.storage.local.remove "editingScript")))))

(defn- handle-load-sponsor-status! [dispatch]
  (js/chrome.storage.local.get
   #js ["sponsorStatus" "sponsorCheckedAt"]
   (fn [result]
     (let [status (boolean (.-sponsorStatus result))
           checked-at (.-sponsorCheckedAt result)]
       (dispatch [[:db/ax.assoc
                   :sponsor/status status
                   :sponsor/checked-at checked-at]])))))

(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :editor/fx.restore-panel-state
    (let [[callback] args]
      (test-logger/log-event! "PANEL_RESTORE_START" {})
      (restore-panel-state! dispatch callback))

    :editor/fx.eval-in-page
    (let [[code libs] args]
      (handle-eval-in-page! dispatch code libs))

    :editor/fx.check-scittle
    (check-scittle-status!
     (fn [status]
       (dispatch [[:editor/ax.update-scittle-status status]])))

    :editor/fx.inject-and-eval
    (let [[code libs] args]
      (handle-inject-and-eval! dispatch code libs))

    :editor/fx.save-script
    (let [[script normalized-name action-text] args]
      (handle-save-script! dispatch script normalized-name action-text))

    :editor/fx.rename-script
    (let [[from-name to-name] args]
      (handle-rename-script! dispatch from-name to-name))

    :editor/fx.clear-persisted-state
    (let [[hostname] args]
      (when hostname
        (js/chrome.storage.local.remove (panel-state-key hostname))))

    :editor/fx.use-current-url
    (let [[action] args]
      (js/chrome.devtools.inspectedWindow.eval
       "window.location.href"
       (fn [url _exception]
         (when-let [pattern (script-utils/url-to-match-pattern url)]
           (dispatch [(conj action pattern)])))))

    :editor/fx.check-editing-script
    (handle-check-editing-script! dispatch)

    :editor/fx.reload-script-from-storage
    (let [[script-name] args]
      (handle-reload-script! dispatch script-name))

    :editor/fx.load-scripts-list
    (handle-load-scripts-list! dispatch)

    :editor/fx.load-connections
    (handle-load-connections! dispatch)

    :editor/fx.check-sponsor
    (js/chrome.storage.local.get
     #js ["sponsor/sponsored-username"]
     (fn [result]
       (let [username (or (aget result "sponsor/sponsored-username") "PEZ")]
         (js/chrome.tabs.create #js {:url (str "https://github.com/sponsors/" username) :active true}))))

    :editor/fx.load-sponsor-status
    (handle-load-sponsor-status! dispatch)

    :panel/fx.log-system-banner
    (let [[message bulk-names] args]
      (if (seq bulk-names)
        (js/console.info "[Epupp:FS]" message (clj->js {:files bulk-names}))
        (js/console.info "[Epupp:FS]" message)))

    :uf/unhandled-fx))
