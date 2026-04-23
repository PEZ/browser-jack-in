(ns epupp.tools
  (:require [epupp.internal.helpers :as helpers]))

(defn ^:async capture-element
  "Capture a screenshot of a DOM element. Returns a promise resolving to
   {:success bool :dataUrl string :error string}.
   Options: :format (\"jpeg\" or \"png\", default \"jpeg\"), :quality (0-100, default 75)."
  [element & {:keys [format quality]}]
  (when-not element
    (throw (js/Error. "capture-element: element must not be nil")))
  (let [rect (.getBoundingClientRect element)
        w (.-width rect)
        h (.-height rect)]
    (when (or (<= w 0) (<= h 0))
      (throw (js/Error. "capture-element: element has zero dimensions")))
    (let [vw (.-innerWidth js/window)
          vh (.-innerHeight js/window)
          el-left (.-left rect)
          el-top (.-top rect)]
      (when (or (>= el-left vw) (>= el-top vh)
                (<= (+ el-left w) 0) (<= (+ el-top h) 0))
        (throw (js/Error. "capture-element: element is not in the viewport")))
      (let [dpr (.-devicePixelRatio js/window)
            payload {:rect {:x el-left :y el-top :width w :height h}
                     :dpr dpr
                     :format (or format "jpeg")
                     :quality (or quality 75)}
            response (await (helpers/send-and-receive
                             "capture-element"
                             "capture-element-response"
                             payload
                             10000))]
        (if response
          {:success (.-success response)
           :dataUrl (.-dataUrl response)
           :error (.-error response)}
          {:success false
           :error "capture-element: timeout waiting for response"})))))

(defn ^:async capture-selector
  "Capture a screenshot of the first element matching a CSS selector.
   Options: :format (\"jpeg\" or \"png\", default \"jpeg\"), :quality (0-100, default 75)."
  [selector & {:keys [format quality]}]
  (let [element (js/document.querySelector selector)]
    (when-not element
      (throw (js/Error. (str "capture-selector: no element matches '" selector "'"))))
    (await (capture-element element :format format :quality quality))))

(defn ^:async capture-visible
  "Capture a screenshot of the full visible viewport.
   Options: :format (\"jpeg\" or \"png\", default \"jpeg\"), :quality (0-100, default 75)."
  [& {:keys [format quality]}]
  (let [payload {:format (or format "jpeg")
                 :quality (or quality 75)}
        response (await (helpers/send-and-receive
                         "capture-element"
                         "capture-element-response"
                         payload
                         10000))]
    (if response
      {:success (.-success response)
       :dataUrl (.-dataUrl response)
       :error (.-error response)}
      {:success false
       :error "capture-visible: timeout waiting for response"})))
