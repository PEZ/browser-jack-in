(ns fixtures.pages
  "Panel and popup page creation helpers for E2E tests."
  (:require ["@playwright/test" :refer [expect]]))

(def mock-devtools-script
  "JavaScript to inject mock chrome.devtools APIs for panel testing.
   Mocks inspectedWindow.eval, network.onNavigated, and panels.themeName."
  "
  // Mock chrome.devtools APIs for panel testing
  window.__mockEvalCalls = [];
  window.__mockHostname = 'test.example.com';

  if (!chrome.devtools) {
    chrome.devtools = {
      inspectedWindow: {
        eval: function(expr, callback) {
          window.__mockEvalCalls.push(expr);
          if (expr.includes('location.hostname')) {
            callback(window.__mockHostname, undefined);
          } else if (expr.includes('location.href')) {
            callback('https://test.example.com/some/path', undefined);
          } else if (expr.includes('scittle') && !expr.includes('eval_string')) {
            callback({ hasScittle: true, hasNrepl: false }, undefined);
          } else if (expr.includes('eval_string')) {
            callback({ value: 'mock-result', error: null }, undefined);
          } else {
            callback(undefined, undefined);
          }
        },
        tabId: 99999
      },
      network: {
        onNavigated: { addListener: function() {} }
      },
      panels: { themeName: 'dark' }
    };
  }
")

(defn- mock-devtools-script-for-tab
  "Generate mock devtools script for a specific tab ID."
  [tab-id]
  (str "
  window.__mockEvalCalls = [];
  window.__mockHostname = 'test.example.com';
  if (!chrome.devtools) {
    chrome.devtools = {
      inspectedWindow: {
        eval: function(expr, callback) {
          window.__mockEvalCalls.push(expr);
          if (expr.includes('location.hostname')) {
            callback(window.__mockHostname, undefined);
          } else if (expr.includes('location.href')) {
            callback('https://test.example.com/some/path', undefined);
          } else if (expr.includes('eval_string')) {
            callback({ value: 'mock-result', error: null }, undefined);
          } else if (expr.includes('scittle') && !expr.includes('eval_string')) {
            callback({ hasScittle: true, hasNrepl: false }, undefined);
          } else {
            callback(undefined, undefined);
          }
        },
        tabId: " tab-id "
      },
      network: {
        onNavigated: { addListener: function() {} }
      },
      panels: { themeName: 'dark' }
    };
  }
  "))

(defn ^:async create-panel-page
  "Create a page with mocked chrome.devtools and navigate to panel.html.
   Waits for panel to be ready by checking for the code textarea."
  [context ext-id]
  (let [panel-page (js-await (.newPage context))
        panel-url (str "chrome-extension://" ext-id "/panel.html")]
    (js-await (.addInitScript panel-page mock-devtools-script))
    (js-await (.goto panel-page panel-url #js {:timeout 3000}))
    (js-await (-> (expect (.locator panel-page "#code-area"))
                  (.toBeVisible #js {:timeout 3000})))
    panel-page))

(defn ^:async create-panel-page-for-tab
  "Create a panel page with mocked chrome.devtools for a specific tab ID.
   Use this when you need the panel to think it's inspecting a real test tab."
  [context ext-id tab-id]
  (let [panel-page (js-await (.newPage context))
        panel-url (str "chrome-extension://" ext-id "/panel.html")]
    (js-await (.addInitScript panel-page (mock-devtools-script-for-tab tab-id)))
    (js-await (.goto panel-page panel-url #js {:timeout 3000}))
    (js-await (-> (expect (.locator panel-page "#code-area"))
                  (.toBeVisible #js {:timeout 3000})))
    panel-page))

(defn ^:async create-popup-page
  "Create a popup page.
   Waits for popup to be ready by checking for the nREPL port input."
  [context ext-id]
  (let [popup-page (js-await (.newPage context))
        popup-url (str "chrome-extension://" ext-id "/popup.html")]
    (js-await (.goto popup-page popup-url #js {:timeout 3000}))
    (js-await (-> (expect (.locator popup-page "#ws-port"))
                  (.toBeVisible #js {:timeout 3000})))
    popup-page))
