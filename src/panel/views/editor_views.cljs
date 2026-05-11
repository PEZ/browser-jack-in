(ns panel.views.editor-views
  (:require [icons :as icons]
            [view-elements :as view-elements]))

(defn- eval-shortcut? [e]
  (and (or (.-ctrlKey e) (.-metaKey e))
       (= "Enter" (.-key e))))

(defn- result-item [{:keys [type text]}]
  (case type
    :input
    [:div.result-item.result-input
     [:div.result-label "Input"]
     text]

    :output
    [:div.result-item.result-output
     [:div.result-label "Result"]
     text]

    :error
    [:div.result-item.result-error
     [:div.result-label "Error"]
     text]))

(defn results-area [{:panel/keys [results]}]
  [:div.results-area
   (if (seq results)
     (let [indexed (map-indexed vector results)]
       (for [[idx result] (reverse indexed)]
         ^{:key idx}
         [result-item result]))
     [view-elements/empty-state {:empty/class "empty-results"}
      [:div.empty-results-text
       "Evaluate ClojureScript code above to see results here"]
      [:div.empty-results-shortcut
       "(" [:kbd "Ctrl"] "+" [:kbd "Enter"] " evals selection)"]])])

(defn- track-selection!
  "Track textarea selection and dispatch to state."
  [dispatch! textarea]
  (let [start (.-selectionStart textarea)
        end (.-selectionEnd textarea)
        text (when (and start end (not= start end))
               (.substring (.-value textarea) start end))]
    (dispatch! [[:editor/ax.set-selection
                 (when text {:start start :end end :text text})]])))

(defn code-input [dispatch! {:panel/keys [code evaluating? scittle-status]}]
  (let [loading? (= :loading scittle-status)]
    [:div.code-input-area {:data-e2e-scittle-status scittle-status}
     [:textarea#code-area {:value code
                           :rows 10
                           :placeholder "(+ 1 2 3)\n\n; Ctrl+Enter evaluates selection"
                           :disabled (or evaluating? loading?)
                           :on-input (fn [e] (dispatch! [[:editor/ax.set-code (.. e -target -value)]]))
                           :on-select (fn [e] (track-selection! dispatch! (.-target e)))
                           :on-click (fn [e] (track-selection! dispatch! (.-target e)))
                           :on-keyup (fn [e]
                                       (track-selection! dispatch! (.-target e)))
                           :on-keydown (fn [e]
                                         (when (eval-shortcut? e)
                                           (.preventDefault e)
                                           (dispatch! [[:editor/ax.eval-selection]])))}]
     [:div.code-actions
      [view-elements/action-button
       {:button/variant :primary
        :button/class "btn-eval"
        :button/icon icons/play
        :button/disabled? (or evaluating? loading? (empty? code))
        :button/on-click #(dispatch! [[:editor/ax.eval]])}
       (cond
         loading? " Loading Scittle..."
         evaluating? " Evaluating..."
         :else " Eval script")]
      [view-elements/action-button
       {:button/variant :secondary
        :button/class "btn-clear"
        :button/on-click #(dispatch! [[:editor/ax.clear-results]])}
       "Clear Results"]
      [:span.shortcut-hint [:kbd "Ctrl"] "+" [:kbd "Enter"] " evals selection"]]]))
