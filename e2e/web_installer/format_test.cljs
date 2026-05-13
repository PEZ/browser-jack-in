(ns e2e.web-installer.format-test
  "E2E tests for web installer format detection (GitHub, GitLab, etc.)."
  (:require ["@playwright/test" :refer [test expect]]
            ["./../fixtures/browser.mjs" :refer [launch-browser get-extension-id]]
            ["./../fixtures/pages.mjs" :refer [create-popup-page]]
            ["./../fixtures/wait.mjs" :refer [wait-for-popup-ready]]
            ["./../fixtures/events.mjs" :refer [assert-no-errors!]]
            ["./helpers.mjs" :as h]))

(def expected-gist-library-copy-url
  "https://gist.githubusercontent.com/PEZ/0123456789abcdef0123456789abcdef/raw/1234567890abcdef1234567890abcdef12345678/gist_library.cljs")

(def expected-repo-library-copy-url
  "https://raw.githubusercontent.com/PEZ/pez-my-epupp-hq/fedcba9876543210fedcba9876543210fedcba98/userscripts/pez/repo_library.cljs")

(defn- ^:async install-clipboard-spy! [page]
  (js-await (.evaluate page
                       (fn []
                         (aset js/window "__EPUPP_COPIED_TEXT" nil)
                         (.defineProperty js/Object js/navigator "clipboard"
                                          #js {:configurable true
                                               :value #js {:writeText (fn [text]
                                                                        (aset js/window "__EPUPP_COPIED_TEXT" text)
                                                                        (js/Promise.resolve nil))}})))))

(defn- ^:async wait-for-copied-text!+ [page]
  (loop [remaining 20]
    (let [copied-text (js-await (.evaluate page (fn [] (aget js/window "__EPUPP_COPIED_TEXT"))))]
      (if (or copied-text (zero? remaining))
        copied-text
        (do
          (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 25))))
          (recur (dec remaining)))))))

