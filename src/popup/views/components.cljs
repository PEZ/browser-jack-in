(ns popup.views.components
  (:require [icons :as icons]
            [view-elements :as view-elements]))

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

(defn permission-banner [dispatch!]
  [:div.permission-banner.warning-banner
   [:div.permission-banner-content
    [:span "Epupp needs host permission to auto-run scripts on web pages."]
    [view-elements/action-button
     {:button/variant :primary
      :button/class "grant-permission-btn"
      :button/size :sm
      :button/on-click #(dispatch! [[:popup/ax.request-host-permission]])}
     "Grant Permission"]]])

(defn command-box [dispatch! {:keys [command]}]
  [:div.command-box
   [:code command]
   [view-elements/icon-button
    {:button/icon icons/copy
     :button/title "Copy browser-nrepl server command line. (You need Babashka to run it)"
     :button/on-click #(dispatch! [[:popup/ax.copy-command]])}]])

(defn collapsible-section [dispatch! {:keys [id title expanded? badge-count max-height data-attrs]} & children]
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
