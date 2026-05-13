(ns panel-save-helpers
  (:require ["@playwright/test" :refer [expect]]
            [clojure.string :as string]
            [fixtures :refer [builtin-script-count create-panel-page
                              clear-storage wait-for-panel-ready wait-for-popup-ready
                              wait-for-save-status wait-for-script-count wait-for-edit-hint
                              assert-no-errors!]]))

(defn code-with-manifest
  "Generate test code with epupp manifest metadata.
   Uses the official manifest keys: :epupp/script-name, :epupp/auto-run-match, etc."
  [{:keys [name match description run-at code]
    :or {code "(println \"Test script\")"}}]
  (let [meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\"")))
        meta-block (when (seq meta-parts)
                     (str "{" (string/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

(defn ^:async create-script-via-panel!
  [context ext-id {:keys [code-opts status clear-storage?]}]
  (let [panel (js-await (create-panel-page context ext-id))
        textarea (.locator panel "#code-area")
        save-btn (.locator panel "button.btn-save")]
    (when clear-storage?
      (js-await (clear-storage panel))
      (js-await (.reload panel)))
    (js-await (wait-for-panel-ready panel))
    (js-await (.fill textarea (code-with-manifest code-opts)))
    (js-await (.click save-btn))
    (js-await (wait-for-save-status panel status))
    (js-await (.close panel))))

(defn ^:async inspect-script-from-popup!
  [context ext-id script-name]
  (let [popup (js-await (.newPage context))
        popup-url (str "chrome-extension://" ext-id "/popup.html")]
    (js-await (.goto popup popup-url #js {:timeout 1000}))
    (js-await (wait-for-popup-ready popup))
    (let [script-item (.locator popup (str ".script-item:has-text(\"" script-name "\")"))
          inspect-btn (.locator script-item "button.script-inspect")]
      (js-await (.click inspect-btn))
      (js-await (wait-for-edit-hint popup)))
    (js-await (.close popup))))

(defn ^:async rename-script-in-panel!
  [context ext-id {:keys [expected-name code-opts]}]
  (let [panel (js-await (create-panel-page context ext-id))
        textarea (.locator panel "#code-area")
        rename-btn (.locator panel "button.btn-rename")
        save-section (.locator panel ".save-script-section")
        name-field (.locator save-section ".property-row:has(th:text('Name')) .property-value")]
    (js-await (-> (expect name-field) (.toContainText expected-name)))
    (js-await (.fill textarea (code-with-manifest code-opts)))
    (js-await (.click rename-btn))
    (js-await (wait-for-save-status panel "Renamed"))
    (js-await (.close panel))))

(defn ^:async verify-popup-scripts!
  [context ext-id {:keys [expected-count visible not-visible exact-not-visible]
                   :or {visible [] not-visible [] exact-not-visible []}}]
  (let [popup (js-await (.newPage context))
        popup-url (str "chrome-extension://" ext-id "/popup.html")]
    (js-await (.goto popup popup-url #js {:timeout 1000}))
    (js-await (wait-for-popup-ready popup))
    (js-await (wait-for-script-count popup (+ builtin-script-count expected-count)))
    (doseq [n visible]
      (js-await (-> (expect (.locator popup (str ".script-item:has-text(\"" n "\")")))
                    (.toBeVisible))))
    (doseq [n not-visible]
      (js-await (-> (expect (.locator popup (str ".script-item:has-text(\"" n "\")")))
                    (.not.toBeVisible))))
    (when (seq exact-not-visible)
      (let [script-names (js-await (.allTextContents (.locator popup ".script-item .script-name")))]
        (doseq [n exact-not-visible]
          (js-await (-> (expect (some #(= % n) script-names)) (.toBeFalsy))))))
    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))
