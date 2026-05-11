(ns popup.views.connect-views
  (:require [icons :as icons]
            [view-elements :as view-elements]
            [popup.utils :as popup-utils]
            [popup.effects.connection-effects :as connection-effects]
            [popup.views.component-views :as components]
            [popup.views.settings-views :as settings]))

(def ^:private config js/EXTENSION_CONFIG)

(defn- generate-server-cmd [{:ports/keys [nrepl ws]}]
  (popup-utils/generate-server-cmd {:deps-string (.-depsString config)
                                    :nrepl-port nrepl
                                    :ws-port ws}))

(defn connected-tab-item [dispatch! {:keys [tab-id port title url favicon is-current-tab]}]
  [:div.connected-tab-item (merge {:class (str (when is-current-tab "current-tab ")
                                               (when-not is-current-tab "clickable "))
                                   :title (str title (when url (str "\n" url)))}
                                  (when-not is-current-tab
                                    {:on-click #(dispatch! [[:ui/ax.reveal-tab tab-id]])}))
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
                        (dispatch! [[:ui/ax.disconnect-tab tab-id]]))}
    nil]])

(defn connected-tabs-section [dispatch! {:repl/keys [connections] :scripts/keys [current-tab-id]}]
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
          [connected-tab-item dispatch! (assoc conn :is-current-tab (= tab-id current-tab-id-str))])])
     [view-elements/empty-state {:empty/class "no-connections"}
      "No REPL connections active"
      [:div.no-connections-hint
       "Start the server (Step 1), then click Connect (Step 2)."]])])

(defn- connect-controls [dispatch! state ws]
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
                          (dispatch! [[:connection/ax.cancel-connect]
                                      [:banner/ax.show-system-banner "info" "Connection cancelled" {} "connection"]]))}
      "Cancel"]]
    [:div.connect-row
     [:span.connect-target (str "ws://localhost:" ws)]
     [view-elements/action-button
      {:button/variant :primary
       :button/id "connect"
       :button/title "Connect this tab to the REPL server"
       :button/on-click #(dispatch! [[:connection/ax.connect]])}
      "Connect"]]))

(defn- direct-connect-mode [dispatch! state ws]
  [:div
   [:div.connect-mode-hint "Connect a scittle.nrepl compliant REPL Client (such as Calva)"]
   [:div.port-row
    [components/port-input {:id "ws-port"
                            :label "WebSocket:"
                            :value ws
                            :on-change #(dispatch! [[:connection/ax.set-ws-port %]])}]]
   [connect-controls dispatch! state ws]])

(defn- relay-connect-mode [dispatch! {:ports/keys [nrepl ws] :as state}]
  [:div
   [:div.connect-mode-hint "For REPOL clients/editors without built-in scittle.nrepl support"]
   [:div.step
    [:div.step-header "1. Start the browser-nrepl relay"]
    [:div.port-row
     [components/port-input {:id "nrepl-port"
                             :label "nREPL:"
                             :value nrepl
                             :on-change #(dispatch! [[:connection/ax.set-nrepl-port %]])}]
     [components/port-input {:id "ws-port"
                             :label "WebSocket:"
                             :value ws
                             :on-change #(dispatch! [[:connection/ax.set-ws-port %]])}]]
    [components/command-box dispatch! {:command (generate-server-cmd state)}]]
   [:div.step
    [:div.step-header "2. Connect browser to relay"]
    [connect-controls dispatch! state ws]]
   [:div.step
    [:div.step-header "3. Connect editor to relay"]
    [:div.connect-row
     [:span.connect-target (str "nrepl://localhost:" nrepl)]]]])

(defn- connect-mode-toggle [dispatch! direct?]
  [:div.connect-mode-toggle
   [:button {:class (when direct? "active")
             :on-click (when-not direct?
                         #(dispatch! [[:connection/ax.set-connect-mode "direct"]]))}
    "Direct"]
   [:button {:class (when-not direct? "active")
             :on-click (when direct?
                         #(dispatch! [[:connection/ax.set-connect-mode "relay"]]))}
    "Relay"]])

(defn repl-connect-content
  [dispatch! {:ports/keys [ws] :as state}]
  (let [is-connected (popup-utils/current-tab-connected? state)
        mode (or (:ui/connect-mode state) "direct")
        direct? (= mode "direct")]
    [:div
     [:div.connect-setup {:class (when is-connected "connected")}
      [:div.connect-setup-inner
       [connect-mode-toggle dispatch! direct?]
       (if direct?
         [direct-connect-mode dispatch! state ws]
         [relay-connect-mode dispatch! state])]]
     (into [:div.connected-repl-settings {:class (when is-connected "visible")}]
           (settings/repl-settings-toggles dispatch! state {:id-prefix "connect-"}))
     [connected-tabs-section dispatch! state]]))
