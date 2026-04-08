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
