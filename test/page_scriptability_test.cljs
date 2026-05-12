(ns page-scriptability-test
  "Tests for browser type detection and page scriptability checking."
  (:require ["vitest" :refer [describe test expect afterEach vi]]
            [page-scriptability :as page-scriptability]))

;; ============================================================
;; detect-browser-type tests
;; ============================================================

(defn- test-detect-browser-type-firefox []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0"})
  (-> (expect (page-scriptability/detect-browser-type))
      (.toBe :firefox)))

(defn- test-detect-browser-type-brave []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    :brave #js {}})
  (-> (expect (page-scriptability/detect-browser-type))
      (.toBe :brave)))

(defn- test-detect-browser-type-edge []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"})
  (-> (expect (page-scriptability/detect-browser-type))
      (.toBe :edge)))

(defn- test-detect-browser-type-safari []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"})
  (-> (expect (page-scriptability/detect-browser-type))
      (.toBe :safari)))

(defn- test-detect-browser-type-chrome []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"})
  (-> (expect (page-scriptability/detect-browser-type))
      (.toBe :chrome)))

(defn- test-detect-browser-type-firefox-priority-over-chrome []
  (.stubGlobal vi "navigator"
               #js {:userAgent "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0"
                    :brave nil})
  (-> (expect (page-scriptability/detect-browser-type))
      (.toBe :firefox)))

(describe "detect-browser-type"
  (fn []
    (afterEach (fn [] (.unstubAllGlobals vi)))
    (test "detects Firefox" test-detect-browser-type-firefox)
    (test "detects Brave" test-detect-browser-type-brave)
    (test "detects Edge" test-detect-browser-type-edge)
    (test "detects Safari" test-detect-browser-type-safari)
    (test "detects Chrome (default)" test-detect-browser-type-chrome)
    (test "Firefox has priority over chrome compat" test-detect-browser-type-firefox-priority-over-chrome)))

;; ============================================================
;; check-page-scriptability tests
;; ============================================================

(defn- test-scriptability-nil-url []
  (let [result (page-scriptability/check-page-scriptability nil :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "No URL"))))

(defn- test-scriptability-empty-url []
  (let [result (page-scriptability/check-page-scriptability "" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "No URL"))))

(defn- test-scriptability-normal-https-url []
  (let [result (page-scriptability/check-page-scriptability "https://example.com/page" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe true))
    (-> (expect (:message result)) (.toBeUndefined))))

(defn- test-scriptability-chrome-scheme []
  (let [result (page-scriptability/check-page-scriptability "chrome://settings" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "chrome"))))

(defn- test-scriptability-about-scheme []
  (let [result (page-scriptability/check-page-scriptability "about:blank" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "about"))))

(defn- test-scriptability-devtools-scheme []
  (let [result (page-scriptability/check-page-scriptability "devtools://devtools/bundled/inspector.html" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "devtools"))))

(defn- test-scriptability-moz-extension-scheme []
  (let [result (page-scriptability/check-page-scriptability "moz-extension://abc-123/popup.html" :firefox)]
    (-> (expect (:scriptable? result)) (.toBe false))))

(defn- test-scriptability-chrome-webstore-blocked []
  (let [result (page-scriptability/check-page-scriptability
                "https://chrome.google.com/webstore/detail/some-ext" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "stores"))))

(defn- test-scriptability-chromewebstore-blocked []
  (let [result (page-scriptability/check-page-scriptability
                "https://chromewebstore.google.com/detail/some-ext" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "stores"))))

(defn- test-scriptability-firefox-addons-blocked []
  (let [result (page-scriptability/check-page-scriptability
                "https://addons.mozilla.org/en-US/firefox/addon/some-addon" :firefox)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "stores"))))

(defn- test-scriptability-edge-addons-blocked []
  (let [result (page-scriptability/check-page-scriptability
                "https://microsoftedge.microsoft.com/addons/detail/some-ext" :edge)]
    (-> (expect (:scriptable? result)) (.toBe false))
    (-> (expect (:message result)) (.toContain "stores"))))

(defn- test-scriptability-firefox-domain-not-blocked-for-chrome []
  (let [result (page-scriptability/check-page-scriptability
                "https://addons.mozilla.org/en-US/firefox" :chrome)]
    (-> (expect (:scriptable? result)) (.toBe true))))

(defn- test-scriptability-edge-domain-not-blocked-for-firefox []
  (let [result (page-scriptability/check-page-scriptability
                "https://microsoftedge.microsoft.com/addons" :firefox)]
    (-> (expect (:scriptable? result)) (.toBe true))))

(describe "check-page-scriptability"
          (fn []
            (test "nil URL is not scriptable" test-scriptability-nil-url)
            (test "empty URL is not scriptable" test-scriptability-empty-url)
            (test "normal HTTPS URL is scriptable" test-scriptability-normal-https-url)
            (test "chrome: scheme is blocked" test-scriptability-chrome-scheme)
            (test "about: scheme is blocked" test-scriptability-about-scheme)
            (test "devtools: scheme is blocked" test-scriptability-devtools-scheme)
            (test "moz-extension: scheme is blocked" test-scriptability-moz-extension-scheme)
            (test "Chrome Web Store (old URL) blocked for Chrome" test-scriptability-chrome-webstore-blocked)
            (test "Chrome Web Store (new URL) blocked for Chrome" test-scriptability-chromewebstore-blocked)
            (test "Firefox Add-ons blocked for Firefox" test-scriptability-firefox-addons-blocked)
            (test "Edge Add-ons blocked for Edge" test-scriptability-edge-addons-blocked)
            (test "Firefox domains NOT blocked for Chrome" test-scriptability-firefox-domain-not-blocked-for-chrome)
            (test "Edge domains NOT blocked for Firefox" test-scriptability-edge-domain-not-blocked-for-firefox)))
