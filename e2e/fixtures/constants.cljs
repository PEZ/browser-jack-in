(ns fixtures.constants
  "Port constants, extension path, and script counts for E2E tests."
  (:require ["path" :as path]
            ["url" :as url]))

(def ^:private __dirname
  (path/dirname (url/fileURLToPath js/import.meta.url)))

(def extension-path
  "Absolute path to the built extension directory (dist/chrome after bb build)."
  (path/resolve __dirname ".." ".." ".." "dist" "chrome"))

;; =============================================================================
;; Port Constants - Must match tasks.clj
;; =============================================================================

(def http-port 18080)

;; Two browser-nrepl servers for multi-tab testing
(def nrepl-port-1 12345)
(def ws-port-1 12346)
(def nrepl-port-2 12347)
(def ws-port-2 12348)

;; =============================================================================
;; Script Count Constants
;; =============================================================================

(def builtin-script-count
  "Number of built-in scripts in test/dev builds.
   Test/dev builds include internal helpers, ui, storage, web installer,
   sponsor, and security probe."
  6)
