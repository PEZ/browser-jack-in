(ns popup.views.scripts
  "Script list UI components for the popup.
   Extracted from popup.cljs to reduce file size."
  (:require [script-utils :as script-utils]
            [icons :as icons]
            [view-elements :as view-elements]
            [clojure.string :as str]))

;; ============================================================
;; Pure helpers
;; ============================================================

(defn- run-at-badge
  "Returns a badge component for non-default run-at timings."
  [run-at]
  (case run-at
    "document-start" [:span.run-at-badge {:title "Runs at document-start (before page loads)"}
                      [icons/rocket {:size 16}]]
    "document-end" [:span.run-at-badge {:title "Runs with document-end timing (wait explicitly if you need DOM-ready behavior)"}
                    [icons/flag {:size 16}]]
    nil))

(defn- safe-pattern-display
  "Safely extract a displayable string from a pattern value.
   Handles malformed data (nested arrays, nil) defensively."
  [pattern]
  (cond
    (nil? pattern) nil
    (string? pattern) pattern
    (or (vector? pattern) (array? pattern))
    (safe-pattern-display (first pattern))
    :else (str pattern)))

(defn- script-item-classes [{:keys [builtin? reveal-highlight? recently-modified? leaving? entering?]}]
  (str (when builtin? "script-item-builtin ")
       (when reveal-highlight? "script-item-reveal-highlight ")
       (when (and recently-modified? (not leaving?)) "script-item-fs-modified ")
       (when entering? "entering ")
       (when leaving? "leaving")))

(defn- show-auto-run-checkbox? [script]
  (and (or (seq (:script/match script))
           (:script/web-installer-scan script))
       (not (:script/always-enabled? script))))

(defn- script-match-text [patterns-text script]
  (cond
    patterns-text patterns-text
    (:script/web-installer-scan script) "Injected when Userscripts are detected"
    :else "No auto-run (manual only)"))

;; ============================================================
;; Script item components
;; ============================================================

(defn- script-name-row [dispatch! {:script/keys [name] script-id :script/id :as script} runtime-error]
  (let [builtin? (script-utils/builtin-script? script)]
    [:div.script-row-header
     [:span.script-name
      (when builtin?
        [:span.builtin-indicator {:title "Built-in script"}
         [icons/package]])
      [:span.script-name-text {:title name} name]
      (when runtime-error
        [:span.script-error-indicator
         {:title (or (:error/message runtime-error) "Resolution error")
          :data-e2e "script-error"}
         [icons/warning {:size 14}]])]
     [:div.script-actions
      [view-elements/action-button
       {:button/variant :secondary
        :button/class "script-inspect"
        :button/size :md
        :button/icon icons/eye
        :button/title "Inspect script"
        :button/on-click #(dispatch! [[:popup/ax.inspect-script script-id]])}
       nil]
      (when-not builtin?
        [view-elements/action-button
         {:button/variant :danger
          :button/class "script-delete"
          :button/size :md
          :button/icon icons/trash
          :button/title "Delete script"
          :button/on-click #(when (js/confirm "Delete this script?")
                              (dispatch! [[:popup/ax.delete-script script-id]]))}
         nil])]]))

