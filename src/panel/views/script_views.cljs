(ns panel.views.script-views
  (:require [clojure.string :as str]
            [dep-resolver :as dep-resolver]
            [icons :as icons]
            [panel-actions :as panel-actions]
            [scittle-libs :as scittle-libs]
            [script-utils :as script-utils]
            [view-elements :as view-elements]))

(defn- run-at-badge
  "Returns a badge component for non-default run-at timings (panel version)."
  [run-at]
  (case run-at
    "document-start" [:span.run-at-badge {:title "Runs at document-start (before page loads)"}
                      [icons/rocket]]
    "document-end" [:span.run-at-badge {:title "Runs with document-end timing (wait explicitly if you need DOM-ready behavior)"}
                    [icons/flag]]
    nil))

(defn- hint-message
  "Render a hint/warning message below a field."
  [{:keys [type text]}]
  [:span {:class (str "field-hint " (when type (str "hint-" type)))}
   text])

(defn- empty-match? [v]
  (or (nil? v)
      (and (string? v) (empty? v))
      (and (sequential? v) (empty? v))))

(defn- format-auto-run-match
  "Format auto-run-match for display, handling both string and vector.
   Empty strings and empty collections are treated as nil (no auto-run)."
  [auto-run-match]
  (cond
    (empty-match? auto-run-match) nil
    (string? auto-run-match) [auto-run-match]
    (sequential? auto-run-match) (vec auto-run-match)
    :else [auto-run-match]))

(defn- property-row
  "Render a single row in the metadata property table."
  [{:keys [label value values hint badge]}]
  [:tr.property-row {:data-e2e-property (-> label str/lower-case (str/replace " " "-"))}
   [:th.property-label label]
   [:td.property-value
    (cond
      (seq values)
      [:div.multi-values
       (for [[idx v] (map-indexed vector values)]
         ^{:key idx}
         [:div.value-item v])]

      (seq value)
      [:span.value-text value badge]

      :else
      [:span.value-placeholder "Not specified"])
    (when hint
      [hint-message hint])]])

(defn- unknown-keys-warning
  "Render warning for unknown manifest keys."
  [unknown-keys]
  (when (seq unknown-keys)
    [:div.manifest-warning
     [:span.warning-icon "⚠️"]
     [:span "Unknown manifest keys: "]
     [:code (str/join ", " unknown-keys)]]))

(defn- valid-require-url?
  "Returns true if the URL is a valid scittle://, epupp://, or ext dep URL."
  [url]
  (case (dep-resolver/classify-inject-url url)
    :scittle (some? (scittle-libs/resolve-scittle-url url))
    :epupp (some? (dep-resolver/parse-epupp-url url))
    :ext-dep true
    false))

(defn- epupp-url?
  "Returns true if the URL is an epupp:// URL."
  [url]
  (= :epupp (dep-resolver/classify-inject-url url)))

