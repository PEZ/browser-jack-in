(ns tasks
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.data.json :as json]))

(defn bundle-scittle
  "Download Scittle and nREPL plugin to src/vendor"
  []
  (let [version "0.7.30"
        base-url "https://cdn.jsdelivr.net/npm/scittle@"
        vendor-dir "src/vendor"
        files [["scittle.js" (str base-url version "/dist/scittle.js")]
               ["scittle.nrepl.js" (str base-url version "/dist/scittle.nrepl.js")]]]
    (fs/create-dirs vendor-dir)
    (doseq [[filename url] files]
      (println "Downloading" filename "...")
      (let [response (http/get url)]
        (spit (str vendor-dir "/" filename) (:body response))))
    (println "✓ Scittle" version "bundled to" vendor-dir)))

(defn- adjust-manifest
  "Adjust manifest.json for specific browser"
  [manifest browser]
  (case browser
    "firefox" (assoc manifest
                     :browser_specific_settings
                     {:gecko {:id "dom-repl@example.com"
                              :strict_min_version "109.0"}})
    manifest))

(defn build
  "Build extension for specified browser(s)"
  [& browsers]
  (let [browsers (if (seq browsers) browsers ["chrome" "firefox" "safari"])
        src-dir "src"
        dist-dir "dist"]
    (fs/create-dirs dist-dir)
    (doseq [browser browsers]
      (println (str "Building for " browser "..."))
      (let [browser-dir (str dist-dir "/" browser)]
        ;; Clean and copy source
        (when (fs/exists? browser-dir)
          (fs/delete-tree browser-dir))
        (fs/copy-tree src-dir browser-dir)

        ;; Adjust manifest
        (let [manifest-path (str browser-dir "/manifest.json")
              manifest (json/read-str (slurp manifest-path) :key-fn keyword)
              adjusted (adjust-manifest manifest browser)]
          (spit manifest-path (json/write-str adjusted :indent true)))

        ;; Create zip
        (let [zip-path (str dist-dir "/dom-repl-" browser ".zip")]
          (fs/delete-if-exists zip-path)
          (fs/zip zip-path [browser-dir] {:root dist-dir})
          (println (str "  Created: " zip-path)))))
    (println "Build complete!")))
