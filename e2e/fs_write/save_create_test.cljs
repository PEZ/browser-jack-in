(ns e2e.fs-write.save-create-test
  "E2E tests for REPL FS save! happy-path creation"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [eval-in-browser eval-async-and-poll!
                                      setup-browser!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_save_creates_new_script_from_code_with_manifest []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/save!)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))

  (let [test-code "{:epupp/script-name \"test-script-from-repl\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns test-script)\n                                  (js/console.log \"Hello from test script!\")"
        result (js-await (eval-async-and-poll!
                          (str "(def !save-result (atom :pending))\n                                       (defn ^:async do-save [] (reset! !save-result (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n                                       (do-save)\n                                       :setup-done")
                          "(let [r @!save-result] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/name r)) :else r))"
                          3000))]
    (-> (expect result) (.toBe "true||test_script_from_repl.cljs")))

  (let [raw-result (js-await (eval-in-browser "(pr-str @!save-result)"))]
    (-> (expect (.-success raw-result)) (.toBe true))
    (let [result-str (first (.-values raw-result))]
      (-> (expect (.includes result-str ":requestId")) (.toBe false))
      (-> (expect (.includes result-str ":source")) (.toBe false))
      (-> (expect (.includes result-str ":type")) (.toBe false))))

  (let [result (js-await (eval-async-and-poll!
                          "(def !ls-after-save (atom :pending))\n                                                (defn ^:async do-ls [] (reset! !ls-after-save (await (epupp.fs/ls))))\n                                                (do-ls)\n                                                :setup-done"
                          "(pr-str @!ls-after-save)"
                          3000))]
    (-> (expect (.includes result "test_script_from_repl.cljs")) (.toBe true))))

(defn- ^:async test_save_with_disabled_creates_disabled_script []
  (let [test-code "{:epupp/script-name \"disabled-by-default\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns disabled-test)\n                                  (js/console.log \"Should be disabled!\")"
        result (js-await (eval-async-and-poll!
                          (str "(def !save-disabled (atom :pending))\n                                       (defn ^:async do-save [] (reset! !save-disabled (await (epupp.fs/save! " (pr-str test-code) " {:fs/enabled? false :fs/force? true}))))\n                                       (do-save)\n                                       :setup-done")
                          "(let [r @!save-disabled] (cond (= r :pending) :pending (map? r) (:fs/success r) :else r))"
                          3000))]
    (-> (expect result) (.toBe "true")))

  (let [result (js-await (eval-async-and-poll!
                          "(def !ls-check-disabled (atom :pending))\n                                                (defn ^:async do-ls [] (reset! !ls-check-disabled (await (epupp.fs/ls))))\n                                                (do-ls)\n                                                :setup-done"
                          "(pr-str @!ls-check-disabled)"
                          3000))]
    (-> (expect (.includes result "disabled_by_default.cljs"))
        (.toBe true))
    (let [scripts-check (js-await (eval-in-browser
                                   "(some (fn [s] (and (= (:fs/name s) \"disabled_by_default.cljs\")\n                                                                                 (false? (:fs/enabled? s))))\n                                                                  @!ls-check-disabled)"))]
      (-> (expect (.-success scripts-check)) (.toBe true))
      (-> (expect (.-values scripts-check)) (.toContain "true"))))

  (js-await (eval-in-browser "(epupp.fs/rm! \"disabled_by_default.cljs\")")))

(defn- ^:async test_save_with_vector_returns_map_of_results []
  (let [code1 "{:epupp/script-name \"bulk-save-test-1\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-save-1)"
        code2 "{:epupp/script-name \"bulk-save-test-2\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-save-2)"
        result (js-await (eval-async-and-poll!
                          (str "(def !bulk-save-result (atom :pending))\n                                       (defn ^:async do-save []\n                                         (try\n                                           (let [result (await (epupp.fs/save! [" (pr-str code1) " " (pr-str code2) "] {:fs/force? true}))]\n                                             (reset! !bulk-save-result {:resolved result}))\n                                           (catch :default e\n                                             (reset! !bulk-save-result {:rejected (.-message e)}))))\n                                       (do-save)\n                                       :setup-done")
                          "(pr-str @!bulk-save-result)"
                          3000))]
    (js/console.log "=== Bulk save result ===" result)
    (-> (expect (.includes result "resolved"))
        (.toBe true))
    (-> (expect (.includes result "0"))
        (.toBe true))
    (-> (expect (.includes result "1"))
        (.toBe true))
    (-> (expect (or (.includes result ":fs/success true")
                    (and (.includes result "#:fs")
                         (.includes result ":success true"))))
        (.toBe true))
    (-> (expect (.includes result "bulk_save_test_1.cljs"))
        (.toBe true))
    (-> (expect (.includes result "bulk_save_test_2.cljs"))
        (.toBe true)))

  (js-await (eval-async-and-poll!
             "(def !bulk-save-cleanup (atom :pending))\n                                 (defn ^:async do-cleanup []\n                                   (try\n                                     (await (js/Promise.all #js [(epupp.fs/rm! \"bulk_save_test_1.cljs\")\n                                                                  (epupp.fs/rm! \"bulk_save_test_2.cljs\")]))\n                                     (reset! !bulk-save-cleanup :done)\n                                     (catch :default _\n                                       (reset! !bulk-save-cleanup :done))))\n                                 (do-cleanup)\n                                 :cleanup-started"
             "(pr-str @!bulk-save-cleanup)"
             3000)))

(defn- ^:async test_save_namespace_style_name_normalizes_correctly []
  (let [test-code "{:epupp/script-name \"pez.my-cool-script\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns pez.my-cool-script)\n                  (js/console.log \"Namespace-style script\")"
        result (js-await (eval-async-and-poll!
                          (str "(def !ns-save-result (atom :pending))\n"
                               "(defn ^:async do-ns-save []\n"
                               "  (try\n"
                               "    (let [r (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))]\n"
                               "      (reset! !ns-save-result {:resolved r}))\n"
                               "    (catch :default e\n"
                               "      (reset! !ns-save-result {:rejected (.-message e)}))))\n"
                               "(do-ns-save)\n"
                               ":setup-done")
                          "(let [r @!ns-save-result]
                             (cond
                               (= r :pending) :not-settled
                               (:rejected r) (str \"ERROR: \" (:rejected r))
                               (:resolved r) (str (:fs/success (:resolved r)) \"||\" (:fs/name (:resolved r)))
                               :else :unknown))"
                          3000))]
    ;; Dots should become slashes: pez.my-cool-script -> pez/my_cool_script.cljs
    (-> (expect result) (.toBe "true||pez/my_cool_script.cljs")))

  ;; Cleanup
  (let [cleanup-result (js-await (eval-in-browser
                                  "(defn ^:async do-ns-cleanup []\n                                     (try (await (epupp.fs/rm! \"pez/my_cool_script.cljs\")) :done\n                                       (catch :default _ :done)))\n                                   (do-ns-cleanup)"))]
    (-> (expect (.-success cleanup-result)) (.toBe true))))

(.describe test "REPL FS: save - creation"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: save - creates new script from code with manifest"
                   test_save_creates_new_script_from_code_with_manifest)

             (test "REPL FS: save - with {:fs/enabled false} creates disabled script"
                   test_save_with_disabled_creates_disabled_script)

             (test "REPL FS: save - vector returns map of per-item results"
                   test_save_with_vector_returns_map_of_results)

             (test "REPL FS: save - namespace-style name normalizes dots to path separators"
                   test_save_namespace_style_name_normalizes_correctly)))
