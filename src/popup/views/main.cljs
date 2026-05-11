(ns popup.views.main
  (:require [icons :as icons]
            [view-elements :as view-elements]
            [popup.utils :as popup-utils]
            [popup.views.scripts :as popup-scripts]
            [storage :as storage]
            [popup.views.components :as components]
            [popup.views.settings :as settings]
            [popup.views.connect :as connect]))

(def ^:private config js/EXTENSION_CONFIG)

(defn- scripts-section [dispatch! state {:keys [id title scripts component]}]
  [components/collapsible-section dispatch! {:id id
                                             :title title
                                             :expanded? (not (get (:ui/sections-collapsed state) id))
                                             :badge-count (count scripts)
                                             :max-height (str (+ 50 (* 105 (max 1 (count scripts)))) "px")}
   [component dispatch! state]])

(defn- popup-header [dispatch! state]
  [view-elements/app-header
   {:elements/wrapper-class "popup-header-wrapper"
    :elements/header-class "popup-header"
    :elements/icon [icons/epupp-logo {:size 28 :connected? (popup-utils/current-tab-connected? state)}]
    :elements/sponsor-status (storage/sponsor-active? state)
    :elements/on-sponsor-click #(dispatch! [[:popup/ax.check-sponsor]])
    :elements/permanent-banner [:div
                                (when-not (:permissions/host-granted? state)
                                  [components/permission-banner dispatch!])
                                (when-let [pb (:ui/page-banner state)]
                                  [view-elements/page-banner pb])]
    :elements/temporary-banner (when-let [banners (seq (:ui/system-banners state))]
                                 [view-elements/system-banners banners])}])

(defn- popup-footer [dispatch! state]
  [view-elements/app-footer {:elements/wrapper-class "popup-footer"
                             :elements/sponsor-status (storage/sponsor-active? state)
                             :elements/on-sponsor-click #(dispatch! [[:popup/ax.check-sponsor]])
                             :elements/creator-menu-open? (:ui/creator-menu-open? state)
                             :elements/on-creator-trigger-click #(dispatch! [[:popup/ax.toggle-creator-menu]])
                             :elements/on-creator-menu-close #(dispatch! [[:popup/ax.close-creator-menu]])}])

(defn popup-ui [dispatch! {:ui/keys [sections-collapsed]
                            :scripts/keys [list current-url]
                            :repl/keys [connections]
                            :as state}]
  (let [{:keys [special matching other-autorun manual library]} (popup-scripts/categorize-scripts list current-url)
        settings-max-height 700]
    [:div
     [popup-header dispatch! state]
     [components/collapsible-section dispatch! {:id :repl-connect
                                                :title "REPL Connect"
                                                :expanded? (not (:repl-connect sections-collapsed))
                                                :max-height (str (+ (if (popup-utils/current-tab-connected? state) 400 500)
                                                                    (* 35 (count connections))) "px")
                                                :data-attrs {:data-e2e-connection-count (count connections)}}
      [connect/repl-connect-content dispatch! state]]
     [scripts-section dispatch! state {:id :manual-scripts :title "Manual/On-demand scripts" :scripts manual :component popup-scripts/manual-scripts-section}]
     [scripts-section dispatch! state {:id :matching-scripts :title "Auto-run for this page" :scripts matching :component popup-scripts/matching-scripts-section}]
     [scripts-section dispatch! state {:id :other-scripts :title "Auto-run not matching this page" :scripts other-autorun :component popup-scripts/other-scripts-section}]
     [scripts-section dispatch! state {:id :libraries :title "Libraries" :scripts library :component popup-scripts/libraries-section}]
     (when (seq special)
       [scripts-section dispatch! state {:id :special :title "Special" :scripts special :component popup-scripts/special-scripts-section}])
     [components/collapsible-section dispatch! {:id :settings
                                                :title "Settings"
                                                :expanded? (not (:settings sections-collapsed))
                                                :max-height (str settings-max-height "px")}
      [settings/settings-content dispatch! state]]
     (when (or (.-dev config) (.-test config))
       [components/collapsible-section dispatch! {:id :dev-tools
                                                  :title "Dev Tools"
                                                  :expanded? (not (:dev-tools sections-collapsed))}
        [settings/dev-tools-section dispatch! state]])
     [popup-footer dispatch! state]]))
