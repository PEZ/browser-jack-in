{:epupp/script-name "epupp/tools.cljs"
 :epupp/description "Element and viewport screenshot capture utilities."
 :epupp/inject ["epupp://epupp/internal/helpers.cljs"]
 :epupp/library? true}

(ns epupp.tools
  (:require [epupp.internal.helpers :as helpers]))

(defn ^:async capture-element
  "Capture a screenshot of a DOM element. Returns a promise resolving to
   {:success bool :dataUrl string :error string}.
   Options: :format (\"png\" or \"jpeg\", default \"png\"), :quality (0-100, for jpeg)."
  ([element] (capture-element element {}))
  ([element opts]
   (when-not element
     (throw (js/Error. "capture-element: element must not be nil")))
   (let [rect (.getBoundingClientRect element)
         w (.-width rect)
         h (.-height rect)]
     (when (or (<= w 0) (<= h 0))
       (throw (js/Error. "capture-element: element has zero dimensions")))
     (let [scroll-x (.-scrollX js/window)
           scroll-y (.-scrollY js/window)
           vw (.-innerWidth js/window)
           vh (.-innerHeight js/window)
           el-left (.-left rect)
           el-top (.-top rect)]
       (when (or (>= el-left vw) (>= el-top vh)
                 (<= (+ el-left w) 0) (<= (+ el-top h) 0))
         (throw (js/Error. "capture-element: element is not in the viewport")))
       (let [dpr (.-devicePixelRatio js/window)
             payload {:rect {:x el-left :y el-top :width w :height h}
                      :dpr dpr
                      :format (or (:format opts) "png")
                      :quality (or (:quality opts) 92)}
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
            :error "capture-element: timeout waiting for response"}))))))

(defn ^:async capture-selector
  "Capture a screenshot of the first element matching a CSS selector.
   Options: :format (\"png\" or \"jpeg\"), :quality (0-100, for jpeg)."
  ([selector] (capture-selector selector {}))
  ([selector opts]
   (let [element (js/document.querySelector selector)]
     (when-not element
       (throw (js/Error. (str "capture-selector: no element matches '" selector "'"))))
     (await (capture-element element opts)))))

(defn ^:async capture-visible
  "Capture a screenshot of the full visible viewport.
   Options: :format (\"png\" or \"jpeg\"), :quality (0-100, for jpeg)."
  ([] (capture-visible {}))
  ([opts]
   (let [payload {:format (or (:format opts) "png")
                  :quality (or (:quality opts) 92)}
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
        :error "capture-visible: timeout waiting for response"}))))