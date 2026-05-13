(ns fs-mv-test
  "E2E tests for REPL file system mv! happy-path operations"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [eval-in-browser setup-browser! eval-async-and-poll!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_mv_renames_a_script []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/mv!)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))

  (let [test-code "{:epupp/script-name \"mv-rename-test-original\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns rename-test)"
        result (js-await (eval-async-and-poll!
                          (str "(def !mv-setup (atom :pending))\n                                       (defn ^:async do-it [] (reset! !mv-setup (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n                                       (do-it)\n                                       :setup-done")
                          "(let [r @!mv-setup] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/name r)) :else r))"
                          3000))]
    (-> (expect result) (.toBe "true||mv_rename_test_original.cljs")))

  (let [result (js-await (eval-async-and-poll!
                          "(def !mv-ls (atom :pending))\n                                                (defn ^:async do-it [] (reset! !mv-ls (await (epupp.fs/ls))))\n                                                (do-it)\n                                                :setup-done"
                          "(pr-str @!mv-ls)"
                          3000))]
    (-> (expect (.includes result "mv_rename_test_original.cljs")) (.toBe true)))

  (let [result (js-await (eval-async-and-poll!
                          "(def !mv-result (atom :pending))\n                                                (defn ^:async do-it [] (reset! !mv-result (await (epupp.fs/mv! \"mv_rename_test_original.cljs\" \"mv_renamed_script.cljs\" {:fs/force? true}))))\n                                                (do-it)\n                                                :setup-done"
                          "(let [r @!mv-result] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/from-name r) \"||\" (:fs/to-name r)) :else r))"
                          3000))]
    (-> (expect result) (.toBe "true||mv_rename_test_original.cljs||mv_renamed_script.cljs")))

  (let [result (js-await (eval-async-and-poll!
                          "(def !ls-after-mv (atom :pending))\n                                                (defn ^:async do-it [] (reset! !ls-after-mv (await (epupp.fs/ls))))\n                                                (do-it)\n                                                :setup-done"
                          "(pr-str @!ls-after-mv)"
                          3000))]
    (-> (expect (.includes result "mv_renamed_script.cljs")) (.toBe true))
    (-> (expect (.includes result "mv_rename_test_original.cljs")) (.toBe false))))

(defn- ^:async test_mv_with_force_returns_from_and_to_names []
  (let [test-code "{:epupp/script-name \"mv-force-confirm\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns mv-force-confirm)"
        result (js-await (eval-async-and-poll!
                          (str "(def !confirm-mv-setup (atom :pending))\n                                       (defn ^:async do-it [] (reset! !confirm-mv-setup (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n                                       (do-it)\n                                       :setup-done")
                          "(let [r @!confirm-mv-setup] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/name r)) :else r))"
                          3000))]
    (-> (expect result) (.toBe "true||mv_force_confirm.cljs")))

  (let [result (js-await (eval-async-and-poll!
                          "(def !confirm-mv-ls (atom :pending))\n                                (defn ^:async do-it [] (reset! !confirm-mv-ls (await (epupp.fs/ls))))\n                                (do-it)\n                                :setup-done"
                          "(pr-str @!confirm-mv-ls)"
                          3000))]
    (-> (expect (.includes result "mv_force_confirm.cljs")) (.toBe true)))

  (let [result (js-await (eval-async-and-poll!
                          "(def !confirm-mv-result (atom :pending))\n                                (defn ^:async do-it [] (reset! !confirm-mv-result (await (epupp.fs/mv! \"mv_force_confirm.cljs\" \"mv_force_renamed.cljs\" {:fs/force? true}))))\n                                (do-it)\n                                :setup-done"
                          "(let [r @!confirm-mv-result] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/from-name r) \"||\" (:fs/to-name r)) :else r))"
                          3000))]
    (-> (expect result) (.toBe "true||mv_force_confirm.cljs||mv_force_renamed.cljs")))

  (let [raw-result (js-await (eval-in-browser "(pr-str @!confirm-mv-result)"))]
    (-> (expect (.-success raw-result)) (.toBe true))
    (let [result-str (first (.-values raw-result))]
      (-> (expect (.includes result-str ":requestId")) (.toBe false))
      (-> (expect (.includes result-str ":source")) (.toBe false))
      (-> (expect (.includes result-str ":type")) (.toBe false))))

  (js-await (eval-async-and-poll!
             "(def !mv-force-cleanup (atom :pending))\n                                 (defn ^:async do-it [] (try (await (epupp.fs/rm! \"mv_force_renamed.cljs\")) (catch :default _)) (reset! !mv-force-cleanup :done))\n                                 (do-it)\n                                 :cleanup-started"
             "(pr-str @!mv-force-cleanup)"
             3000)))

