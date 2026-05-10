(ns popup-utils
  "Pure utility functions for the popup UI.
   No browser dependencies - takes config/state as arguments.")

(defn generate-server-cmd
  "Generate the bb browser-nrepl server command.
   Takes deps-string (from config) and port settings."
  [{:keys [deps-string nrepl-port ws-port]}]
  (str "bb -Sdeps '" deps-string "' "
       "-e '(require (quote [sci.nrepl.browser-server :as server])) "
       "(server/start! {:nrepl-port " nrepl-port " :websocket-port " ws-port "}) "
       "@(promise)'"))

;; ============================================================
;; Script list transformations
;; ============================================================

(defn toggle-script-in-list
  "Toggle enabled state for a script. Always-enabled scripts cannot be toggled."
  [scripts script-id]
  (mapv (fn [s]
          (if (and (= (:script/id s) script-id)
                   (not (:script/always-enabled? s)))
            (assoc s :script/enabled (not (:script/enabled s)))
            s))
        scripts))

(defn remove-script-from-list
  "Remove script from list by id."
  [scripts script-id]
  (filterv #(not= (:script/id %) script-id) scripts))

;; ============================================================
;; Script sorting for display
;; ============================================================

(defn sort-scripts-for-display
  "Sort scripts for UI display: user scripts alphabetically first,
   then built-in scripts alphabetically.
   Uses script name for alphabetic ordering (case-insensitive)."
  [scripts builtin-script?-fn]
  (let [user-scripts (filterv (comp not builtin-script?-fn) scripts)
        builtin-scripts (filterv builtin-script?-fn scripts)
        sort-by-name #(vec (sort-by (fn [s] (.toLowerCase (:script/name s))) %))]
    (concat (sort-by-name user-scripts)
            (sort-by-name builtin-scripts))))

;; ============================================================
;; Tab helpers (browser-dependent)
;; ============================================================

(defn get-active-tab
  "Gets the active tab. In tests, checks for window.__scittle_tamper_test_url
   and window.__scittle_tamper_test_tab_id overrides."
  []
  (js/Promise.
   (fn [resolve]
     (if-let [test-url js/window.__scittle_tamper_test_url]
       (let [test-tab-id (or js/window.__scittle_tamper_test_tab_id -1)]
         (resolve #js {:id test-tab-id :url test-url}))
       (js/chrome.tabs.query
        #js {:active true :currentWindow true}
        (fn [tabs] (resolve (first tabs))))))))

(defn current-tab-connected?
  "Check if current tab is in the connections list"
  [{:repl/keys [connections] :scripts/keys [current-tab-id]}]
  (let [current-tab-id-str (str current-tab-id)]
    (some #(= (:tab-id %) current-tab-id-str) connections)))

(defn parse-domain-ports [saved]
  (when saved
    (let [nrepl (.-nreplPort saved)
          ws (.-wsPort saved)]
      (when (or (some? nrepl) (some? ws))
        (cond-> {}
          (some? nrepl) (assoc :nrepl (str nrepl))
          (some? ws) (assoc :ws (str ws)))))))

(defn default-ports-changed? [changes area]
  (and (= area "local")
       (or (aget changes "defaultNreplPort")
           (aget changes "defaultWsPort"))))

(defn parse-new-defaults [result]
  {:nrepl (str (or (aget result "defaultNreplPort") "3339"))
   :ws (str (or (aget result "defaultWsPort") "3340"))})