(defn- ^:async run-button-placement-test!+
  "Run shared button placement test: browser setup, install via install-fn!, verify in popup."
  [install-fn! script-name]
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))
      (let [page (js-await (h/navigate-to-mock-gist context))]
        (js-await (install-fn! page))
        (js-await (.close page)))
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup (str ".script-item:has-text(\"" script-name "\")"))]
          (js-await (-> (expect script-item) (.toBeVisible #js {:timeout 1000}))))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async install-github-style-script! [page]
  (let [install-btn (.locator page "#github-installable-gist .file-actions [data-e2e-install-state=\"install\"]")]
    (js-await (-> (expect install-btn) (.toBeVisible #js {:timeout 2000})))
    (js/console.log "Install button found in .file-actions container")
    (js-await (h/assert-no-install-button page "#github-non-installable .file-actions" "install" 500))
    (js/console.log "Confirmed: non-installable GitHub block has no Install button")
    (js-await (.click install-btn))
    (let [confirm-btn (.locator page "#epupp-confirm")]
      (js-await (-> (expect confirm-btn) (.toBeVisible #js {:timeout 1000})))
      (js-await (.click confirm-btn)))
    (js-await (h/wait-for-install-button page "#github-installable-gist .file-actions" "installed" 1000))
    (js/console.log "GitHub-style script installed successfully")))

(defn- ^:async install-gitlab-script! [page]
  (js-await (h/wait-for-install-button page "#gitlab-installable-snippet .file-actions" "install" 2000))
  (js/console.log "Install button found in GitLab .file-actions container")
  (js-await (h/assert-no-install-button page "#gitlab-non-installable .file-actions" "install" 500))
  (js/console.log "Confirmed: non-installable GitLab block has no Install button")
  (js-await (h/click-install-and-confirm!+ page "#gitlab-installable-snippet .file-actions" "installed"))
  (js/console.log "GitLab-style script installed successfully"))

(defn- ^:async install-github-repo-script! [page]
  (js-await (h/wait-for-install-button page "#github-repo-installable" "install" 2000))
  (js/console.log "Install button found in GitHub repo container")
  (js-await (h/click-install-and-confirm!+ page "#github-repo-installable" "installed"))
  (js/console.log "GitHub repo script installed successfully"))

(defn- ^:async test_github_style_block []
  (js-await (run-button-placement-test!+ install-github-style-script! "github_test_script.cljs")))

(defn- ^:async run-library-copy-action-test!+
  "Run shared library copy action test: setup, navigate, copy URL, install, verify popup."
  [library-container non-installable-container expected-url script-name]
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))
      (let [page (js-await (h/navigate-to-mock-gist context))]
        (js-await (install-clipboard-spy! page))
        (let [copy-btn (js-await (h/wait-for-installer-action page
                                                              library-container
                                                              {:action "copy-library-url"}
                                                              2000))]
          (js-await (-> (expect copy-btn)
                        (.toHaveAttribute "title" "Copy library URL")))
          (js-await (h/assert-no-installer-action page
                                                  non-installable-container
                                                  {:action "copy-library-url"}
                                                  500))
          (js-await (.click copy-btn))
          (let [copied-text (js-await (wait-for-copied-text!+ page))]
            (js-await (-> (expect copied-text)
                          (.toBe expected-url))))
          (js-await (h/click-install-and-confirm!+
                     page
                     library-container
                     "installed"))
          (js-await (.close page))))
      (let [popup (js-await (create-popup-page context ext-id))]
        (js-await (wait-for-popup-ready popup))
        (let [script-item (.locator popup (str ".script-item:has-text(\"" script-name "\")"))]
          (js-await (-> (expect script-item)
                        (.toBeVisible #js {:timeout 1000}))))
        (js-await (assert-no-errors! popup))
        (js-await (.close popup)))
      (finally
        (js-await (.close context))))))

(defn- ^:async test_gist_library_copy_action []
  (js-await (run-library-copy-action-test!+
             "#gist-library-gist"
             "#installable-gist"
             expected-gist-library-copy-url
             "gist_library.cljs")))

(defn- ^:async test_github_repo_library_copy_action []
  (js-await (run-library-copy-action-test!+
             "#github-repo-library"
             "#github-repo-installable"
             expected-repo-library-copy-url
             "repo_library.cljs")))

(defn- ^:async test_gitlab_button_placement []
  (js-await (run-button-placement-test!+ install-gitlab-script! "gitlab_test_script.cljs")))

(defn- ^:async test_github_repo_button_placement []
  (js-await (run-button-placement-test!+ install-github-repo-script! "github_repo_script.cljs")))

(defn- ^:async test_github_gist_edit_skipped []
  (let [context (js-await (launch-browser))
        ext-id (js-await (get-extension-id context))]
    (try
      ;; Setup installer
      (let [popup (js-await (h/setup-installer!+ context ext-id))]
        (js-await (.close popup)))

      ;; Navigate to mock gist page
      (let [page (js-await (h/navigate-to-mock-gist context))]
        ;; Verify NO install button appears for gist edit textarea
        ;; (code editor textareas are skipped)
        (js-await (h/assert-no-install-button page "#github-gist-edit-textarea" "install" 2000))
        (js/console.log "Confirmed: gist edit textarea correctly skipped (no install button)")

        (js-await (.close page)))

      (finally
        (js-await (.close context))))))

(.describe test "Web Installer: format detection"
           (fn []
     (test "Web Installer: gist library copy action"
       test_gist_library_copy_action)

     (test "Web Installer: GitHub repo library copy action"
       test_github_repo_library_copy_action)

             (test "Web Installer: detects GitHub-style table code blocks"
                   test_github_style_block)

             (test "Web Installer: places GitLab buttons in .file-actions"
                   test_gitlab_button_placement)

             (test "Web Installer: places GitHub repo buttons in ButtonGroup"
                   test_github_repo_button_placement)

             (test "Web Installer: skips gist edit textareas (code editor)"
                   test_github_gist_edit_skipped)))
