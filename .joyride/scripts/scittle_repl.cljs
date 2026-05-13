(ns scittle-repl
  "Start a Scittle REPL for experimenting with Scittle code.

   This script:
   1. Starts the browser-nrepl relay server (via VS Code task)
   2. Opens a Calva Flare webview with Scittle + nREPL client
   3. Prompts to connect Calva to the nREPL port

   Usage: Run this script, then use Calva Connect with 'Scittle Dev REPL' sequence."
  (:require [joyride.core :as joyride]
            [joyride.flare :as flare]
            [promesa.core :as p]
            ["vscode" :as vscode]))

;; High port numbers to avoid conflicts with other services
(def nrepl-port 31337)
(def websocket-port 31338)
(def flare-key :sidebar-1)

(defn find-task-by-label [label tasks]
  (first (filter #(= label (.-name %)) tasks)))

(defn relay-running?+
  "Check if browser-nrepl relay is already listening on the nREPL port."
  []
  (p/create
   (fn [resolve _reject]
     (let [net (js/require "net")
           client (.connect net nrepl-port "127.0.0.1")]
       (.on client "connect"
            (fn []
              (.end client)
              (resolve true)))
       (.on client "error"
            (fn [_err]
              (resolve false)))))))

(def ^:private flare-css
  "body {
    font-family: var(--vscode-font-family);
    padding: 20px;
    background: var(--vscode-editor-background);
    color: var(--vscode-editor-foreground);
  }
  h1 { color: var(--vscode-textLink-foreground); }
  .status {
    padding: 10px;
    border-radius: 4px;
    background: var(--vscode-textBlockQuote-background);
    margin: 10px 0;
  }
  .port {
    font-family: var(--vscode-editor-font-family);
    background: var(--vscode-textCodeBlock-background);
    padding: 2px 6px;
    border-radius: 3px;
  }
  code {
    font-family: var(--vscode-editor-font-family);
    background: var(--vscode-textCodeBlock-background);
    padding: 2px 6px;
    border-radius: 3px;
  }")

(defn- flare-body []
  [:body
   [:h1 "Scittle Dev REPL"]
   [:div.status
    [:p "WebSocket port: " [:span.port (str websocket-port)]]
    [:p "nREPL port: " [:span.port (str nrepl-port)]]]
   [:p "This webview hosts a Scittle runtime connected to the browser-nrepl relay."]
   [:p "To evaluate Scittle code:"]
   [:ol
    [:li "Connect Calva using " [:code "Scittle Dev REPL"] " sequence"]
    [:li "Evaluate ClojureScript code from any file"]
    [:li "Results execute in this Scittle environment"]]
   [:hr]
   [:p [:em "Tip: Keep this panel open while developing Scittle code."]]])

(defn open-scittle-flare!+ []
  (println "Opening Scittle flare webview...")
  (flare/flare!+
   {:html [:html
           [:head
            [:meta {:charset "UTF-8"}]
            [:title "Scittle Dev REPL"]
            [:style flare-css]
            [:script (str "var SCITTLE_NREPL_WEBSOCKET_PORT = " websocket-port ";
                          var SCITTLE_NREPL_WEBSOCKET_HOST = '127.0.0.1';")]
            [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.js"
                      :type "application/javascript"}]
            [:script {:src "https://cdn.jsdelivr.net/npm/scittle@0.8.31/dist/scittle.nrepl.js"
                      :type "application/javascript"}]]
           (flare-body)]
    :key flare-key
    :title "Scittle Dev REPL"}))

(defn start!+ []
  (p/do!
   (p/delay 1500)
   (open-scittle-flare!+)))

(defn stop!+ []
  (flare/close! flare-key))

(comment
  ;; Manual control
  (start!+)
  (stop!+)

  ;; Test flare separately
  (open-scittle-flare!+)
  (flare/close! flare-key)

  ;; List active flares
  (flare/ls)
  :rcf)