(defn- script-pattern-row
  [dispatch!
   {:script/keys [enabled run-at]
    script-id :script/id :as script}
   matching-pattern patterns-display patterns-tooltip]
  [:div.script-row-pattern
   (when (show-auto-run-checkbox? script)
     [:input.pattern-checkbox {:type "checkbox"
                               :checked enabled
                               :title (if enabled "Auto-run enabled" "Auto-run disabled")
                               :on-change #(dispatch! [[:popup/ax.toggle-script script-id matching-pattern]])}])
   (when run-at
     (run-at-badge run-at))
   [:span.script-match {:title (script-match-text patterns-tooltip script)}
    (script-match-text patterns-display script)]])

(defn script-item [dispatch!
                   {:script/keys [name match description]
                    script-id :script/id
                    :as script}
                   current-url
                   {:keys [reveal-highlight? recently-modified? leaving? entering? runtime-error]}]
  (let [matching-pattern (script-utils/get-matching-pattern current-url script)
        builtin? (script-utils/builtin-script? script)
        patterns-display (when (seq match)
                           (->> match
                                (mapv safe-pattern-display)
                                (filterv some?)
                                (str/join " ")))
        patterns-tooltip (when (seq match)
                           (->> match
                                (mapv safe-pattern-display)
                                (filterv some?)
                                (str/join "\n")))]
    [:div.script-item {:data-script-name name
                       :data-e2e-script-id script-id
                       :class (script-item-classes {:builtin? builtin?
                                                    :reveal-highlight? reveal-highlight?
                                                    :recently-modified? recently-modified?
                                                    :leaving? leaving?
                                                    :entering? entering?})}
     [:div.script-button-column
      [view-elements/action-button
       {:button/variant :secondary
        :button/class "script-run"
        :button/size :md
        :button/icon icons/play
        :button/title "Run script"
        :button/on-click #(dispatch! [[:popup/ax.evaluate-script script-id]])}
       nil]]
     [:div.script-content-column
      [script-name-row dispatch! script runtime-error]
      [script-pattern-row dispatch! script matching-pattern patterns-display patterns-tooltip]
      (when (seq description)
        [:div.script-row-description
         [:span.script-description {:title description}
          description]])]]))

;; ============================================================
;; Script sections
;; ============================================================

(defn- matching-scripts-empty-state [no-user-scripts? example-pattern]
  [:div.no-scripts
   (if no-user-scripts?
     "No userscripts yet!"
     "No scripts auto-run for this page.")
   [:div.no-scripts-hint
    (if no-user-scripts?
      "Create your first script in DevTools → Epupp panel."
      (if example-pattern
        [:span "Auto-run patterns look like " [:code example-pattern]]
        "Check your script patterns in DevTools → Epupp panel."))]])

(defn- default-script-sort [{:keys [item]}]
  [(if (script-utils/builtin-script? item) 1 0)
   (str/lower-case (or (:script/name item) ""))])

(defn- render-script-items [dispatch! items current-url opts]
  (let [{:keys [highlight-name modified-set errors]} opts]
    (for [{:keys [item] :ui/keys [entering? leaving?]} items
          :let [script item]]
      ^{:key (:script/id script)}
      [script-item dispatch! script current-url
       {:reveal-highlight? (= (:script/name script) highlight-name)
        :recently-modified? (contains? modified-set (:script/name script))
        :leaving? leaving?
        :entering? entering?
        :runtime-error (get errors (:script/name script))}])))

(defn- matching-url-shadow? [current-url shadow-item]
  (and (not (script-utils/special-script? (:item shadow-item)))
       (script-utils/get-matching-pattern current-url (:item shadow-item))))

(defn matching-scripts-section [dispatch! {:scripts/keys [list current-url]
                                           :ui/keys [scripts-shadow reveal-highlight-script-name recently-modified-scripts]
                                           :runtime/keys [errors]}]
  (let [matching-shadow (->> scripts-shadow
                             (filterv (partial matching-url-shadow? current-url))
                             (sort-by default-script-sort))
        user-scripts (filterv #(not (script-utils/builtin-script? %)) list)
        no-user-scripts? (empty? user-scripts)
        example-pattern (script-utils/url-to-match-pattern current-url {:wildcard-scheme? true})
        modified-set (or recently-modified-scripts #{})
        error-map (or errors {})]
    [:div.script-list
     (if (seq matching-shadow)
       (render-script-items dispatch! matching-shadow current-url
                            {:highlight-name reveal-highlight-script-name
                             :modified-set modified-set
                             :errors error-map})
       [matching-scripts-empty-state no-user-scripts? example-pattern])]))

(defn- filtered-script-section
  [dispatch!
   {:scripts/keys [current-url]
    :ui/keys [scripts-shadow reveal-highlight-script-name recently-modified-scripts]
    :runtime/keys [errors]}
   {:keys [filter-fn sort-fn empty-text empty-hint]}]
  (let [sort-comparator (or sort-fn default-script-sort)
        filtered (->> scripts-shadow
                      (filterv filter-fn)
                      (sort-by sort-comparator))
        modified-set (or recently-modified-scripts #{})
        error-map (or errors {})]
    [:div.script-list
     (if (seq filtered)
       (render-script-items dispatch! filtered current-url
                            {:highlight-name reveal-highlight-script-name
                             :modified-set modified-set
                             :errors error-map})
       [:div.no-scripts
        empty-text
        [:div.no-scripts-hint empty-hint]])]))

(defn manual-scripts-section [dispatch! state]
  [filtered-script-section dispatch! state
   {:filter-fn (fn [{:keys [item]}]
                 (and (not (script-utils/special-script? item))
                      (not (script-utils/library-script? item))
                      (empty? (:script/match item))))
    :empty-text "No manual scripts."
    :empty-hint "Scripts without auto-run patterns appear here."}])

(defn libraries-section [dispatch! state]
  [filtered-script-section dispatch! state
   {:filter-fn (fn [{:keys [item]}]
                 (and (script-utils/library-script? item)
                      (not (script-utils/special-script? item))
                      (empty? (:script/match item))))
    :empty-text "No library scripts."
    :empty-hint "Scripts with :epupp/library? true appear here."}])

(defn other-scripts-section [dispatch! state]
  (let [current-url (:scripts/current-url state)]
    [filtered-script-section dispatch! state
     {:filter-fn (fn [{:keys [item]}]
                   (and (not (script-utils/special-script? item))
                        (seq (:script/match item))
                        (not (script-utils/get-matching-pattern current-url item))))
      :empty-text "No auto-run scripts for other pages."
      :empty-hint "Scripts with match patterns that don't match this page appear here."}]))

(defn special-scripts-section [dispatch! state]
  [filtered-script-section dispatch! state
   {:filter-fn (fn [{:keys [item]}]
                 (script-utils/special-script? item))
    :sort-fn (fn [{:keys [item]}]
               (str/lower-case (or (:script/name item) "")))
    :empty-text "No special scripts."
    :empty-hint "Background-managed scripts appear here."}])

;; ============================================================
;; Script categorization
;; ============================================================

(defn categorize-scripts [scripts current-url]
  {:special (->> scripts (filterv script-utils/special-script?))
   :matching (->> scripts
                  (filterv #(and (not (script-utils/special-script? %))
                                 (script-utils/get-matching-pattern current-url %))))
   :other-autorun (->> scripts
                       (filterv (fn [s]
                                  (and (not (script-utils/special-script? s))
                                       (seq (:script/match s))
                                       (not (script-utils/get-matching-pattern current-url s))))))
   :manual (->> scripts
                (filterv #(and (not (script-utils/special-script? %))
                               (not (script-utils/library-script? %))
                               (empty? (:script/match %)))))
   :library (->> scripts
                 (filterv (fn [s]
                            (and (script-utils/library-script? s)
                                 (not (script-utils/special-script? s))
                                 (empty? (:script/match s))))))})
