(ns page-scriptability
  "Detection of whether a page URL is scriptable by the extension.
   Checks URL schemes and browser-specific blocked domains.")

(def blocked-schemes
  "URL schemes that block extension content script injection across all browsers."
  ["chrome:" "chrome-extension:" "chrome-search:" "chrome-untrusted:"
   "edge:" "brave:" "opera:" "vivaldi:" "arc:"
   "about:" "moz-extension:" "safari-web-extension:"
   "devtools:" "view-source:"])

(def blocked-domains-by-browser
  "HTTPS domains blocked per browser. Keys are browser type keywords,
   values are vectors of domain prefixes to match against the full URL."
  {:chrome  ["https://chrome.google.com/webstore"
             "https://chromewebstore.google.com"]
   :brave   ["https://chrome.google.com/webstore"
             "https://chromewebstore.google.com"]
   :edge    ["https://chrome.google.com/webstore"
             "https://chromewebstore.google.com"
             "https://microsoftedge.microsoft.com/addons"]
   :firefox ["https://addons.mozilla.org"]})

(defn detect-browser-type
  "Detect the browser type from the runtime environment.
   Returns :firefox, :brave, :edge, :safari, or :chrome.
   Checks in priority order: Firefox first since it has chrome compat layer."
  []
  (let [ua (.-userAgent js/navigator)]
    (cond
      (.includes ua "Firefox") :firefox
      (some? (.-brave js/navigator)) :brave
      (.includes ua "Edg/") :edge
      (and (.includes ua "Safari")
           (not (.includes ua "Chrome"))) :safari
      :else :chrome)))

(defn check-page-scriptability
  "Check if a page URL is scriptable by the extension.
   Pure function taking URL string and browser type keyword.
   Returns map with :scriptable? boolean and :message string (when not scriptable).

   Three blocking conditions checked in order:
   1. nil/empty URL
   2. Blocked URL schemes (e.g. chrome:, about:, devtools:)
   3. Browser-specific blocked domains (e.g. extension stores)"
  [url browser-type]
  (cond
    (or (nil? url) (= url ""))
    {:scriptable? false
     :message "No URL available for this tab"}

    (some #(.startsWith url %) blocked-schemes)
    {:scriptable? false
     :message (str "Unfortunatelly neither the REPL nor Userscripting works on " (first (.split url ":")) ": pages.")}

    (some #(.startsWith url %) (get blocked-domains-by-browser browser-type []))
    {:scriptable? false
     :message "Unfortunatelly neither the REPL nor Userscripting works on Extension/Addon stores."}

    :else
    {:scriptable? true}))