(defn- ^:async test_mv_with_force_overwrites_existing_target []
  (let [source-script-name "mv-force-overwrite-source"
        source-name "mv_force_overwrite_source.cljs"
        target-script-name "mv-force-overwrite-target"
        target-name "mv_force_overwrite_target.cljs"
        source-description "source-description"
        target-description "target-description"
        source-marker "mv-force-overwrite-source-marker"
        target-marker "mv-force-overwrite-target-marker"
        expected-mv-result (str "resolved||true||" source-name "||" target-name)
        source-code (str "{:epupp/script-name \"" source-script-name "\"\n"
                         " :epupp/description \"" source-description "\"\n"
                         " :epupp/auto-run-match \"https://source.example/*\"}\n"
                         "(ns mv-force-overwrite-source)\n"
                         "(js/console.log \"" source-marker "\")")
        target-code (str "{:epupp/script-name \"" target-script-name "\"\n"
                         " :epupp/description \"" target-description "\"\n"
                         " :epupp/auto-run-match \"https://target.example/*\"}\n"
                         "(ns mv-force-overwrite-target)\n"
                         "(js/console.log \"" target-marker "\")")
        cleanup-code (str "(def !mv-force-overwrite-cleanup (atom :pending))\n"
                          "(defn ^:async do-it []\n"
                          "  (try (await (epupp.fs/rm! \"" source-name "\")) (catch :default _ nil))\n"
                          "  (try (await (epupp.fs/rm! \"" target-name "\")) (catch :default _ nil))\n"
                          "  (reset! !mv-force-overwrite-cleanup :done))\n"
                          "(do-it)\n"
                          ":cleanup-started")]
    ;; Clean up any leftovers from prior runs before creating the test scripts
    (js-await (eval-async-and-poll! cleanup-code "(pr-str @!mv-force-overwrite-cleanup)" 3000))

    ;; Create two scripts with different names and different content - save sequentially to avoid races
    (let [result (js-await (eval-async-and-poll!
                            (str "(def !mv-force-overwrite-save-source (atom :pending))\n"
                                 "(defn ^:async do-it [] (try (reset! !mv-force-overwrite-save-source (pr-str (await (epupp.fs/save! " (pr-str source-code) " {:fs/force? true})))) (catch :default e (reset! !mv-force-overwrite-save-source (str \"ERROR: \" (.-message e))))))\n"
                                 "(do-it)\n"
                                 ":started")
                            "(pr-str @!mv-force-overwrite-save-source)"
                            3000))]
      (when (.includes result "ERROR:")
        (throw (js/Error. (str "Source save failed: " result))))
      (-> (expect (.includes result source-name)) (.toBe true)))

    (let [result (js-await (eval-async-and-poll!
                            (str "(def !mv-force-overwrite-save-target (atom :pending))\n"
                                 "(defn ^:async do-it [] (try (reset! !mv-force-overwrite-save-target (pr-str (await (epupp.fs/save! " (pr-str target-code) " {:fs/force? true})))) (catch :default e (reset! !mv-force-overwrite-save-target (str \"ERROR: \" (.-message e))))))\n"
                                 "(do-it)\n"
                                 ":started")
                            "(pr-str @!mv-force-overwrite-save-target)"
                            3000))]
      (when (.includes result "ERROR:")
        (throw (js/Error. (str "Target save failed: " result))))
      (-> (expect (.includes result target-name)) (.toBe true)))

    (let [result (js-await (eval-async-and-poll!
                            (str "(def !mv-force-overwrite-result (atom :pending))\n"
                                 "(defn ^:async do-it [] (try (let [r (await (epupp.fs/mv! \"" source-name "\" \"" target-name "\" {:fs/force? true}))] (reset! !mv-force-overwrite-result {:resolved r})) (catch :default e (reset! !mv-force-overwrite-result {:rejected (.-message e)}))))\n"
                                 "(do-it)\n"
                                 ":setup-done")
                            "(let [r @!mv-force-overwrite-result] (cond (= r :pending) :pending (:rejected r) (str \"rejected||\" (:rejected r)) (:resolved r) (str \"resolved||\" (:fs/success (:resolved r)) \"||\" (:fs/from-name (:resolved r)) \"||\" (:fs/to-name (:resolved r))) :else r))"
                            3000))]
      (-> (expect result) (.toBe expected-mv-result)))

    (let [result (js-await (eval-async-and-poll!
                            "(def !mv-force-overwrite-ls (atom :pending))\n                                (defn ^:async do-it [] (reset! !mv-force-overwrite-ls (await (epupp.fs/ls))))\n                                (do-it)\n                                :setup-done"
                            "(pr-str @!mv-force-overwrite-ls)"
                            3000))
          matches (.match result (js/RegExp. "mv_force_overwrite_target\\.cljs" "g"))
          target-count (if matches (.-length matches) 0)]
      (-> (expect target-count) (.toBe 1))
      (-> (expect (.includes result target-name)) (.toBe true))
      (-> (expect (.includes result source-name)) (.toBe false)))

    (let [result (js-await (eval-async-and-poll!
                            (str "(def !mv-force-overwrite-target-code (atom :pending))\n"
                                 "(defn ^:async do-it [] (reset! !mv-force-overwrite-target-code (await (epupp.fs/show \"" target-name "\"))))\n"
                                 "(do-it)\n"
                                 ":setup-done")
                            "@!mv-force-overwrite-target-code"
                            3000))]
      (-> (expect (.includes result source-description)) (.toBe true))
      (-> (expect (.includes result source-marker)) (.toBe true))
      (-> (expect (.includes result target-name)) (.toBe true))
      (-> (expect (.includes result target-description)) (.toBe false))
      (-> (expect (.includes result target-marker)) (.toBe false)))

    (let [result (js-await (eval-async-and-poll!
                            (str "(def !mv-force-overwrite-source-code (atom :pending))\n"
                                 "(defn ^:async do-it [] (reset! !mv-force-overwrite-source-code (await (epupp.fs/show \"" source-name "\"))))\n"
                                 "(do-it)\n"
                                 ":setup-done")
                            "@!mv-force-overwrite-source-code"
                            3000))]
      (-> (expect result) (.toBe "nil")))

    ;; Best-effort cleanup to keep the suite isolated
    (js-await (eval-async-and-poll! cleanup-code "(pr-str @!mv-force-overwrite-cleanup)" 3000))))

(.describe test "REPL FS: mv happy-path operations"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: mv - renames a script"
                   test_mv_renames_a_script)

             (test "REPL FS: mv - with {:fs/force? true} returns result with :fs/from-name and :fs/to-name"
                   test_mv_with_force_returns_from_and_to_names)

             (test "REPL FS: mv - with {:fs/force? true} overwrites an existing normal target"
                   test_mv_with_force_overwrites_existing_target)))
