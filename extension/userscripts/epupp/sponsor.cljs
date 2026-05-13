{:epupp/script-name "epupp/sponsor.cljs"
 :epupp/auto-run-match "https://github.com/sponsors/PEZ*"
 :epupp/description "Detects GitHub sponsor status for Epupp"
 :epupp/run-at "document-idle"
 :epupp/inject ["scittle://replicant.js"
                "epupp://epupp/internal/helpers.cljs"
                "epupp://epupp/ui.cljs"]}

(ns epupp.sponsor
  (:require [replicant.dom :as r]
            [epupp.internal.helpers :as h]
            [epupp.ui :as ui]))

;; Privacy: This script only sends a single boolean (sponsor/not-sponsor)
;; back to Epupp. No GitHub username or other personal data is collected.
;; The source code is visible in the Epupp popup script list for anyone
;; to verify.

;; ============================================================
;; Extension communication
;; ============================================================

(defonce !sponsored-username (atom nil))

(defn ^:async fetch-sponsored-username!+
  "Fetch the expected sponsor username from the extension via content bridge.
   Returns a Promise that resolves with the username string or nil on timeout."
  []
  (let [msg (await (h/send-and-receive "get-sponsored-username"
                                       "get-sponsored-username-response"))]
    (when msg (.-username msg))))

;; ============================================================
;; Forever sponsors
;; ============================================================

(def ^:private forever-sponsors
  {"PEZ" "Thanks to myself for Epupp, Calva, Joyride, and Backseat Driver!"
   "borkdude" "Thanks for SCI, Squint, Babashka, Scittle, Joyride, and all the things! You have status in my heart as a forever sponsor of Epupp and Calva."
   "richhickey" "Thanks for Clojure! You have status in my heart as a forever sponsor of Epupp and Calva."
   "swannodette" "Thanks for stewarding ClojureScript for all these years! You have status in my heart as a forever sponsor of Epupp and Calva."
   "thheller" "Thanks for shadow-cljs! You have status in my heart as a forever sponsor of Epupp and Calva."})

;; ============================================================
;; Messaging
;; ============================================================

(defn- send-sponsor-status! []
  (js/window.postMessage
   #js {:source "epupp-userscript"
        :type "sponsor-status"
        :sponsor true}
   "*"))

;; ============================================================
;; Branded banner rendering
;; ============================================================

(def ^:private banner-background-image
  "linear-gradient(color-mix(in srgb, var(--bgColor-attention-muted) 75%, transparent), color-mix(in srgb, var(--bgColor-attention-muted) 75%, transparent))")

(def ^:private banner-inner-style
  {:display "flex"
   :align-items "center"
   :justify-content "space-between"
   :flex-wrap "wrap"
   :margin "0 auto"
   :gap "16px"})

(def ^:private banner-message-style
  {:text-align "right"
   :flex-shrink 0})

(def ^:private heart-view
  [:span {:style {:color "#e91e63"}} "♥"])

(defn- message-with-heart [text]
  [:span text " " heart-view])

(defn- remove-existing-banner!
  "Remove any previously inserted Epupp sponsor banner."
  []
  (doseq [el (array-seq (js/document.querySelectorAll "[data-epupp-sponsor-banner]"))]
    (.remove el)))

(defn render-banner!
  "Render a branded Epupp banner at the top of <main> with the given message."
  [message]
  (remove-existing-banner!)
  (when-let [main-el (js/document.querySelector "main")]
    (let [banner (js/document.createElement "div")]
      (.add (.-classList banner) "flash" "flash-warn" "flash-full" "epupp-sponsor-banner")
      (.setAttribute banner "data-epupp-sponsor-banner" "true")
      (.setProperty (.-style banner) "background-image" banner-background-image)
      (r/render banner
                [:div {:style banner-inner-style}
                 [:div (ui/epupp-header :size 32)]
                 [:div {:style banner-message-style} message]])
      (.insertBefore main-el banner (.-firstChild main-el)))))

;; ============================================================
;; Detection and action
;; ============================================================

(defn- on-expected-path?
  "Returns true when the current page path starts with the expected sponsor path."
  [expected-path]
  (and expected-path
       (.startsWith (.-pathname js/window.location) expected-path)))

(defn- page-signals
  "Collects sponsor-detection signals from the current page."
  []
  (let [params (js/URLSearchParams. (.-search js/window.location))
        user-login (.-content (js/document.querySelector "meta[name='user-login']"))
        logged-in? (and (string? user-login) (not (empty? user-login)))
        h1 (js/document.querySelector "h1.f2")
        h1-text (when h1 (.trim (.-textContent h1)))
        body-text (.-textContent js/document.body)]
    {:just-sponsored? (= "true" (.get params "success"))
     :logged-in? logged-in?
     :forever-message (when logged-in? (get forever-sponsors user-login))
     :h1-text h1-text
     :has-sponsoring-as? (re-find #"Sponsoring as" body-text)}))

(defn- act-on-signals!
  "Renders the appropriate banner and sends sponsor status based on page signals."
  [{:keys [forever-message just-sponsored? logged-in? h1-text has-sponsoring-as?]}]
  (cond
    ;; Forever sponsor - personalized thank-you, always send true
    forever-message
    (do
      (render-banner! (message-with-heart forever-message))
      (send-sponsor-status!))

    ;; Just completed a sponsorship (one-time or recurring confirmation)
    just-sponsored?
    (do
      (render-banner! (message-with-heart "Thanks for sponsoring me!"))
      (send-sponsor-status!))

    ;; Not logged in
    (not logged-in?)
    (render-banner! "Log in to GitHub to update your Epupp sponsor status")

    ;; Logged in, "Become a sponsor" heading present - not sponsoring
    (and h1-text (re-find #"Become a sponsor" h1-text))
    (render-banner! (message-with-heart "Sponsor PEZ to light up your Epupp sponsor heart!"))

    ;; Logged in, "Sponsoring as" present - confirmed recurring sponsor
    has-sponsoring-as?
    (do
      (render-banner! (message-with-heart "Thanks for sponsoring me!"))
      (send-sponsor-status!))

    ;; Unknown state - do nothing (graceful degradation)
    :else nil))

(defn detect-and-act! []
  (let [expected-username @!sponsored-username
        expected-path (when expected-username (str "/sponsors/" expected-username))]
    (when (on-expected-path? expected-path)
      (act-on-signals! (page-signals)))))

;; ============================================================
;; SPA navigation guard (defonce prevents listener stacking)
;; ============================================================

(defonce !nav-registered (atom false))

;; ============================================================
;; Initialization
;; ============================================================

(defn- ^:async ensure-username-and-detect! []
  (when-not @!sponsored-username
    (try
      (when-let [username (await (fetch-sponsored-username!+))]
        (reset! !sponsored-username username))
      (catch :default _)))
  (detect-and-act!))

(defn- register-nav-listener! []
  (when (and (not @!nav-registered) js/window.navigation)
    (reset! !nav-registered true)
    (let [!nav-timeout (atom nil)
          !last-url (atom js/window.location.href)]
      (.addEventListener js/window.navigation "navigate"
                         (fn [evt]
                           (let [new-url (.-url (.-destination evt))]
                             (when (not= new-url @!last-url)
                               (reset! !last-url new-url)
                               (when-let [tid @!nav-timeout]
                                 (js/clearTimeout tid))
                               (reset! !nav-timeout
                                       (js/setTimeout detect-and-act! 300)))))))))

(defn- ^:async init! []
  (await (ensure-username-and-detect!))
  (register-nav-listener!))

(init!)
