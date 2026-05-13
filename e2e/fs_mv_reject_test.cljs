(ns fs-mv-reject-test
  "E2E tests for REPL file system mv! rejection and security tests"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [eval-in-browser setup-browser! eval-async-and-poll!]]))

(def ^:private !context (atom nil))

(defn- assert-save-succeeded! [result expected-filename save-label]
  (when (.includes result "ERROR:")
    (throw (js/Error. (str save-label " save failed: " result))))
  (-> (expect (.includes result expected-filename)) (.toBe true)))

(defn- ^:async test_mv_rejects_when_target_name_exists []
  ;; Create two scripts with different names - save sequentially to avoid races
  (let [code1 "{:epupp/script-name \"mv-collision-source\"\n               :epupp/auto-run-match \"https://example.com/*\"}\n              (ns collision-source)"
        result1 (js-await (eval-async-and-poll!
                           (str "(def !save1 (atom :pending))\n"
                                "(defn ^:async do-it [] (try (reset! !save1 (pr-str (await (epupp.fs/save! " (pr-str code1) " {:fs/force? true})))) (catch :default e (reset! !save1 (str \"ERROR: \" (.-message e))))))\n"
                                "(do-it)\n:started")
                           "(pr-str @!save1)" 3000))]
    (assert-save-succeeded! result1 "mv_collision_source.cljs" "First"))

  ;; Save second script
  (let [code2 "{:epupp/script-name \"mv-collision-target\"\n               :epupp/auto-run-match \"https://example.com/*\"}\n              (ns collision-target)"
        result2 (js-await (eval-async-and-poll!
                           (str "(def !save2 (atom :pending))\n"
                                "(defn ^:async do-it [] (try (reset! !save2 (pr-str (await (epupp.fs/save! " (pr-str code2) " {:fs/force? true})))) (catch :default e (reset! !save2 (str \"ERROR: \" (.-message e))))))\n"
                                "(do-it)\n:started")
                           "(pr-str @!save2)" 3000))]
    (assert-save-succeeded! result2 "mv_collision_target.cljs" "Second"))

  ;; Ensure both scripts are visible before mv
  (let [ls-result (js-await (eval-async-and-poll!
                             "(def !collision-ls-before (atom :pending))\n(defn ^:async do-it [] (reset! !collision-ls-before (pr-str (await (epupp.fs/ls)))))\n(do-it)\n:setup-done"
                             "(let [r @!collision-ls-before] (if (and (not= r :pending) (clojure.string/includes? (str r) \"mv_collision_source.cljs\") (clojure.string/includes? (str r) \"mv_collision_target.cljs\")) (pr-str r) :pending))"
                             3000))]
    (-> (expect (.includes ls-result "mv_collision_source.cljs")) (.toBe true))
    (-> (expect (.includes ls-result "mv_collision_target.cljs")) (.toBe true)))

  ;; Now try to rename source to target - should fail since target exists
  (let [result (js-await (eval-async-and-poll!
                          "(def !collision-mv-result (atom :pending))\n(defn ^:async do-it [] (try (let [r (await (epupp.fs/mv! \"mv_collision_source.cljs\" \"mv_collision_target.cljs\"))] (reset! !collision-mv-result {:resolved r})) (catch :default e (reset! !collision-mv-result {:rejected (.-message e)}))))\n(do-it)\n:setup-done"
                          "(let [r @!collision-mv-result] (cond (= r :pending) :pending (:rejected r) (str \"rejected||\" (:rejected r)) (:resolved r) (str \"resolved||\" (:resolved r)) :else r))"
                          3000))]
    ;; Should be rejected because target already exists
    (-> (expect (.startsWith result "rejected||"))
        (.toBe true))
    (-> (expect (or (.includes result "already exists")
                    (.includes result "Script already exists")))
        (.toBe true)))

  ;; Verify both scripts still exist (no data corruption)
  (let [ls-result (js-await (eval-async-and-poll!
                             "(def !collision-ls (atom :pending))\n(defn ^:async do-it [] (reset! !collision-ls (pr-str (await (epupp.fs/ls)))))\n(do-it)\n:setup-done"
                             "(pr-str @!collision-ls)" 3000))]
    ;; Both scripts should still exist
    (-> (expect (.includes ls-result "mv_collision_source.cljs"))
        (.toBe true))
    (-> (expect (.includes ls-result "mv_collision_target.cljs"))
        (.toBe true)))

  ;; Cleanup
  (js-await (eval-async-and-poll!
             "(def !mv-collision-cleanup (atom :pending))\n(defn ^:async do-it [] (try (await (js/Promise.all #js [(epupp.fs/rm! \"mv_collision_source.cljs\") (epupp.fs/rm! \"mv_collision_target.cljs\")])) (catch :default _)) (reset! !mv-collision-cleanup :done))\n(do-it)\n:cleanup-started"
             "(pr-str @!mv-collision-cleanup)" 3000)))

