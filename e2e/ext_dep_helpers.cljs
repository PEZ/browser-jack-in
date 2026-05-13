(ns ext-dep-helpers
  "Shared helpers and constants for ext-dep E2E tests."
  (:require [clojure.string :as str]
            [fixtures :refer [create-popup-page create-panel-page
                              wait-for-save-status wait-for-popup-ready
                              get-script-item wait-for-checkbox-state
                              send-runtime-message]]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ext-dep-url
  "https://raw.githubusercontent.com/test-owner/test-repo/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/lib.cljs")

(def ext-dep-lib-code
  "{:epupp/script-name \"ext/lib.cljs\"\n :epupp/library? true}\n\n(ns ext.lib)\n\n(defn greet [who]\n  (str \"Hello from ext-dep, \" who \"!\"))")

(def git-raw-url
  "https://raw.githubusercontent.com/PEZ/pez-my-epupp-hq/3dbf6393916cd4e384826b093ab6e9a96b1793f9/userscripts/pez/test_lib.cljs")

(def gist-raw-url
  "https://gist.githubusercontent.com/PEZ/f7059fe7328bb25ee3f459d7457dc2a8/raw/50b3bed5fff509c2d86c2cbb4d3fa5f0f47c23ed/pez_test_lib.cljs")

(def pez-test-lib-code
  "{:epupp/script-name \"pez/test_lib.cljs\"\n :epupp/description \"Test library for injection\"\n :epupp/library? true}\n\n(ns pez.test-lib)\n\n(defn greeting [who]\n  (str \"Hello from pez.test-lib, \" who \"!\"))")

;; =============================================================================
;; Cache Construction
;; =============================================================================

(defn make-ext-dep-cache
  "Construct an ext-dep cache object for a single URL/code pair."
  [url code]
  (js-obj url
          #js {"cache/code" code
               "cache/url" url
               "cache/inject" #js []
               "cache/fetched-at" 1700000000000
               "cache/schema-version" 1}))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn code-with-manifest
  "Generate test code with epupp manifest metadata."
  [{:keys [name match description run-at inject code]
    :or {code "(println \"Test script\")"}}]
  (let [inject-str (when inject
                     (str "[" (str/join " " (map #(str "\"" % "\"") inject)) "]"))
        meta-parts (cond-> []
                     name (conj (str ":epupp/script-name \"" name "\""))
                     match (conj (str ":epupp/auto-run-match \"" match "\""))
                     description (conj (str ":epupp/description \"" description "\""))
                     run-at (conj (str ":epupp/run-at \"" run-at "\""))
                     inject (conj (str ":epupp/inject " inject-str)))
        meta-block (when (seq meta-parts)
                     (str "{" (str/join "\n " meta-parts) "}\n\n"))]
    (str meta-block code)))

(defn ^:async save-script-via-panel
  "Save a script via the panel UI. Returns after save confirmation."
  [context ext-id code]
  (let [panel (js-await (create-panel-page context ext-id))]
    (js-await (.fill (.locator panel "#code-area") code))
    (js-await (.click (.locator panel "button.btn-save")))
    (js-await (wait-for-save-status panel "Created"))
    (js-await (.close panel))))

(defn ^:async enable-script-via-popup
  "Enable a script via popup checkbox."
  [context ext-id script-name]
  (let [popup (js-await (create-popup-page context ext-id))]
    (js-await (wait-for-popup-ready popup))
    (let [script-item (get-script-item popup script-name)
          checkbox (.locator script-item "input[type='checkbox']")]
      (js-await (.click checkbox))
      (js-await (wait-for-checkbox-state checkbox true)))
    (js-await (.close popup))))

(defn ^:async set-ext-dep-cache!
  "Pre-populate the ext-dep cache in chrome.storage via e2e/set-storage message."
  [popup cache-obj]
  (js-await (send-runtime-message popup "e2e/set-storage"
                                  #js {:key "extDepCache" :value cache-obj})))

;; =============================================================================
;; Poll Helpers
;; =============================================================================

(defn- ^:async poll-until-non-nil!
  "Generic poll loop: calls poll-fn repeatedly until it returns non-nil.
   Throws with error-msg on timeout."
  [poll-fn timeout-ms interval-ms error-msg]
  (let [start (.now js/Date)]
    (loop []
      (let [result (js-await (poll-fn))]
        (cond
          (some? result) result
          (> (- (.now js/Date) start) timeout-ms)
          (throw (js/Error. error-msg))
          :else
          (do (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve interval-ms))))
              (recur)))))))

(defn ^:async poll-for-window-property!
  "Poll page for a window property to become non-nil."
  [page property-name timeout-ms]
  (poll-until-non-nil!
   #(.evaluate page (str "window['" property-name "']"))
   timeout-ms 50
   (str "Timeout waiting for window." property-name)))

(def ^:private scittle-eval-safely-fn
  "Pure JS fn for page.evaluate: tries scittle eval, returns nil on error."
  (fn [code]
    (try
      (js/scittle.core.eval_string code)
      (catch :default _e nil))))

(defn ^:async poll-for-scittle-eval!
  "Poll page for a scittle eval_string result to become non-nil."
  [page eval-code timeout-ms]
  (poll-until-non-nil!
   #(.evaluate page scittle-eval-safely-fn eval-code)
   timeout-ms 100
   (str "Timeout: scittle eval not ready: " eval-code)))
