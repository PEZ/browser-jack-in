(ns e2e.fs-write.save-security-test
  "E2E tests for REPL FS save! reserved names and path traversal security"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [eval-in-browser eval-async-and-poll!
                                      setup-browser! ensure-builtin-script!
                                      wait-for-builtin-script-via-repl!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_save_rejects_builtin_script_names []
  (js-await (ensure-builtin-script! @!context))
  (js-await (wait-for-builtin-script-via-repl! "epupp/web_userscript_installer.cljs" 5000))

  ;; Try to save a script with a built-in name - should reject
  ;; Note: epupp/ prefix is rejected by reserved namespace check (correct behavior)
  (let [test-code "{:epupp/script-name \"epupp/web_userscript_installer.cljs\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns fake-builtin)\n                  (js/console.log \"Trying to impersonate built-in!\")"
        result-str (js-await (eval-async-and-poll!
                               (str "(def !save-builtin-result (atom :pending))\n                                     (defn ^:async do-save []\n                                       (try\n                                         (let [r (await (epupp.fs/save! " (pr-str test-code) "))]\n                                           (reset! !save-builtin-result {:resolved r}))\n                                         (catch :default e\n                                           (reset! !save-builtin-result {:rejected (.-message e)}))))\n                                     (do-save)\n                                     :setup-done")
                               "(let [r @!save-builtin-result]
                                  (cond
                                    (= r :pending) :not-settled
                                    (:rejected r) (:rejected r)
                                    :else :resolved))"
                               3000))]
    (-> (expect result-str)
        (.toBe "Cannot create scripts in reserved namespace: epupp/"))))

(defn- ^:async test_save_with_force_rejects_builtin_script_names []
  (js-await (ensure-builtin-script! @!context))
  (js-await (wait-for-builtin-script-via-repl! "epupp/web_userscript_installer.cljs" 5000))

  ;; Try to save with force - still should reject (reserved namespace)
  (let [test-code "{:epupp/script-name \"epupp/web_userscript_installer.cljs\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns fake-builtin-force)"
        result-str (js-await (eval-async-and-poll!
                               (str "(def !save-builtin-force-result (atom :pending))\n                                     (defn ^:async do-save []\n                                       (try\n                                         (let [r (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))]\n                                           (reset! !save-builtin-force-result {:resolved r}))\n                                         (catch :default e\n                                           (reset! !save-builtin-force-result {:rejected (.-message e)}))))\n                                     (do-save)\n                                     :setup-done")
                               "(let [r @!save-builtin-force-result]
                                  (cond
                                    (= r :pending) :not-settled
                                    (:rejected r) (:rejected r)
                                    :else :resolved))"
                               3000))]
    (-> (expect result-str)
        (.toBe "Cannot create scripts in reserved namespace: epupp/"))))

(defn- ^:async test_save_rejects_reserved_namespace_with_clear_error []
  (let [test-code "{:epupp/script-name \"epupp/my-script.cljs\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns bad-namespace)"
        result-str (js-await (eval-async-and-poll!
                               (str "(def !save-reserved-result (atom :pending))\n"
                                    "(defn ^:async do-save []\n"
                                    "  (try\n"
                                    "    (let [r (await (epupp.fs/save! " (pr-str test-code) "))]\n"
                                    "      (reset! !save-reserved-result {:resolved r}))\n"
                                    "    (catch :default e\n"
                                    "      (reset! !save-reserved-result {:rejected (.-message e)}))))\n"
                                    "(do-save)\n"
                                    ":setup-done")
                               "(let [r @!save-reserved-result]
                                  (cond
                                    (= r :pending) :not-settled
                                    (:rejected r) (:rejected r)
                                    :else :resolved))"
                               3000))]
    (-> (expect result-str)
        (.toBe "Cannot create scripts in reserved namespace: epupp/"))))

(defn- ^:async test_save_rejects_epupp_dot_namespace_bypass []
  (let [test-code "{:epupp/script-name \"epupp.sneaky-script\"}\n                  (ns epupp.sneaky-script)"
        result-str (js-await (eval-async-and-poll!
                               (str "(def !epupp-dot-result (atom :pending))\n"
                                    "(defn ^:async do-epupp-dot-save []\n"
                                    "  (try\n"
                                    "    (let [r (await (epupp.fs/save! " (pr-str test-code) "))]\n"
                                    "      (reset! !epupp-dot-result {:resolved r}))\n"
                                    "    (catch :default e\n"
                                    "      (reset! !epupp-dot-result {:rejected (.-message e)}))))\n"
                                    "(do-epupp-dot-save)\n"
                                    ":setup-done")
                               "(let [r @!epupp-dot-result]
                                  (cond
                                    (= r :pending) :not-settled
                                    (:rejected r) (:rejected r)
                                    :else :resolved))"
                               3000))]
    ;; Must be rejected: epupp.sneaky normalizes to epupp/sneaky
    (-> (expect result-str)
        (.toBe "Cannot create scripts in reserved namespace: epupp/"))))

(defn- ^:async test_save_rejects_path_traversal_names []
  (let [init-result (js-await (eval-in-browser "(def !save-path-results (atom {}))"))]
    (-> (expect (.-success init-result)) (.toBe true)))

  (doseq [[label name-pattern]
          [["leading slash" "/absolute/path.cljs"]
           ["dot-slash prefix" "./relative.cljs"]
           ["dot-dot-slash prefix" "../parent.cljs"]
           ["dot-dot-slash in middle" "foo/../bar.cljs"]]]
    (let [test-code (str "{:epupp/script-name \"" name-pattern "\"\n"
                         " :epupp/auto-run-match \"https://example.com/*\"}\n"
                         "(ns path-traversal-test)")
          label-key (pr-str label)
          result-str (js-await (eval-async-and-poll!
                                 (str "(swap! !save-path-results assoc " label-key " :pending)\n"
                                      "(defn ^:async do-save []\n"
                                      "  (try\n"
                                      "    (let [r (await (epupp.fs/save! " (pr-str test-code) "))]\n"
                                      "      (swap! !save-path-results assoc " label-key " {:resolved r}))\n"
                                      "    (catch :default e\n"
                                      "      (swap! !save-path-results assoc " label-key " {:rejected (.-message e)}))))\n"
                                      "(do-save)\n"
                                      ":setup-done")
                                 (str "(let [r (get @!save-path-results " label-key ")]
                                         (cond
                                           (= r :pending) :not-settled
                                           (:rejected r) (:rejected r)
                                           :else :resolved))")
                                 3000))
          expected (if (= label "leading slash")
                     "Script name cannot start with '/'"
                     "Script name cannot contain './' or '../'")]
      (-> (expect result-str)
          (.toBe expected)))))
(.describe test "REPL FS: save - security"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: save - rejects built-in script names"
                   test_save_rejects_builtin_script_names)

             (test "REPL FS: save - with {:fs/force? true} still rejects built-in script names"
                   test_save_with_force_rejects_builtin_script_names)

             (test "REPL FS: save - rejects reserved namespace with clear error"
                   test_save_rejects_reserved_namespace_with_clear_error)

             (test "REPL FS: save - rejects epupp. dot bypass of reserved namespace"
                   test_save_rejects_epupp_dot_namespace_bypass)

             (test "REPL FS: save - rejects path traversal names"
                   test_save_rejects_path_traversal_names)))