(defn- ^:async test_mv_rejects_renaming_builtin_scripts []
  ;; Warm up ls
  (js-await (eval-in-browser "(defn ^:async do-it [] (pr-str (await (epupp.fs/ls)))) (do-it)"))

  (js/console.log "Verifying web userscript installer exists before mv test...")
  (let [ls-result (js-await (eval-async-and-poll!
                             "(def !mv-builtin-ls (atom :pending))\n(defn ^:async do-it [] (try (reset! !mv-builtin-ls (pr-str (await (epupp.fs/ls {:fs/ls-hidden? true})))) (catch :default e (reset! !mv-builtin-ls (str \"error: \" (pr-str e))))))\n(do-it)\n:setup-done"
                             "(let [r @!mv-builtin-ls] (if (and (not= r :pending) (clojure.string/includes? (str r) \"epupp/web_userscript_installer.cljs\")) (pr-str r) :pending))"
                             5000))]
    (-> (expect (.includes ls-result "epupp/web_userscript_installer.cljs")) (.toBe true)))

  (let [result (js-await (eval-async-and-poll!
                          "(def !mv-builtin-result (atom :pending))\n(defn ^:async do-it [] (try (let [r (await (epupp.fs/mv! \"epupp/web_userscript_installer.cljs\" \"renamed-builtin.cljs\"))] (reset! !mv-builtin-result {:resolved r})) (catch :default e (reset! !mv-builtin-result {:rejected (.-message e)}))))\n(do-it)\n:setup-done"
                          "(let [r @!mv-builtin-result] (cond (= r :pending) :pending (:rejected r) (str \"rejected||\" (:rejected r)) (:resolved r) (str \"resolved||\" (:resolved r)) :else r))"
                          3000))]
    (if (.startsWith result "rejected||")
      (let [error-msg (.substring result 10)]
        (-> (expect error-msg)
            (.toBe "Cannot rename built-in scripts")))
      (throw (js/Error. (str "mv should have been rejected but got: " result))))))

(defn- ^:async test_mv_rejects_rename_to_reserved_namespace []
  (let [test-code "{:epupp/script-name \"mv-reserved-source\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns mv-reserved-test)"
        save-result (js-await (eval-async-and-poll!
                               (str "(def !mv-reserved-setup (atom :pending))\n"
                                    "(defn ^:async do-it [] (reset! !mv-reserved-setup (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n"
                                    "(do-it)\n:setup-done")
                               "(let [r @!mv-reserved-setup] (if (= r :pending) :pending (:fs/success r)))"
                               3000))]
    (-> (expect save-result) (.toBe "true")))

  (let [result (js-await (eval-async-and-poll!
                          "(def !mv-reserved-result (atom :pending))\n(defn ^:async do-it [] (try (let [r (await (epupp.fs/mv! \"mv_reserved_source.cljs\" \"epupp/test.cljs\"))] (reset! !mv-reserved-result {:resolved r})) (catch :default e (reset! !mv-reserved-result {:rejected (.-message e)}))))\n(do-it)\n:setup-done"
                          "(let [r @!mv-reserved-result] (cond (= r :pending) :pending (:rejected r) (:rejected r) :else r))"
                          3000))]
    (-> (expect result)
        (.toBe "Cannot create scripts in reserved namespace: epupp/")))

  (js-await (eval-in-browser "(defn ^:async do-it [] (try (await (epupp.fs/rm! \"mv_reserved_source.cljs\")) (catch :default _ nil))) (do-it)")))