(defn- categorize-requires
  "Categorize require URLs into valid, invalid, and missing-epupp.
   Returns {:valid [...] :invalid [...] :missing-epupp [...]}."
  [requires scripts-list]
  (when (seq requires)
    (let [known-urls (filter #(not= :unknown (dep-resolver/classify-inject-url %)) requires)
          valid-urls (filter valid-require-url? known-urls)
          invalid-urls (remove valid-require-url? known-urls)
          epupp-urls (filter epupp-url? valid-urls)
          script-names (set (map :script/name scripts-list))
          missing-epupp (filterv (fn [url]
                                   (let [name (dep-resolver/parse-epupp-url url)]
                                     (and name (not (contains? script-names name)))))
                                 epupp-urls)]
      {:valid (vec valid-urls)
       :invalid (vec invalid-urls)
       :missing-epupp missing-epupp})))

(defn- invalid-requires-warning
  "Render warning for invalid require URLs."
  [invalid-requires]
  (when (seq invalid-requires)
    [:div.manifest-warning
     [:span.warning-icon "⚠️"]
     [:span "Invalid requires: "]
     [:ul.invalid-requires-list
      (for [[idx url] (map-indexed vector invalid-requires)]
        ^{:key idx}
        [:li [:code url]])]]))

(defn- missing-epupp-warning
  "Render warning for epupp:// references pointing to scripts not in storage."
  [missing-epupp]
  (when (seq missing-epupp)
    [:div.manifest-warning
     [:span.warning-icon "⚠️"]
     [:span "Library scripts not found: "]
     [:ul.invalid-requires-list
      (for [[idx url] (map-indexed vector missing-epupp)]
        ^{:key idx}
        [:li [:code url]])]]))

(defn- no-manifest-message
  "Message shown when code has no manifest annotations."
  []
  [:div.no-manifest-message
   [:p "Add a manifest map to your code to define script metadata:"]
   [:pre.manifest-example
    "{:epupp/script-name \"My Script\"\n :epupp/auto-run-match \"https://example.com/*\"\n :epupp/description \"What it does\"}\n\n(ns my-script)\n; your code..."]])

(defn- compute-name-validation
  "Compute name validation state from raw inputs."
  [raw-script-name script-name original-name scripts-list]
  (let [raw-name (or raw-script-name script-name)
        normalized-name (when (seq raw-name)
                          (script-utils/normalize-script-name raw-name))
        editing-builtin? (and original-name
                              (script-utils/name-matches-builtin? scripts-list original-name))
        name-error (when (and raw-name (not editing-builtin?))
                     (script-utils/validate-script-name raw-name))
        name-changed? (and original-name
                           normalized-name
                           (not= normalized-name original-name))
        conflicting-script (script-utils/detect-name-conflict scripts-list raw-name original-name)
        has-name-conflict? (boolean conflicting-script)]
    {:raw-name raw-name
     :normalized-name normalized-name
     :editing-builtin? editing-builtin?
     :name-error name-error
     :name-changed? name-changed?
     :conflicting-script conflicting-script
     :has-name-conflict? has-name-conflict?}))

(defn- compute-button-state
  "Compute button enabled/disabled state and labels."
  [{:keys [code script-name has-manifest? has-name-conflict? name-error
           editing-builtin? name-changed? original-name]}]
  {:save-disabled? (or (empty? code)
                       (empty? script-name)
                       (not has-manifest?)
                       has-name-conflict?
                       name-error
                       (and editing-builtin? (not name-changed?)))
   :save-button-text (cond
                       has-name-conflict? "Save Script"
                       name-changed? "Create Script"
                       :else "Save Script")
   :show-rename? (and original-name
                      (not editing-builtin?)
                      name-changed?
                      (not has-name-conflict?))
   :rename-disabled? (or editing-builtin? name-error)})

(defn- name-hint
  "Compute the hint for the Name property row."
  [{:keys [name-error has-name-conflict? normalized-name name-normalized? raw-script-name]}]
  (cond
    name-error {:type "error" :text name-error}
    has-name-conflict? {:type "warning" :text (str "\"" normalized-name "\" already exists")}
    name-normalized? {:type "info" :text (str "Normalized from: " raw-script-name)}))

(defn- requires-summary
  "Format requires count for display."
  [inject valid invalid]
  (when (seq inject)
    (str (count inject) " "
         (if (= 1 (count inject)) "library" "libraries")
         (when (and (empty? invalid) (seq valid)) " ✓"))))

(defn- manifest-metadata-table
  "Render the metadata property table from parsed manifest."
  [{:keys [script-name auto-run-matches
           script-description run-at run-at-invalid? raw-run-at inject
           valid invalid] :as ctx}]
  [:table.metadata-table
   [:tbody
    [property-row
     {:label "Name"
      :value script-name
      :hint (name-hint ctx)}]
    [property-row
     {:label "Auto-run"
      :values (when (seq auto-run-matches) auto-run-matches)
      :value (when (empty? auto-run-matches) "No auto-run (manual only)")}]
    [property-row
     {:label "Description"
      :value script-description}]
    [property-row
     {:label "Run At"
      :value (or run-at "document-idle (default)")
      :badge (run-at-badge run-at)
      :hint (when run-at-invalid?
              {:type "warning"
               :text (str "Invalid value \"" raw-run-at "\" - using default")})}]
    [property-row
     {:label "Requires"
      :value (requires-summary inject valid invalid)}]]])

(defn- save-button-title
  "Compute title text for the save button."
  [{:keys [name-error has-name-conflict? normalized-name
           editing-builtin? name-changed? script-name]}]
  (cond
    name-error name-error
    has-name-conflict? (str "Script \"" normalized-name "\" already exists - use Overwrite to replace it")
    (and editing-builtin? (not name-changed?)) "Cannot overwrite built-in script - change the name to create a copy"
    (empty? script-name) "Add :epupp/script-name to manifest"
    :else nil))

(defn- overwrite-button-title [name-error normalized-name conflicting-script]
  (cond
    name-error name-error
    (script-utils/builtin-script? conflicting-script) "Cannot overwrite built-in scripts"
    :else (str "Replace existing \"" normalized-name "\" with this code")))

(defn- rename-button-title [name-error rename-disabled? original-name normalized-name]
  (cond
    name-error name-error
    rename-disabled? "Cannot rename built-in scripts"
    :else (str "Rename from \"" original-name "\" to \"" normalized-name "\"")))

(defn- save-action-buttons
  "Render save/overwrite/rename action buttons."
  [dispatch! {:keys [save-disabled? save-button-text has-name-conflict?
                     show-rename? rename-disabled? name-error
                     normalized-name original-name
                     conflicting-script] :as ctx}]
  [:div.save-actions
   [view-elements/action-button
    {:button/variant :success
     :button/class "btn-save"
     :button/disabled? save-disabled?
     :button/on-click #(dispatch! [[:editor/ax.save-script]])
     :button/title (save-button-title ctx)}
    save-button-text]
   (when has-name-conflict?
     [view-elements/action-button
      {:button/variant :warning
       :button/class "btn-overwrite"
       :button/disabled? (or name-error (script-utils/builtin-script? conflicting-script))
       :button/on-click #(dispatch! [[:editor/ax.save-script-overwrite]])
       :button/title (overwrite-button-title name-error normalized-name conflicting-script)}
      "Overwrite"])
   (when show-rename?
     [view-elements/action-button
      {:button/variant :primary
       :button/class "btn-rename"
       :button/disabled? rename-disabled?
       :button/on-click #(dispatch! [[:editor/ax.rename-script]])
       :button/title (rename-button-title name-error rename-disabled? original-name normalized-name)}
      "Rename"])])

(defn- new-script-button
  "Button to clear editor and start a new script. Shows confirmation if code has changed."
  [dispatch! {:panel/keys [code]}]
  (let [has-changes? (and (seq code)
                          (not= code (panel-actions/default-script nil)))]
    [view-elements/action-button
     {:button/variant :secondary
      :button/class "btn-new-script"
      :button/icon icons/add
      :button/title "Start a new script"
      :button/on-click (fn [_e]
                         (if has-changes?
                           (when (js/confirm "Clear current script and start fresh?")
                             (dispatch! [[:editor/ax.new-script]]))
                           (dispatch! [[:editor/ax.new-script]])))}
     "New"]))

(defn save-script-section [dispatch! {:panel/keys [script-name script-match script-description
                                                   code original-name
                                                   manifest-hints scripts-list]
                                      :as state}]
  (let [has-manifest? (some? manifest-hints)
        {:keys [name-normalized? raw-script-name unknown-keys run-at-invalid? raw-run-at inject]} manifest-hints
        {:keys [valid invalid missing-epupp]} (categorize-requires inject scripts-list)
        auto-run-matches (format-auto-run-match script-match)
        name-val (compute-name-validation raw-script-name script-name original-name scripts-list)
        {:keys [has-name-conflict?]} name-val
        btn (compute-button-state (merge name-val {:code code :script-name script-name
                                                   :has-manifest? has-manifest?
                                                   :original-name original-name}))
        run-at (if run-at-invalid? "document-idle" raw-run-at)
        ctx (merge name-val btn {:script-name script-name
                                 :name-normalized? name-normalized?
                                 :raw-script-name raw-script-name
                                 :auto-run-matches auto-run-matches
                                 :script-description script-description
                                 :run-at run-at
                                 :run-at-invalid? run-at-invalid?
                                 :raw-run-at raw-run-at
                                 :inject inject
                                 :valid valid
                                 :invalid invalid
                                 :original-name original-name})]
    [:div.save-script-section {:data-e2e-scripts-count (count scripts-list)
                               :data-e2e-editing (boolean original-name)
                               :data-e2e-conflict has-name-conflict?}
     [:div.save-script-header
      [:span.header-title (if original-name "Edit Userscript" "Save as Userscript")]
      [new-script-button dispatch! state]]
     (if has-manifest?
       [:div.save-script-form.manifest-driven
        [manifest-metadata-table ctx]
        [unknown-keys-warning unknown-keys]
        [invalid-requires-warning invalid]
        [missing-epupp-warning missing-epupp]
        [save-action-buttons dispatch! ctx]]
       [:div.save-script-form.no-manifest
        [no-manifest-message]
        [:div.save-actions
         [view-elements/action-button
          {:button/variant :success
           :button/class "btn-save"
           :button/disabled? true
           :button/title "Add manifest to code to enable saving"}
          "Save Script"]]])]))
