(ns e2e.fs-write.save-create-test
  "E2E tests for REPL FS save! happy-path creation"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [sleep eval-in-browser unquote-result
                                      setup-browser! wait-for-script-present!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_save_creates_new_script_from_code_with_manifest []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/save!)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))

  (let [test-code "{:epupp/script-name \"test-script-from-repl\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns test-script)\n                                  (js/console.log \"Hello from test script!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-result (atom :pending))\n                                       (defn ^:async do-save [] (reset! !save-result (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n                                       (do-save)\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!save-result] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/name r)) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (unquote-result (first (.-values check-result))))
              (.toBe "true||test_script_from_repl.cljs"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for epupp.fs/save! result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [raw-result (js-await (eval-in-browser "(pr-str @!save-result)"))]
    (-> (expect (.-success raw-result)) (.toBe true))
    (let [result-str (first (.-values raw-result))]
      (-> (expect (.includes result-str ":requestId")) (.toBe false))
      (-> (expect (.includes result-str ":source")) (.toBe false))
      (-> (expect (.includes result-str ":type")) (.toBe false))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !ls-after-save (atom :pending))\n                                                (defn ^:async do-ls [] (reset! !ls-after-save (await (epupp.fs/ls))))\n                                                (do-ls)\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!ls-after-save)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (.includes (first (.-values check-result)) "test_script_from_repl.cljs"))
              (.toBe true))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls after save"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_save_with_disabled_creates_disabled_script []
  (let [test-code "{:epupp/script-name \"disabled-by-default\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns disabled-test)\n                                  (js/console.log \"Should be disabled!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-disabled (atom :pending))\n                                       (defn ^:async do-save [] (reset! !save-disabled (await (epupp.fs/save! " (pr-str test-code) " {:fs/enabled? false :fs/force? true}))))\n                                       (do-save)\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!save-disabled] (cond (= r :pending) :pending (map? r) (:fs/success r) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (first (.-values check-result)))
              (.toBe "true"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !ls-check-disabled (atom :pending))\n                                                (defn ^:async do-ls [] (reset! !ls-check-disabled (await (epupp.fs/ls))))\n                                                (do-ls)\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!ls-check-disabled)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            (-> (expect (.includes result-str "disabled_by_default.cljs"))
                (.toBe true))
            (let [scripts-check (js-await (eval-in-browser
                                           "(some (fn [s] (and (= (:fs/name s) \"disabled_by_default.cljs\")\n                                                                                 (false? (:fs/enabled? s))))\n                                                                  @!ls-check-disabled)"))]
              (-> (expect (.-success scripts-check)) (.toBe true))
              (-> (expect (.-values scripts-check)) (.toContain "true"))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (js-await (eval-in-browser "(epupp.fs/rm! \"disabled_by_default.cljs\")")))

(defn- ^:async test_save_with_vector_returns_map_of_results []
  (let [code1 "{:epupp/script-name \"bulk-save-test-1\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-save-1)"
        code2 "{:epupp/script-name \"bulk-save-test-2\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-save-2)"
        setup-result (js-await (eval-in-browser
                                (str "(def !bulk-save-result (atom :pending))\n                                       (defn ^:async do-save []\n                                         (try\n                                           (let [result (await (epupp.fs/save! [" (pr-str code1) " " (pr-str code2) "] {:fs/force? true}))]\n                                             (reset! !bulk-save-result {:resolved result}))\n                                           (catch :default e\n                                             (reset! !bulk-save-result {:rejected (.-message e)}))))\n                                       (do-save)\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
        (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-save-result)"))
              result-str (unquote-result (first (.-values check-result)))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not= result-str ":pending"))
            (do
              (js/console.log "=== Bulk save result ===" result-str)
              (-> (expect (.includes result-str "resolved"))
                  (.toBe true))
              (-> (expect (.includes result-str "0"))
                  (.toBe true))
              (-> (expect (.includes result-str "1"))
                  (.toBe true))
              (-> (expect (or (.includes result-str ":fs/success true")
                              (and (.includes result-str "#:fs")
                                   (.includes result-str ":success true"))))
                  (.toBe true))
              (-> (expect (.includes result-str "bulk_save_test_1.cljs"))
                  (.toBe true))
              (-> (expect (.includes result-str "bulk_save_test_2.cljs"))
                  (.toBe true)))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for bulk save! result"))
              (do
                (js-await (sleep 20))
                (recur)))))))

  (let [cleanup-result (js-await (eval-in-browser
                                  "(def !bulk-save-cleanup (atom :pending))\n                                 (defn ^:async do-cleanup []\n                                   (try\n                                     (await (js/Promise.all #js [(epupp.fs/rm! \"bulk_save_test_1.cljs\")\n                                                                  (epupp.fs/rm! \"bulk_save_test_2.cljs\")]))\n                                     (reset! !bulk-save-cleanup :done)\n                                     (catch :default _\n                                       (reset! !bulk-save-cleanup :done))))\n                                 (do-cleanup)\n                                 :cleanup-started"))]
    (-> (expect (.-success cleanup-result)) (.toBe true)))
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!bulk-save-cleanup)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          true
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for bulk save cleanup"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_save_namespace_style_name_normalizes_correctly []
  (let [test-code "{:epupp/script-name \"pez.my-cool-script\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns pez.my-cool-script)\n                  (js/console.log \"Namespace-style script\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !ns-save-result (atom :pending))\n"
                                     "(defn ^:async do-ns-save []\n"
                                     "  (try\n"
                                     "    (let [r (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))]\n"
                                     "      (reset! !ns-save-result {:resolved r}))\n"
                                     "    (catch :default e\n"
                                     "      (reset! !ns-save-result {:rejected (.-message e)}))))\n"
                                     "(do-ns-save)\n"
                                     ":setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser
                                    "(let [r @!ns-save-result]
                                       (cond
                                         (= r :pending) :not-settled
                                         (:rejected r) (str \"ERROR: \" (:rejected r))
                                         (:resolved r) (str (:fs/success (:resolved r)) \"||\" (:fs/name (:resolved r)))
                                         :else :unknown))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result)))
          (let [result-str (unquote-result (first (.-values check-result)))]
            (if (= result-str ":not-settled")
              (if (> (- (.now js/Date) start) timeout-ms)
                (throw (js/Error. "Timeout waiting for namespace-style save result"))
                (do
                  (js-await (sleep 20))
                  (recur)))
              ;; Dots should become slashes: pez.my-cool-script -> pez/my_cool_script.cljs
              (-> (expect result-str)
                  (.toBe "true||pez/my_cool_script.cljs"))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for namespace-style save result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

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
