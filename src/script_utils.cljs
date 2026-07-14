(ns script-utils
  "Shared utilities for userscript data transformation and URL matching.

   This module contains pure functions with no external dependencies,
   enabling use across popup, storage, url-matching, and background modules
   without circular dependency issues."
  (:require [clojure.string :as string]))

;; ============================================================
;; Script data transformation
;; ============================================================

(def valid-run-at-values
  "Set of valid run-at timing values for userscripts."
  #{"document-start" "document-end" "document-idle"})

(def default-run-at
  "Default run-at timing if not specified."
  "document-idle")

(defn normalize-run-at
  "Validate and normalize a run-at value.
   Returns the value if valid, otherwise returns default-run-at."
  [run-at]
  (if (contains? valid-run-at-values run-at)
    run-at
    default-run-at))

(defn- js-arr->vec
  "Convert JS array to Clojure vector (handles nil)"
  [arr]
  (if arr (vec arr) []))

(defn normalize-match-patterns
  "Normalize match patterns to a vector.
   Accepts a string (single pattern) or vector of patterns.
   Empty string or nil means no patterns (manual-only script).
   Returns a vector of pattern strings."
  [match]
  (cond
    (nil? match) []
    (and (string? match) (empty? match)) []
    (string? match) [match]
    (vector? match) match
    :else (vec match)))

(defn- normalize-inject-value
  "Normalize manifest inject value to a vector of strings."
  [has-manifest? manifest-inject script-inject]
  (cond
    (not has-manifest?) script-inject
    (nil? manifest-inject) []
    (vector? manifest-inject) (vec manifest-inject)
    (js/Array.isArray manifest-inject) (vec manifest-inject)
    (string? manifest-inject) [manifest-inject]
    :else []))

(defn derive-script-fields
  "Derive script fields from manifest data.
   When manifest is present, it becomes the source of truth for derived fields."
  [script manifest]
  (let [has-manifest? (some? manifest)
        manifest-name (or (get manifest "script-name")
                          (get manifest "raw-script-name"))
        manifest-description (get manifest "description")
        manifest-run-at (get manifest "run-at")
        manifest-inject (get manifest "inject")
        manifest-match (get manifest "auto-run-match")
        manifest-library? (get manifest "library?")
        manifest-has-auto-run-key? (when has-manifest?
                                     (some #(= % "epupp/auto-run-match")
                                           (get manifest "found-keys")))
        match (cond
                manifest-has-auto-run-key?
                (normalize-match-patterns manifest-match)
                has-manifest?
                []
                :else
                (:script/match script))
        inject (normalize-inject-value has-manifest? manifest-inject (:script/inject script))]
    (cond-> script
      has-manifest? (assoc :script/match match
                           :script/run-at (normalize-run-at manifest-run-at)
                           :script/inject inject)
      (some? manifest-name) (assoc :script/name manifest-name)
      (and has-manifest? (some? manifest-description)) (assoc :script/description manifest-description)
      (and has-manifest? (nil? manifest-description)) (dissoc :script/description)
      (and has-manifest? manifest-library?) (assoc :script/library? true))))

(defn parse-scripts
  "Convert JS scripts array to Clojure with namespaced keys"
  ([js-scripts] (parse-scripts js-scripts {}))
  ([js-scripts {:keys [extract-manifest]}]
   (->> (js-arr->vec js-scripts)
        (mapv (fn [s]
                (let [script (cond-> {:script/id (.-id s)
                                      :script/code (.-code s)
                                      :script/enabled (.-enabled s)
                                      :script/created (.-created s)
                                      :script/modified (.-modified s)
                                      :script/builtin? (boolean (.-builtin s))
                                      :script/always-enabled? (boolean (.-alwaysEnabled s))}
                              (.-special s) (assoc :script/special? true)
                              (.-webInstallerScan s) (assoc :script/web-installer-scan true))
                      manifest (when (and extract-manifest (:script/code script))
                                 (try (extract-manifest (:script/code script))
                                      (catch :default _ nil)))]
                  (if extract-manifest
                    (derive-script-fields script manifest)
                    script)))))))

(defn script->js
  "Convert script map to JS object with simple keys for storage.
   Includes runAt and match for early injection loader.
   Other derived fields (name, description, inject) are not stored
   since they are re-derived from the manifest on load."
  [script]
  #js {:id (:script/id script)
       :code (:script/code script)
       :enabled (:script/enabled script)
       :created (:script/created script)
       :modified (:script/modified script)
       :builtin (:script/builtin? script)
       :alwaysEnabled (:script/always-enabled? script)
       :special (:script/special? script)
       :webInstallerScan (:script/web-installer-scan script)
       :runAt (:script/run-at script)
       :match (clj->js (or (:script/match script) []))})

