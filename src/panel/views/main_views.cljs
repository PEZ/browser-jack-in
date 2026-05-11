(ns panel.views.main-views
  (:require [icons :as icons]
            [view-elements :as view-elements]
            [storage :as storage]
            [test-logger :as test-logger]
            [panel.views.editor-views :as editor-views]
            [panel.views.script-views :as script-views]))

(defn- refresh-banner []
  [:div.banner.page-banner.warning-banner
   [:span "Extension updated - please "]
   [:strong "close and reopen DevTools"]
   [:span " to use the new version of this panel"]])

(defn- panel-header [dispatch! {:panel/keys [needs-refresh? system-banners tab-connected? page-banner]
                                :as state}]
  [view-elements/app-header
   {:elements/wrapper-class "panel-header-wrapper"
    :elements/header-class "panel-header"
    :elements/icon [icons/epupp-logo {:size 28 :connected? tab-connected?}]
    :elements/sponsor-status (storage/sponsor-active? state)
    :elements/on-sponsor-click #(dispatch! [[:editor/ax.check-sponsor]])
    :elements/permanent-banner
    (let [page-pb page-banner]
      (cond
        (and needs-refresh? page-pb)
        [:<> [refresh-banner] [view-elements/page-banner page-pb]]
        needs-refresh? [refresh-banner]
        page-pb [view-elements/page-banner page-pb]))
    :elements/temporary-banner (when (seq system-banners)
                                 [view-elements/system-banners system-banners])}])

(defn- panel-footer [dispatch! state]
  [view-elements/app-footer {:elements/wrapper-class "panel-footer"
                             :elements/sponsor-status (storage/sponsor-active? state)
                             :elements/on-sponsor-click #(dispatch! [[:editor/ax.check-sponsor]])
                             :elements/creator-menu-open? (:ui/creator-menu-open? state)
                             :elements/on-creator-trigger-click #(dispatch! [[:editor/ax.toggle-creator-menu]])
                             :elements/on-creator-menu-close #(dispatch! [[:editor/ax.close-creator-menu]])}])

(defn panel-ui [dispatch! state]
  [:div.panel-root {:data-e2e-connected (str (boolean (:panel/tab-connected? state)))}
   [panel-header dispatch! state]
   [:div.panel-content
    (when (test-logger/test-mode?)
      [:div#debug-info {:style {:position "absolute" :left "-9999px"}}
       "hostname: " (:panel/current-hostname state)
       " | code-len: " (count (:panel/code state))
       " | original-name: " (or (:panel/original-name state) "nil")])
    (when-let [error (get (:runtime/errors state) (:panel/script-name state))]
      [:div.panel-runtime-error {:data-e2e "panel-script-error"}
       [icons/warning {:size 14}]
       [:span (:error/message error)]])
    [script-views/save-script-section dispatch! state]
    [editor-views/code-input dispatch! state]
    [editor-views/results-area state]
    [panel-footer dispatch! state]]])
