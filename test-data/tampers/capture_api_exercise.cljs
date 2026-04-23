(ns tampers.capture-api-exercise
  (:require [epupp.tools :as tools]))

(comment
  ;; ===== VIEWPORT CAPTURE =====

  ;; Capture the full visible viewport (simplest test)
  (defn ^:async capture-viewport []
    (let [result (await (tools/capture-visible))]
      (def viewport-result result)))
  (capture-viewport)

  ;; Capture viewport as PNG (caution: PNG data URLs are much larger)
  (defn ^:async capture-viewport-png []
    (let [result (await (tools/capture-visible :format "png"))]
      (def viewport-png-result result)
      result))
  (capture-viewport-png)

  ;; ===== SELECTOR CAPTURE =====

  ;; Capture by CSS selector
  (defn ^:async capture-nav []
    (try
      (let [result (await (tools/capture-selector "nav"))]
        (def nav-result result))
      (catch :default e (def nav-error (.-message e)))))
  (capture-nav)

  ;; Capture by CSS selector - may throw if element scrolled out of viewport
  (defn ^:async capture-heading []
    (try
      (let [result (await (tools/capture-selector "h1"))]
        (def heading-result result))
      (catch :default e (def heading-error (.-message e)))))
  (capture-heading)
  ;; Verified: throws "element is not in the viewport" when h1 is scrolled off

  ;; Capture non-existent selector - should throw
  (defn ^:async capture-missing-selector []
    (try
      (let [result (await (tools/capture-selector "#does-not-exist-at-all"))]
        (def missing-result result))
      (catch :default e (def missing-error (.-message e)))))
  (capture-missing-selector)
  ;; Verified: throws "capture-selector: no element matches '#does-not-exist-at-all'"

  ;; ===== ELEMENT CAPTURE =====

  ;; Capture a specific element by reference
  (defn ^:async capture-element-simple [selector]
    (let [result (await (tools/capture-element (js/document.querySelector selector)))]
      (def el-result result)))
  (capture-element-simple "nav")

  ;; Check element dimensions and viewport visibility
  (defn element-info [selector]
    (when-let [el (js/document.querySelector selector)]
      (let [r (.getBoundingClientRect el)]
        {:tag (.-tagName el)
         :width (.-width r) :height (.-height r)
         :in-viewport? (and (< (.-top r) (.-innerHeight js/window))
                            (> (+ (.-top r) (.-height r)) 0)
                            (> (.-width r) 0) (> (.-height r) 0))})))
  (element-info "nav")

  ;; Capture a specific element with error handling
  (defn ^:async capture-element-safe [selector]
    (let [el (js/document.querySelector selector)]
      (if el
        (try
          (let [result (await (tools/capture-element el))]
            (def el-result result))
          (catch :default e (def el-error (.-message e))))
        (def el-error (str "No element matches '" selector "'"))))  )
  (capture-element-safe "nav")

  ;; Capture nil element - should throw
  (defn ^:async capture-nil []
    (try
      (let [result (await (tools/capture-element nil))]
        (def nil-result result))
      (catch :default e (def nil-error (.-message e)))))
  (capture-nil)
  ;; Verified: throws "capture-element: element must not be nil"

  ;; ===== RESULT INSPECTION =====

  ;; Check a result's data URL prefix and size
  ;; Evaluate after running one of the captures above
  (when-let [viewport-result-var (resolve 'viewport-result)]
    (let [viewport-result @viewport-result-var]
      {:success (:success viewport-result)
       :format (when-let [url (:dataUrl viewport-result)]
                 (re-find #"data:image/\w+" url))
       :data-length (count (:dataUrl viewport-result))}))

  ;; Quick preview - create an img element from a capture result
  (defn preview-capture! [result]
    (when (:success result)
      (let [img (js/document.createElement "img")]
        (set! (.-src img) (:dataUrl result))
        (set! (.. img -style -cssText)
              "position:fixed;top:10px;right:10px;z-index:99999;max-width:300px;max-height:200px;border:2px solid #5881d8;border-radius:8px;box-shadow:0 4px 12px rgba(0,0,0,0.3);")
        (.appendChild js/document.body img)
        img)))

  ;; Preview the viewport capture
  (when-let [viewport-result-var (resolve 'viewport-result)]
    (preview-capture! @viewport-result-var))

  ;; Remove all preview images
  (doseq [img (js/document.querySelectorAll "img[style*='z-index:99999']")]
    (.remove img))

  :rcf)
