(ns popup
  "Epupp extension popup - built with Squint + Reagami
   Inspired by Replicant tic-tac-toe state management pattern"
  (:require [reagami :as r]
            [event-handler :as event-handler]
            [icons :as icons]
            [manifest-parser :as mp]
            [script-utils :as script-utils]
            [popup-utils :as popup-utils]
            [popup-scripts :as popup-scripts]
            [popup-actions :as popup-actions]
            [popup-effects.port-effects :as port-effects]
            [popup-effects.connection-effects :as connection-effects]
            [popup-effects.script-effects :as script-effects]
            [popup-effects.settings-effects :as settings-effects]
            [popup-effects.sponsor-effects :as sponsor-effects]
            [popup-effects.ui-effects :as ui-effects]
            [log :as log]
            [storage :as storage]
            [view-elements :as view-elements]
            [test-logger :as test-logger]))

;; EXTENSION_CONFIG is injected by esbuild at bundle time from config/*.edn
;; Shape: {"dev": boolean, "depsString": string, "sectionsCollapsed": {...}}
(def ^:private config js/EXTENSION_CONFIG)

(defonce !state
  (atom {:ports/nrepl "3339"
         :ports/ws "3340"
         :ui/reveal-highlight-script-name nil ; Temporary highlight when revealing a script
         :ui/connecting? false
         :ui/connect-mode "direct"
         :ui/sections-collapsed (or (.-sectionsCollapsed config)
                                    {:repl-connect false
                                     :manual-scripts false
                                     :libraries true
                                     :matching-scripts false
                                     :other-scripts true
                                     :special true
                                     :settings true
                                     :dev-tools true})
         :browser/brave? false
         :scripts/list []
         :scripts/current-url nil
         :scripts/current-tab-id nil
         :settings/auto-connect-level "off"
         :settings/auto-reconnect-repl true
         :fs/sync-tab-id nil
         :settings/debug-logging false
         :settings/default-nrepl-port "3339" ; Default nREPL port for new hostnames
         :settings/default-ws-port "3340"    ; Default WebSocket port for new hostnames
         :permissions/host-granted? true ; Assume granted (Chrome default), check on init
         :ui/system-banners []          ; System banners [{:id :type :message :leaving} ...]
         :ui/system-bulk-names {}      ; bulk-id -> [script-name ...]
         :ui/page-banner nil           ; Page-level banner (e.g., unscriptable page)
         :ui/recently-modified-scripts #{} ; Scripts modified via REPL FS sync
         :browser/type :chrome         ; Detected browser type
         :sponsor/status false
         :sponsor/checked-at nil
         :sponsor/sponsored-username "PEZ"
         :repl/connections []         ; Source of truth for connections
         :runtime/errors {}           ; {script-name -> error-envelope} for current tab
         ;; Shadow lists for rendering with animation state
         ;; Shape: [{:item <original> :ui/entering? bool :ui/leaving? bool}]
         :ui/scripts-shadow []
         ;; List watchers: compare source to shadow, trigger sync actions
         :uf/list-watchers {:scripts/list {:id-fn :script/id
                                           :shadow-path :ui/scripts-shadow
                                           :on-change :ui/ax.sync-scripts-shadow}}}))



(defn generate-server-cmd [{:ports/keys [nrepl ws]}]
  (popup-utils/generate-server-cmd {:deps-string (.-depsString config)
                                    :nrepl-port nrepl
                                    :ws-port ws}))

;; ============================================================
;; Uniflow Dispatch
;; ============================================================

(def ^:private effect-router
  {:popup/fx.save-ports port-effects/save-ports!
   :popup/fx.clear-domain-ports port-effects/clear-domain-ports!
   :popup/fx.load-saved-ports port-effects/load-saved-ports!
   :popup/fx.init-ports port-effects/init-ports!
   :popup/fx.load-default-ports-setting port-effects/load-default-ports-setting!
   :popup/fx.save-default-ports-setting port-effects/save-default-ports-setting!
   :popup/fx.run-port-migration port-effects/run-port-migration!
   :popup/fx.remove-storage-keys port-effects/remove-storage-keys!
   :popup/fx.set-storage-key port-effects/set-storage-key!
   :popup/fx.connect connection-effects/connect!
   :popup/fx.check-status connection-effects/check-status!
   :popup/fx.disconnect-tab connection-effects/disconnect-tab!
   :popup/fx.load-current-url connection-effects/load-current-url!
   :popup/fx.load-connections connection-effects/load-connections!
   :popup/fx.load-runtime-status connection-effects/load-runtime-status!
   :popup/fx.load-scripts script-effects/load-scripts!
   :popup/fx.toggle-script script-effects/toggle-script!
   :popup/fx.delete-script script-effects/delete-script!
   :popup/fx.inspect-script script-effects/inspect-script!
   :popup/fx.evaluate-script script-effects/evaluate-script!
   :popup/fx.export-scripts script-effects/export-scripts!
   :popup/fx.trigger-import script-effects/trigger-import!
   :popup/fx.import-scripts script-effects/import-scripts!
   :popup/fx.load-auto-connect-level settings-effects/load-auto-connect-level!
   :popup/fx.save-auto-connect-level settings-effects/save-auto-connect-level!
   :popup/fx.load-auto-reconnect-setting settings-effects/load-auto-reconnect-setting!
   :popup/fx.save-auto-reconnect-setting settings-effects/save-auto-reconnect-setting!
   :popup/fx.load-fs-sync-status settings-effects/load-fs-sync-status!
   :popup/fx.toggle-fs-sync settings-effects/toggle-fs-sync!
   :popup/fx.load-debug-logging-setting settings-effects/load-debug-logging-setting!
   :popup/fx.save-debug-logging-setting settings-effects/save-debug-logging-setting!
   :popup/fx.check-sponsor sponsor-effects/check-sponsor!
   :popup/fx.load-sponsor-status sponsor-effects/load-sponsor-status!
   :popup/fx.set-dev-sponsor-username sponsor-effects/set-dev-sponsor-username!
   :popup/fx.reset-sponsor-status sponsor-effects/reset-sponsor-status!
   :popup/fx.load-dev-sponsor-username sponsor-effects/load-dev-sponsor-username!
   :popup/fx.copy-command ui-effects/copy-command!
   :popup/fx.reveal-script ui-effects/reveal-script!
   :popup/fx.reveal-tab ui-effects/reveal-tab!
   :popup/fx.dump-dev-log ui-effects/dump-dev-log!
   :popup/fx.log-system-banner ui-effects/log-system-banner!
   :popup/fx.check-page-scriptability ui-effects/check-page-scriptability!
   :popup/fx.check-host-permission ui-effects/check-host-permission!
   :popup/fx.request-host-permission ui-effects/request-host-permission!})

(defn ^:async perform-effect! [dispatch [effect & args]]
  (let [handler (get effect-router effect)]
    (if handler
      (js-await (apply handler dispatch args))
      (case effect
        :uf/fx.defer-dispatch
        (let [[actions timeout] args]
          (js/setTimeout #(dispatch actions) timeout))
        :uf/unhandled-fx))))

(defn- make-uf-data []
  {:config/deps-string (.-depsString config)})

(defn dispatch! [actions]
  (event-handler/dispatch! !state popup-actions/handle-action perform-effect! actions (make-uf-data)))

(defn port-input [{:keys [id label value on-change]}]
  [:span
   [:label {:for id} label]
   [:input {:type "number"
            :id id
            :value value
            :min "1"
            :max "65535"
            :on-input (fn [e]
                        (on-change (.. e -target -value)))}]])

(defn permission-banner []
  [:div.permission-banner.warning-banner
   [:div.permission-banner-content
    [:span "Epupp needs host permission to auto-run scripts on web pages."]
    [view-elements/action-button
     {:button/variant :primary
      :button/class "grant-permission-btn"
      :button/size :sm
      :button/on-click #(dispatch! [[:popup/ax.request-host-permission]])}
     "Grant Permission"]]])

(defn command-box [{:keys [command]}]
  [:div.command-box
   [:code command]
   [view-elements/icon-button
    {:button/icon icons/copy
     :button/title "Copy browser-nrepl server command line. (You need Babashka to run it)"
     :button/on-click #(dispatch! [[:popup/ax.copy-command]])}]])

(defn collapsible-section [{:keys [id title expanded? badge-count max-height data-attrs]} & children]
  [:div.collapsible-section (merge {:class (when-not expanded? "collapsed")
                                    :data-e2e-section id
                                    :data-e2e-expanded (boolean expanded?)}
                                   data-attrs)
   [:div.section-header {:on-click #(dispatch! [[:popup/ax.toggle-section id]])}
    [icons/chevron-right {:class (str "chevron " (when expanded? "expanded"))}]
    [:span.section-title title]
    (when (and badge-count (pos? badge-count))
      [:span.section-badge badge-count])]
   (into [:div.section-content {:style (when (and expanded? max-height) {:max-height max-height})}] children)])

(defn dev-tools-section
  "Dev tools: sponsor username, reset sponsor status, dump dev log.
   Only visible in dev/test builds."
  [{:sponsor/keys [sponsored-username]}]
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

(defn- reconnect-toggle [state prefix auto-connect-active?]
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

(defn- auto-connect-toggle [auto-connect-level prefix]
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

(defn- fs-sync-toggle [state prefix]
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

(defn- repl-settings-toggles
  "Returns a vector of three REPL setting toggle elements.
   Use `into` to splice into a parent container.
   id-prefix differentiates duplicate instances in the DOM."
  [state {:keys [id-prefix]}]
  (let [{:settings/keys [auto-connect-level]} state
        prefix (or id-prefix "")
        auto-connect-active? (not= auto-connect-level "off")]
    [[reconnect-toggle state prefix auto-connect-active?]
     [auto-connect-toggle auto-connect-level prefix]
     [fs-sync-toggle state prefix]]))

(defn settings-content [{:settings/keys [debug-logging] :as state}]
  [:div.settings-content
   (into
    [:div.settings-section
     [:h3.settings-section-title "REPL Connection"]
     [:p.section-description
      "Default ports (for hostnames without saved ports)."]
     [:div.port-row
      [port-input {:id "default-nrepl-port"
                   :label "nREPL:"
                   :value (:settings/default-nrepl-port state)
                   :on-change #(dispatch! [[:popup/ax.set-default-nrepl-port %]])}]
      [port-input {:id "default-ws-port"
                   :label "WebSocket:"
                   :value (:settings/default-ws-port state)
                   :on-change #(dispatch! [[:popup/ax.set-default-ws-port %]])}]]]
    (repl-settings-toggles state {}))
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

(defn connected-tab-item [{:keys [tab-id port title url favicon is-current-tab]}]
  [:div.connected-tab-item (merge {:class (str (when is-current-tab "current-tab ")
                                               (when-not is-current-tab "clickable "))
                                   :title (str title (when url (str "\n" url)))}
                                  (when-not is-current-tab
                                    {:on-click #(dispatch! [[:popup/ax.reveal-tab tab-id]])}))
   (when favicon
     [:img.connected-tab-favicon {:src favicon :width 16 :height 16}])
   [:span.connected-tab-title (or title "Unknown")]
   [:span.connected-tab-port (str ":" port)]
   [view-elements/action-button
    {:button/variant :danger
     :button/class "disconnect-tab-btn"
     :button/size :sm
     :button/icon icons/debug-disconnect
     :button/title "Disconnect this tab"
     :button/on-click (fn [e]
                        (.stopPropagation e)
                        (dispatch! [[:popup/ax.disconnect-tab tab-id]]))}
    nil]])

(defn connected-tabs-section [{:repl/keys [connections] :scripts/keys [current-tab-id]}]
  [:div.connected-tabs-section
   [:h2 "Connected tabs"]
   (if (seq connections)
     (let [current-tab-id-str (str current-tab-id)
           sorted-connections (sort-by
                               (fn [conn]
                                 (if (= (:tab-id conn) current-tab-id-str) 0 1))
                               connections)]
       [:div.connected-tabs-list
        (for [{:keys [tab-id] :as conn} sorted-connections]
          ^{:key tab-id}
          [connected-tab-item (assoc conn :is-current-tab (= tab-id current-tab-id-str))])])
     [view-elements/empty-state {:empty/class "no-connections"}
      "No REPL connections active"
      [:div.no-connections-hint
       "Start the server (Step 1), then click Connect (Step 2)."]])])

(defn- connect-controls [state ws]
  (if (:ui/connecting? state)
    [:div.connect-row.connecting
     [:span.connect-status
      (str "Waiting for server on :" ws "...")]
     [view-elements/action-button
      {:button/variant :secondary
       :button/id "cancel-connect"
       :button/title "Cancel connection"
       :button/on-click (fn [_e]
                          (set! (.-cancelled connection-effects/connect-cancel-signal) true)
                          (dispatch! [[:popup/ax.cancel-connect]
                                      [:popup/ax.show-system-banner "info" "Connection cancelled" {} "connection"]]))}
      "Cancel"]]
    [:div.connect-row
     [:span.connect-target (str "ws://localhost:" ws)]
     [view-elements/action-button
      {:button/variant :primary
       :button/id "connect"
       :button/title "Connect this tab to the REPL server"
       :button/on-click #(dispatch! [[:popup/ax.connect]])}
      "Connect"]]))

(defn- direct-connect-mode [{:as state} ws]
  [:div
   [:div.connect-mode-hint "Connect a scittle.nrepl compliant REPL Client (such as Calva)"]
   [:div.port-row
    [port-input {:id "ws-port"
                 :label "WebSocket:"
                 :value ws
                 :on-change #(dispatch! [[:popup/ax.set-ws-port %]])}]]
   [connect-controls state ws]])

(defn- relay-connect-mode [{:ports/keys [nrepl ws] :as state}]
  [:div
   [:div.connect-mode-hint "For REPOL clients/editors without built-in scittle.nrepl support"]
   [:div.step
    [:div.step-header "1. Start the browser-nrepl relay"]
    [:div.port-row
     [port-input {:id "nrepl-port"
                  :label "nREPL:"
                  :value nrepl
                  :on-change #(dispatch! [[:popup/ax.set-nrepl-port %]])}]
     [port-input {:id "ws-port"
                  :label "WebSocket:"
                  :value ws
                  :on-change #(dispatch! [[:popup/ax.set-ws-port %]])}]]
    [command-box {:command (generate-server-cmd state)}]]
   [:div.step
    [:div.step-header "2. Connect browser to relay"]
    [connect-controls state ws]]
   [:div.step
    [:div.step-header "3. Connect editor to relay"]
    [:div.connect-row
     [:span.connect-target (str "nrepl://localhost:" nrepl)]]]])

(defn- connect-mode-toggle [direct?]
  [:div.connect-mode-toggle
   [:button {:class (when direct? "active")
             :on-click (when-not direct?
                         #(dispatch! [[:popup/ax.set-connect-mode "direct"]]))}
    "Direct"]
   [:button {:class (when-not direct? "active")
             :on-click (when direct?
                         #(dispatch! [[:popup/ax.set-connect-mode "relay"]]))}
    "Relay"]])

(defn repl-connect-content
  [{:ports/keys [ws] :as state}]
  (let [is-connected (popup-utils/current-tab-connected? state)
        mode (or (:ui/connect-mode state) "direct")
        direct? (= mode "direct")]
    [:div
     [:div.connect-setup {:class (when is-connected "connected")}
      [:div.connect-setup-inner
       [connect-mode-toggle direct?]
       (if direct?
         [direct-connect-mode state ws]
         [relay-connect-mode state])]]
     (into [:div.connected-repl-settings {:class (when is-connected "visible")}]
           (repl-settings-toggles state {:id-prefix "connect-"}))
     [connected-tabs-section state]]))

(defn- scripts-section [state {:keys [id title scripts component]}]
  [collapsible-section {:id id
                        :title title
                        :expanded? (not (get (:ui/sections-collapsed state) id))
                        :badge-count (count scripts)
                        :max-height (str (+ 50 (* 105 (max 1 (count scripts)))) "px")}
   [component state]])

(defn- popup-header [state]
  [view-elements/app-header
   {:elements/wrapper-class "popup-header-wrapper"
    :elements/header-class "popup-header"
    :elements/icon [icons/epupp-logo {:size 28 :connected? (popup-utils/current-tab-connected? state)}]
    :elements/sponsor-status (storage/sponsor-active? state)
    :elements/on-sponsor-click #(dispatch! [[:popup/ax.check-sponsor]])
    :elements/permanent-banner [:div
                                (when-not (:permissions/host-granted? state)
                                  [permission-banner])
                                (when-let [pb (:ui/page-banner state)]
                                  [view-elements/page-banner pb])]
    :elements/temporary-banner (when-let [banners (seq (:ui/system-banners state))]
                                 [view-elements/system-banners banners])}])

(defn- popup-footer [state]
  [view-elements/app-footer {:elements/wrapper-class "popup-footer"
                             :elements/sponsor-status (storage/sponsor-active? state)
                             :elements/on-sponsor-click #(dispatch! [[:popup/ax.check-sponsor]])
                             :elements/creator-menu-open? (:ui/creator-menu-open? state)
                             :elements/on-creator-trigger-click #(dispatch! [[:popup/ax.toggle-creator-menu]])
                             :elements/on-creator-menu-close #(dispatch! [[:popup/ax.close-creator-menu]])}])

(defn popup-ui [{:ui/keys [sections-collapsed]
                 :scripts/keys [list current-url]
                 :repl/keys [connections]
                 :as state}]
  (let [{:keys [special matching other-autorun manual library]} (popup-scripts/categorize-scripts list current-url)
        settings-max-height 700]
    [:div
     [popup-header state]
     [collapsible-section {:id :repl-connect
                           :title "REPL Connect"
                           :expanded? (not (:repl-connect sections-collapsed))
                           :max-height (str (+ (if (popup-utils/current-tab-connected? state) 400 500)
                                               (* 35 (count connections))) "px")
                           :data-attrs {:data-e2e-connection-count (count connections)}}
      [repl-connect-content state]]
     [scripts-section state {:id :manual-scripts :title "Manual/On-demand scripts" :scripts manual :component popup-scripts/manual-scripts-section}]
     [scripts-section state {:id :matching-scripts :title "Auto-run for this page" :scripts matching :component popup-scripts/matching-scripts-section}]
     [scripts-section state {:id :other-scripts :title "Auto-run not matching this page" :scripts other-autorun :component popup-scripts/other-scripts-section}]
     [scripts-section state {:id :libraries :title "Libraries" :scripts library :component popup-scripts/libraries-section}]
     (when (seq special)
       [scripts-section state {:id :special :title "Special" :scripts special :component popup-scripts/special-scripts-section}])
     [collapsible-section {:id :settings
                           :title "Settings"
                           :expanded? (not (:settings sections-collapsed))
                           :max-height (str settings-max-height "px")}
      [settings-content state]]
     (when (or (.-dev config) (.-test config))
       [collapsible-section {:id :dev-tools
                             :title "Dev Tools"
                             :expanded? (not (:dev-tools sections-collapsed))}
        [dev-tools-section state]])
     [popup-footer state]]))

(defn render! []
  (r/render (js/document.getElementById "app")
            [popup-ui @!state]))

(defn- handle-runtime-message [message _sender _send-response]
  (case (.-type message)
    "connections-changed"
    (dispatch! [[:db/ax.assoc :repl/connections (.-connections message)]])
    "fs-sync-status-changed"
    (dispatch! [[:db/ax.assoc :fs/sync-tab-id (.-fsSyncTabId message)]])
    "runtime-status"
    (dispatch! [[:popup/ax.handle-runtime-status
                 {:tab-id (aget message "tab-id")
                  :errors (aget message "errors")}]])
    nil)
  false)

(defn- handle-system-banner-message [message _sender _send-response]
  (when (= "system-banner" (.-type message))
    (dispatch! [[:popup/ax.handle-system-banner
                 {:event-type (aget message "event-type")
                  :operation (aget message "operation")
                  :script-name (aget message "script-name")
                  :error (aget message "error")
                  :unchanged (aget message "unchanged")
                  :bulk-id (aget message "bulk-id")
                  :bulk-count (aget message "bulk-count")
                  :bulk-index (aget message "bulk-index")}]]))
  false)

(defn- notify-scripts-modified! [old-scripts new-scripts]
  (when (and old-scripts new-scripts)
    (let [{:keys [added modified]} (script-utils/diff-scripts old-scripts new-scripts)
          changed-names (concat added modified)]
      (when (seq changed-names)
        (dispatch! [[:popup/ax.mark-scripts-modified (vec changed-names)]])))))

(defn- handle-scripts-storage-change [changes area]
  (when (and (= area "local") (.-scripts changes))
    (let [scripts-change (.-scripts changes)
          old-scripts (when (.-oldValue scripts-change)
                        (script-utils/parse-scripts (.-oldValue scripts-change) {:extract-manifest mp/extract-manifest}))
          new-scripts (when (.-newValue scripts-change)
                        (script-utils/parse-scripts (.-newValue scripts-change) {:extract-manifest mp/extract-manifest}))]
      (dispatch! [[:popup/ax.load-scripts]
                  [:popup/ax.load-runtime-status]])
      (notify-scripts-modified! old-scripts new-scripts))))

(defn- handle-sponsor-storage-change [changes area]
  (when (= area "local")
    (let [status-change (.-sponsorStatus changes)
          checked-change (.-sponsorCheckedAt changes)]
      (when (or status-change checked-change)
        (dispatch! (cond-> []
                     status-change
                     (conj [:db/ax.assoc :sponsor/status (boolean (.-newValue status-change))])
                     checked-change
                     (conj [:db/ax.assoc :sponsor/checked-at (.-newValue checked-change)])))))))

(defn- handle-default-ports-change [changes area]
  (when (popup-utils/default-ports-changed? changes area)
    (.then (popup-utils/get-active-tab)
           (fn [tab]
             (let [key (port-effects/storage-key tab)]
               (js/chrome.storage.local.get
                #js ["defaultNreplPort" "defaultWsPort" key]
                (fn [result]
                  (let [new-defaults (popup-utils/parse-new-defaults result)
                        domain-ports (popup-utils/parse-domain-ports (aget result key))]
                    (dispatch! [[:popup/ax.on-default-ports-changed new-defaults domain-ports]])))))))))

(defn init! []
  (log/info "Popup" "Init!")
  (popup-scripts/set-dispatch! dispatch!)
  (test-logger/install-global-error-handlers! "popup" js/window)
  (add-watch !state :popup/render (fn [_ _ _ _] (render!)))
  (dispatch! [[:popup/ax.set-brave-detected (some? (.-brave js/navigator))]])
  (render!)
  (js/requestAnimationFrame
   (fn [] (js/requestAnimationFrame
           (fn [] (.add (.-classList js/document.body) "ready")))))
  (js/chrome.runtime.onMessage.addListener handle-runtime-message)
  (js/chrome.runtime.onMessage.addListener handle-system-banner-message)
  (js/chrome.storage.onChanged.addListener handle-scripts-storage-change)
  (js/chrome.storage.onChanged.addListener handle-sponsor-storage-change)
  (js/chrome.storage.onChanged.addListener handle-default-ports-change)
  (dispatch! [[:popup/ax.init-ports]
              [:popup/ax.check-status]
              [:popup/ax.load-scripts]
              [:popup/ax.load-current-url]
              [:popup/ax.check-page-scriptability]
              [:popup/ax.load-auto-connect-level]
              [:popup/ax.load-auto-reconnect-setting]
              [:popup/ax.load-fs-sync-status]
              [:popup/ax.load-debug-logging-setting]
              [:popup/ax.load-connections]
              [:popup/ax.load-sponsor-status]
              [:popup/ax.load-dev-sponsor-username]
              [:popup/ax.check-host-permission]])
  (js/setTimeout #(dispatch! [[:popup/ax.run-port-migration]]) 1000))

;; Start the app when DOM is ready
(log/info "Popup" "Script loaded, readyState:" js/document.readyState)
(if (= "loading" js/document.readyState)
  (js/document.addEventListener "DOMContentLoaded" init!)
  (init!))
