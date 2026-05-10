(ns background-effects.ext-dep-effects
  (:require [ext-dep :as ext-dep]
            [manifest-parser :as manifest-parser]))

(defn- ^:async fetch-text!
  [url]
  (let [resp (js-await (js/fetch url))]
    (when-not (.-ok resp)
      (throw (js/Error. (str "Failed to fetch " url " (" (.-status resp) ")"))))
    (js-await (.text resp))))

(defn ^:async perform-effect! [_dispatch! effect args]
  (case effect
    :ext-dep/fx.fetch-deps
    (let [[uncached-urls existing-cache] args]
      (js-await (ext-dep/resolve-and-fetch!
                 {:inject-urls uncached-urls
                  :ext-dep-cache existing-cache
                  :fetch-fn fetch-text!
                  :parse-manifest-fn manifest-parser/extract-manifest
                  :now (.now js/Date)})))))