(defn script->panel-js
  "Convert script map to JS object for panel save/rename messages."
  [script]
  #js {:id (:script/id script)
       :name (:script/name script)
       :description (:script/description script)
       :match (clj->js (or (:script/match script) []))
       :code (:script/code script)
       :enabled (:script/enabled script)
       :runAt (:script/run-at script)
       :inject (clj->js (or (:script/inject script) []))
       :force (:script/force? script)})

;; ============================================================
;; Script name normalization
;; ============================================================

(defn normalize-script-name
  "Normalize a script name to a consistent format for uniqueness.
   Supports using a Clojure namespace in the manifest, and have
   it normalized to a proper filename.
   - Lowercase
   - Replace spaces, and dashes with underscores
   - Replace `.` with `/`
   - Preserve `/` for namespace-like paths
   - Append .cljs extension
   - Remove invalid characters"
  [input-name]
  (let [base-name (if (string/ends-with? input-name ".cljs")
                    (subs input-name 0 (- (count input-name) 5))
                    input-name)]
    (-> base-name
        (string/lower-case)
        (string/replace #"[.]+" "/")
        (string/replace #"[\s-]+" "_")
        (string/replace #"[^a-z0-9_/]"  "")
        (str ".cljs"))))

;; ============================================================
;; Built-in script detection
;; ============================================================

(defn validate-script-name
  "Validate script names for reserved namespace and path traversal.
   Returns nil when valid, or a string error message when invalid."
  [input-name]
  (cond
    (nil? input-name) nil
    (not (string? input-name)) "Script name must be a string"
    (.startsWith (.toLowerCase input-name) "epupp/") "Cannot create scripts in reserved namespace: epupp/"
    (.startsWith input-name "/") "Script name cannot start with '/'"
    (or (.includes input-name "./") (.includes input-name "../")) "Script name cannot contain './' or '../'"
    (.startsWith input-name ".") "Script name cannot start with '.'"
    :else nil))

(defn- extract-raw-name
  "Extract raw script name from manifest or script data."
  [script manifest]
  (let [manifest-name (when manifest
                        (or (get manifest "raw-script-name")
                            (get manifest "script-name")))]
    (or manifest-name (:script/name script))))

(defn- normalize-name-with-validation
  "Normalize a raw name and validate both pre- and post-normalization.
   Returns {:normalized-name :error} map."
  [raw-name is-builtin?]
  (if (nil? raw-name)
    {:normalized-name nil :error nil}
    (let [normalized-name (if is-builtin?
                            raw-name
                            (normalize-script-name raw-name))
          pre-error (when (not is-builtin?)
                      (validate-script-name raw-name))
          post-error (when (and (not is-builtin?) (not pre-error)
                                (.startsWith normalized-name "epupp/"))
                       "Cannot create scripts in reserved namespace: epupp/")]
      {:normalized-name normalized-name
       :error (or pre-error post-error)})))

(defn- resolve-script-name
  "Extract and normalize script name from manifest and script data.
   Returns {:raw-name :normalized-name :error} map."
  [script manifest is-builtin?]
  (let [raw-name (extract-raw-name script manifest)
        {:keys [normalized-name error]} (normalize-name-with-validation raw-name is-builtin?)]
    {:raw-name raw-name
     :normalized-name normalized-name
     :error error}))

(defn- compute-enabled-state
  "Determine whether a script should be enabled."
  [script derived existing]
  (let [has-auto-run? (seq (:script/match derived))
        is-update? (some? existing)]
    (cond
      (:script/always-enabled? script) true
      (:script/web-installer-scan script) (if is-update?
                                            (:script/enabled existing)
                                            true)
      (not has-auto-run?) false
      is-update? (:script/enabled existing)
      (some? (:script/enabled derived)) (:script/enabled derived)
      :else false)))

(defn- merge-and-timestamp
  "Merge script with existing data and add timestamps."
  [derived new-enabled existing now-iso]
  (let [is-update? (some? existing)
        merged (if is-update?
                 (-> (merge existing (dissoc derived :script/enabled))
                     (assoc :script/enabled new-enabled))
                 (assoc derived :script/enabled new-enabled))]
    (if is-update?
      (assoc merged :script/modified now-iso)
      (assoc merged
             :script/created now-iso
             :script/modified now-iso))))

(defn normalize-and-merge-script
  "Pure script normalization and merge. No persistence, no atoms.
   Returns {:script updated-script} or {:error error-message}.

   Handles: name extraction from manifest, name validation/normalization,
   manifest-derived fields (via derive-script-fields), enabled-state
   computation, existing-script merge, and timestamps.

   Each caller is responsible for:
   - Manifest extraction
   - ID resolution
   - Error handling strategy (throw vs error map)
   - Persistence"
  [script existing manifest {:keys [is-builtin? now-iso]}]
  (let [{:keys [normalized-name error]} (resolve-script-name script manifest is-builtin?)]
    (if error
      {:error error}
      (let [has-manifest? (some? manifest)
            named-script (cond-> script
                           normalized-name (assoc :script/name normalized-name))
            with-match-fallback (if (and (not has-manifest?)
                                         (nil? (:script/match named-script))
                                         existing)
                                  (assoc named-script :script/match (:script/match existing))
                                  named-script)
            derived (derive-script-fields with-match-fallback manifest)
            derived (if normalized-name
                      (assoc derived :script/name normalized-name)
                      derived)
            new-enabled (compute-enabled-state script derived existing)]
        {:script (merge-and-timestamp derived new-enabled existing now-iso)}))))

(defn builtin-script?
  "Check if a script is a built-in script via :script/builtin? metadata."
  [script]
  (boolean (:script/builtin? script)))

(defn special-script?
  "Check if a script is a special script via :script/special? flag.
   Special scripts appear in a dedicated 'Special' section in the popup."
  [script]
  (boolean (:script/special? script)))

(defn library-script?
  "Check if a script is a library script via :script/library? metadata."
  [script]
  (boolean (:script/library? script)))

(defn internal-script?
  "True when script name is under epupp/internal/ (implementation deps, not for popup UI)."
  [script]
  (let [name (or (:script/name script) "")]
    (.startsWith name "epupp/internal/")))

(defn name-matches-builtin?
  "Check if a normalized script name matches any builtin script's normalized name.
   Used to prevent creating scripts with names that would shadow builtins."
  [scripts script-name]
  (let [builtins (filter builtin-script? scripts)]
    (some #(= script-name (normalize-script-name (:script/name %)))
          builtins)))

;; ============================================================
;; Script ID generation
;; ============================================================

(defn detect-name-conflict
  "Detect if saving with new-name would conflict with existing scripts.
   Returns the conflicting script if conflict exists, nil otherwise.

   A conflict exists when:
   - A script with the normalized new-name exists in scripts-list
   - AND the normalized new-name differs from the original-name

   This allows editing a script without changing its name (no conflict),
   but prevents creating a new script or renaming to an existing name.

   Args:
   - scripts-list: vector of script maps with :script/name
   - new-name: the desired name (can be unnormalized, e.g., 'My Script')
   - original-name: current script's normalized name (nil for new scripts)"
  [scripts-list new-name original-name]
  (if (nil? new-name)
    nil
    (let [normalized-name (normalize-script-name new-name)
          ;; Find existing script with matching normalized name
          existing-script (some #(when (= (normalize-script-name (:script/name %)) normalized-name) %)
                                scripts-list)]
      ;; Conflict if existing script found AND we're not just keeping the same name
      (if (and existing-script
               (not= normalized-name original-name))
        existing-script
        nil))))


(defn filter-visible-scripts
  "Filter scripts for ls. When include-hidden? is true, includes built-ins."
  [scripts include-hidden?]
  (if include-hidden?
    scripts
    (filterv (comp not builtin-script?) scripts)))

(defn generate-script-id
  "Generate a stable, unique script ID based on timestamp.
   The ID is immutable once created - it does not change when the script is renamed."
  []
  (str "script-" (.now js/Date)))

(defn diff-scripts
  "Detect changes between old and new script lists.
   Returns {:added [names], :modified [names], :removed [names]}
   where modified means the script code changed."
  [old-scripts new-scripts]
  (let [old-by-name (into {} (map (juxt :script/name identity) old-scripts))
        new-by-name (into {} (map (juxt :script/name identity) new-scripts))
        old-names (set (keys old-by-name))
        new-names (set (keys new-by-name))
        added (filterv #(not (contains? old-names %)) new-names)
        removed (filterv #(not (contains? new-names %)) old-names)
        common (filterv #(contains? old-names %) new-names)
        modified (filterv (fn [name]
                            (not= (:script/code (get old-by-name name))
                                  (:script/code (get new-by-name name))))
                          common)]
    {:added added
     :modified modified
     :removed removed}))


