{:epupp/script-name "epupp/ui.cljs"
 :epupp/description "Epupp branding and UI components. Available for use in userscripts."
 :epupp/library? true}

(ns epupp.ui)

(defn epupp-icon
  [& {:keys [size] :or {size 36}}]
  (let [accent-color "#FFDC73"]
    [:svg {:width size
           :height size
           :viewBox "0 0 100 100"
           :fill :none
           :xmlns "http://www.w3.org/2000/svg"}
     [:path
      {:d
       "M50 0.999996C77.062 0.999996 99 22.938 99 50C99 77.0619 77.062 99 50 99C22.9381 99 1 77.0619 1 50C1 22.938 22.9381 0.999996 50 0.999996Z"
       :fill "#4A71C4"
       :stroke accent-color
       :stroke-width "2"}]
     [:path
      {:d
       "M34.9792 36.9613L75.3818 0.999997L15.0206 37.2308L44.6048 50.8483L23.4278 84.9488L85.5 67.5L48.8818 66L55.9177 47.6053L34.9792 36.9613Z"
       :fill accent-color}]]))

(defn copy-icon
  [& {:keys [size] :or {size 14}}]
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :width size :height size
         :viewBox "0 0 16 16"
         :fill "currentColor"}
   [:path {:d "M4 4L4 1.5C4 1.22386 4.22386 1 4.5 1H12.5C12.7761 1 13 1.22386 13 1.5V10.5C13 10.7761 12.7761 11 12.5 11H10V12H12.5C13.3284 12 14 11.3284 14 10.5V1.5C14 0.671573 13.3284 0 12.5 0H4.5C3.67157 0 3 0.671573 3 1.5V4H4ZM1 5.5C1 4.67157 1.67157 4 2.5 4H9.5C10.3284 4 11 4.67157 11 5.5V14.5C11 15.3284 10.3284 16 9.5 16H2.5C1.67157 16 1 15.3284 1 14.5V5.5ZM2.5 5C2.22386 5 2 5.22386 2 5.5V14.5C2 14.7761 2.22386 15 2.5 15H9.5C9.77614 15 10 14.7761 10 14.5V5.5C10 5.22386 9.77614 5 9.5 5H2.5Z"}]])

(defn epupp-header
  [& {:keys [size title tagline]
      :or {size 36
           title "Epupp"
           tagline "Live Tamper your Web"}}]
  (let [font-size (* size (/ 24 36))]
    [:div {:style {:font-size (str font-size "px")
                   :display "flex"
                   :align-items "center"
                   :gap "8px"}}
     (epupp-icon :size size)
     [:span {:style {:font-weight 500
                     :display "flex"
                     :align-items "baseline"}}
      title
      (when tagline
        [:span {:style {:font-size (str (* 0.75 font-size) "px")
                        :font-style "italic"
                        :font-weight 400
                        :margin-left "4px"}}
         tagline])]]))