(defn- ^:async test_mv_rejects_path_traversal_target_names []
  (let [test-code "{:epupp/script-name \"mv-path-source\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns mv-path-test)"
        save-result (js-await (eval-async-and-poll!
                               (str "(def !mv-path-setup (atom :pending))\n"
                                    "(defn ^:async do-it [] (reset! !mv-path-setup (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n"
                                    "(do-it)\n:setup-done")
                               "(let [r @!mv-path-setup] (if (= r :pending) :pending (:fs/success r)))"
                               3000))]
    (-> (expect save-result) (.toBe "true")))

  (let [init-result (js-await (eval-in-browser "(def !mv-path-results (atom {}))"))]
    (-> (expect (.-success init-result)) (.toBe true)))

  (doseq [[label target-name expected-error]
          [["leading slash" "/absolute/path.cljs" "Script name cannot start with '/'"]
           ["dot-slash prefix" "./relative.cljs" "Script name cannot contain './' or '../'"]
           ["dot-dot-slash prefix" "../parent.cljs" "Script name cannot contain './' or '../'"]
           ["dot-dot-slash in middle" "foo/../bar.cljs" "Script name cannot contain './' or '../'"]]]
    ;; Ensure source script exists before each invalid mv attempt
    (js-await (eval-async-and-poll!
               (str "(def !ensure-source (atom :pending))\n"
                    "(defn ^:async do-it [] (reset! !ensure-source (await (epupp.fs/save! \"{:epupp/script-name \\\"mv-path-source\\\"\\n                   :epupp/auto-run-match \\\"https://example.com/*\\\"}\\n                  (ns mv-path-test)\" {:fs/force? true}))))\n"
                    "(do-it)\n:ensuring")
               "(let [r @!ensure-source] (if (= r :pending) :pending (:fs/success r)))"
               3000))

    (let [label-key (pr-str label)
          result (js-await (eval-async-and-poll!
                            (str "(swap! !mv-path-results assoc " label-key " :pending)\n"
                                 "(defn ^:async do-it [] (try (let [r (await (epupp.fs/mv! \"mv_path_source.cljs\" " (pr-str target-name) "))] (swap! !mv-path-results assoc " label-key " {:resolved r})) (catch :default e (swap! !mv-path-results assoc " label-key " {:rejected (.-message e)}))))\n"
                                 "(do-it)\n:setup-done")
                            (str "(let [r (get @!mv-path-results " label-key ")] (cond (= r :pending) :pending (:rejected r) (:rejected r) :else r))")
                            3000))]
      (-> (expect result)
          (.toBe expected-error (str "Expected exact error for mv to: " label)))))

  (js-await (eval-in-browser "(defn ^:async do-it [] (try (await (epupp.fs/rm! \"mv_path_source.cljs\")) (catch :default _ nil))) (do-it)")))

(.describe test "REPL FS: mv rejection operations"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: mv - rejects rename when target name already exists"
                   test_mv_rejects_when_target_name_exists)

             (test "REPL FS: mv - rejects renaming built-in scripts"
                   test_mv_rejects_renaming_builtin_scripts)

             (test "REPL FS: mv - rejects rename to reserved namespace"
                   test_mv_rejects_rename_to_reserved_namespace)

             (test "REPL FS: mv - rejects path traversal target names"
                   test_mv_rejects_path_traversal_target_names)))
