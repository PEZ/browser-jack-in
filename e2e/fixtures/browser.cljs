(ns fixtures.browser
  "Browser launch and extension ID helpers for E2E tests."
  (:require ["@playwright/test" :refer [chromium]]
            [fixtures.constants :refer [extension-path]]))

(defn ^:async create-extension-context
  "Launch Chrome with the extension loaded.
   Returns a persistent browser context.
   Note: Extensions require headed mode (headless: false)."
  []
  (js-await
   (.launchPersistentContext chromium ""
                             #js {:headless false
                                  :args #js ["--no-sandbox"
                                             (str "--disable-extensions-except=" extension-path)
                                             (str "--load-extension=" extension-path)]})))

;; Alias for backward compatibility
(def launch-browser create-extension-context)

(defn ^:async get-extension-id
  "Extract extension ID from service worker URL.
   Waits for service worker if not immediately available."
  [context]
  (let [workers (.serviceWorkers context)]
    (if (pos? (.-length workers))
      (let [sw-url (-> (aget workers 0) (.url))]
        (-> sw-url (.split "/") (aget 2)))
      (let [sw (js-await (.waitForEvent context "serviceworker"))
            sw-url (.url sw)]
        (-> sw-url (.split "/") (aget 2))))))
