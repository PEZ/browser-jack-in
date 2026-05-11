(ns url-matching
  "URL pattern matching for userscripts.
   Supports TamperMonkey/Greasemonkey-style match patterns.

   Pattern syntax:
   - *://example.com/* - matches any scheme (http/https)
   - https://*.example.com/* - matches any subdomain
   - https://example.com/path/* - matches path prefix
   - <all_urls> - matches all URLs

   Note: * matches any characters, ? is literal (not a wildcard)."
  (:require [storage :as storage]))

(defn- escape-regex
  "Escape special regex characters except * which we handle specially"
  [s]
  (.replace s (js/RegExp. "[.+?^${}()|[\\]\\\\]" "g") "\\$&"))

(defn pattern->regex
  "Convert a match pattern to a RegExp.
   - '*://github.com/*' -> matches http://github.com/... and https://github.com/...
   - 'https://*.example.com/*' -> matches any subdomain
   - '<all_urls>' -> matches everything"
  [pattern]
  (cond
    (= pattern "<all_urls>")
    (js/RegExp. "^https?://.*$")

    :else
    (let [escaped (escape-regex pattern)
          with-wildcards (.replace escaped (js/RegExp. "\\*" "g") ".*")]
      (js/RegExp. (str "^" with-wildcards "$")))))

(defn url-matches-pattern?
  "Check if a URL matches a single pattern.
   Returns false for invalid (non-string) patterns."
  [url pattern]
  (if (string? pattern)
    (let [regex (pattern->regex pattern)]
      (.test regex url))
    false))

(defn url-matches-any-pattern?
  "Check if a URL matches any pattern in the list"
  [url patterns]
  (some #(url-matches-pattern? url %) patterns))

(defn get-matching-pattern
  "Find which pattern in a script matches the given URL.
   Returns the first matching pattern, or nil."
  [url script]
  (when url
    (->> (:script/match script)
         (filter #(url-matches-pattern? url %))
         first)))

(defn get-required-origins
  "Extract unique origin patterns from a list of scripts.
   Used to determine which permissions need to be requested."
  [scripts]
  (->> scripts
       (mapcat :script/match)
       distinct
       vec))

(defn url-to-match-pattern
  "Convert a URL to a match pattern string.
   Options:
   - :wildcard-scheme? - Use *:// instead of specific protocol (default: false)"
  ([url] (url-to-match-pattern url {}))
  ([url {:keys [wildcard-scheme?] :or {wildcard-scheme? false}}]
   (try
     (let [parsed (js/URL. url)
           scheme (if wildcard-scheme? "*" (.-protocol parsed))
           sep (if wildcard-scheme? "://" "//")]
       (str scheme sep (.-hostname parsed) "/*"))
     (catch :default _ nil))))

(defn get-matching-scripts
  "Get all enabled scripts that match the given URL"
  [url]
  (->> (storage/get-scripts)
       (filter :script/enabled)
       (filter #(url-matches-any-pattern? url (:script/match %)))
       vec))

;; Debug: Expose for console testing
(set! js/globalThis.urlMatching
      #js {:pattern_to_regex pattern->regex
           :url_matches_pattern_QMARK_ url-matches-pattern?
           :url_matches_any_pattern_QMARK_ url-matches-any-pattern?
           :get_matching_scripts get-matching-scripts
           :get_matching_pattern get-matching-pattern
           :get_required_origins get-required-origins
           :url_to_match_pattern url-to-match-pattern})
