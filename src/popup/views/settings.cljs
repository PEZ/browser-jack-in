(ns popup.views.settings
  (:require [view-elements :as view-elements]
            [popup.views.components :as components]))

(defn dev-tools-section
  "Dev tools: sponsor username, reset sponsor status, dump dev log.
   Only visible in dev/test builds."
  [dispatch! {:sponsor/keys [sponsored-username]}]
  [:div.dev-tools-content
   [:div.setting
    [:label {:for "dev-sponsor-username"} "Sponsor Username"]
    [:input {:type "text"
             :id "dev-sponsor-username"
             :value (or sponsored-username "PEZ")
             :on-change (fn [e]
                          (dispatch! [[:popup/ax.set-dev-sponsor-username
                                       (.. e -target -value)]]))}]]
   [:div.dev-tools-buttons
    [view-elements/action-button
     {:button/variant :secondary
      :button/on-click #(dispatch! [[:popup/ax.reset-sponsor-status]])}
     "Reset Sponsor Status"]
    [view-elements/action-button
     {:button/variant :secondary
      :button/class "dev-log-btn"
      :button/on-click #(dispatch! [[:popup/ax.dump-dev-log]])}
     "Dump Dev Log"]]])

(defn- reconnect-toggle [dispatch! state prefix auto-connect-active?]
  [:div.setting (when auto-connect-active?
                  {:title "Overridden by Auto-connect"})
   [:label.checkbox-label {:class (when auto-connect-active? "disabled")}
    [:input {:type "checkbox"
             :id (str prefix "auto-reconnect-repl")
             :checked (:settings/auto-reconnect-repl state)
             :disabled auto-connect-active?
             :on-change #(dispatch! [[:popup/ax.toggle-auto-reconnect-repl]])}]
    "Reconnect connected tabs on navigation"]
   [:p.description
    "When a connected tab navigates to a new page, automatically reconnect. "
    "REPL state is lost but the connection is restored."]])

(defn- auto-connect-toggle [dispatch! auto-connect-level prefix]
  [:div.setting
   [:label.select-label {:for (str prefix "auto-connect-level")}
    "Auto-connect"]
   [:div.select-wrapper
    [:select {:id (str prefix "auto-connect-level")
              :value auto-connect-level
              :on-change #(dispatch! [[:popup/ax.set-auto-connect-level (.. % -target -value)]])}
     [:option {:value "off"} "Never"]
     [:option {:value "all-pages"} "On page load"]
     [:option {:value "all-tabs"} "On page load + tab activation"]]]
   [:p.description.warning
    (case auto-connect-level
      "all-pages" "Epupp connects a REPL to every page you load."
      "all-tabs" "Epupp connects a REPL to every page you load, and follows your active tab."
      "Auto-connect is disabled.")]])

(defn- fs-sync-toggle [dispatch! state prefix]
  (let [current-tab-id (:scripts/current-tab-id state)
        fs-sync-tab-id (:fs/sync-tab-id state)
        current-tab-connected? (some #(= (:tab-id %) (str current-tab-id))
                                     (:repl/connections state))
        fs-sync-enabled? (and (some? current-tab-id)
                              (= current-tab-id fs-sync-tab-id))]
    [:div.setting
     [:label.checkbox-label
      [:input {:type "checkbox"
               :id (str prefix "fs-repl-sync")
               :checked fs-sync-enabled?
               :disabled (not current-tab-connected?)
               :on-change #(dispatch! [[:popup/ax.toggle-fs-sync]])}]
      "Allow REPL FS Sync for this tab"]
     [:p.description.warning
      (if current-tab-connected?
        "Only enable this on pages you trust."
        "Connect a REPL to enable FS Sync for this tab.")]]))

(defn repl-settings-toggles
  "Returns a vector of three REPL setting toggle elements.
   Use `into` to splice into a parent container.
   id-prefix differentiates duplicate instances in the DOM."
  [dispatch! state {:keys [id-prefix]}]
  (let [{:settings/keys [auto-connect-level]} state
        prefix (or id-prefix "")
        auto-connect-active? (not= auto-connect-level "off")]
    [[reconnect-toggle dispatch! state prefix auto-connect-active?]
     [auto-connect-toggle dispatch! auto-connect-level prefix]
     [fs-sync-toggle dispatch! state prefix]]))

(defn settings-content [dispatch! {:settings/keys [debug-logging] :as state}]
  [:div.settings-content
   (into
    [:div.settings-section
     [:h3.settings-section-title "REPL Connection"]
     [:p.section-description
      "Default ports (for hostnames without saved ports)."]
     [:div.port-row
      [components/port-input {:id "default-nrepl-port"
                              :label "nREPL:"
                              :value (:settings/default-nrepl-port state)
                              :on-change #(dispatch! [[:popup/ax.set-default-nrepl-port %]])}]
      [components/port-input {:id "default-ws-port"
                              :label "WebSocket:"
                              :value (:settings/default-ws-port state)
                              :on-change #(dispatch! [[:popup/ax.set-default-ws-port %]])}]]]
    (repl-settings-toggles dispatch! state {}))
   [:div.settings-section
    [:h3.settings-section-title "Diagnostics"]
    [:div.setting
     [:label.checkbox-label
      [:input#debug-logging {:type "checkbox"
                             :checked debug-logging
                             :on-change #(dispatch! [[:popup/ax.toggle-debug-logging]])}]
      "Enable debug logging"]
     [:p.description
      "Show verbose Epupp logs in browser console (for troubleshooting)."]]]
   [:div.settings-section
    [:h3.settings-section-title "Export / Import Scripts"]
    [:p.section-description
     "Export your scripts to a JSON file for backup, or import scripts from a previously exported file."]
    [:div.export-import-buttons
     [view-elements/action-button
      {:button/variant :secondary
       :button/class "export-btn"
       :button/on-click #(dispatch! [[:popup/ax.export-scripts]])}
      "Export Scripts"]
     [view-elements/action-button
      {:button/variant :secondary
       :button/class "import-btn"
       :button/on-click #(dispatch! [[:popup/ax.import-scripts]])}
      "Import Scripts"]]]])
