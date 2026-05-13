(ns fs-mv-test
  "E2E tests for REPL file system mv! happy-path operations"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [sleep eval-in-browser unquote-result setup-browser!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_mv_renames_a_script []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/mv!)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))

  (let [test-code "{:epupp/script-name \"mv-rename-test-original\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns rename-test)"
        setup-result (js-await (eval-in-browser
                                (str "(def !mv-setup (atom :pending))\n                                       (defn ^:async do-it [] (reset! !mv-setup (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n                                       (do-it)\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!mv-setup] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/name r)) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (unquote-result (first (.-values check-result))))
              (.toBe "true||mv_rename_test_original.cljs"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for mv setup save"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !mv-ls (atom :pending))\n                                                (defn ^:async do-it [] (reset! !mv-ls (await (epupp.fs/ls))))\n                                                (do-it)\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!mv-ls)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (.includes (first (.-values check-result)) "mv_rename_test_original.cljs"))
              (.toBe true))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls before mv"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !mv-result (atom :pending))\n                                                (defn ^:async do-it [] (reset! !mv-result (await (epupp.fs/mv! \"mv_rename_test_original.cljs\" \"mv_renamed_script.cljs\" {:fs/force? true}))))\n                                                (do-it)\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!mv-result] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/from-name r) \"||\" (:fs/to-name r)) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (unquote-result (first (.-values check-result))))
              (.toBe "true||mv_rename_test_original.cljs||mv_renamed_script.cljs"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for epupp.fs/mv! result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !ls-after-mv (atom :pending))\n                                                (defn ^:async do-it [] (reset! !ls-after-mv (await (epupp.fs/ls))))\n                                                (do-it)\n                                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!ls-after-mv)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (let [result-str (first (.-values check-result))]
            (-> (expect (.includes result-str "mv_renamed_script.cljs")) (.toBe true))
            (-> (expect (.includes result-str "mv_rename_test_original.cljs")) (.toBe false)))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls after mv"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_mv_with_force_returns_from_and_to_names []
  (let [test-code "{:epupp/script-name \"mv-force-confirm\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns mv-force-confirm)"
        setup-result (js-await (eval-in-browser
                                (str "(def !confirm-mv-setup (atom :pending))\n                                       (defn ^:async do-it [] (reset! !confirm-mv-setup (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n                                       (do-it)\n                                       :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!confirm-mv-setup] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/name r)) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (unquote-result (first (.-values check-result))))
              (.toBe "true||mv_force_confirm.cljs"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for mv force setup save"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !confirm-mv-ls (atom :pending))\n                                (defn ^:async do-it [] (reset! !confirm-mv-ls (await (epupp.fs/ls))))\n                                (do-it)\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!confirm-mv-ls)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (.includes (first (.-values check-result)) "mv_force_confirm.cljs"))
              (.toBe true))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for ls before mv"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [setup-result (js-await (eval-in-browser
                                "(def !confirm-mv-result (atom :pending))\n                                (defn ^:async do-it [] (reset! !confirm-mv-result (await (epupp.fs/mv! \"mv_force_confirm.cljs\" \"mv_force_renamed.cljs\" {:fs/force? true}))))\n                                (do-it)\n                                :setup-done"))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!confirm-mv-result] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/from-name r) \"||\" (:fs/to-name r)) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (unquote-result (first (.-values check-result))))
              (.toBe "true||mv_force_confirm.cljs||mv_force_renamed.cljs"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for mv! result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (let [raw-result (js-await (eval-in-browser "(pr-str @!confirm-mv-result)"))]
    (-> (expect (.-success raw-result)) (.toBe true))
    (let [result-str (first (.-values raw-result))]
      (-> (expect (.includes result-str ":requestId")) (.toBe false))
      (-> (expect (.includes result-str ":source")) (.toBe false))
      (-> (expect (.includes result-str ":type")) (.toBe false))))

  (let [cleanup-result (js-await (eval-in-browser
                                  "(def !mv-force-cleanup (atom :pending))\n                                 (defn ^:async do-it [] (try (await (epupp.fs/rm! \"mv_force_renamed.cljs\")) (catch :default _)) (reset! !mv-force-cleanup :done))\n                                 (do-it)\n                                 :cleanup-started"))]
    (-> (expect (.-success cleanup-result)) (.toBe true)))
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!mv-force-cleanup)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          true
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for mv force cleanup"))
            (do
              (js-await (sleep 20))
              (recur))))))))

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
    (let [cleanup-result (js-await (eval-in-browser cleanup-code))]
      (-> (expect (.-success cleanup-result)) (.toBe true)))

    (let [start (.now js/Date)
          timeout-ms 3000]
      (loop []
        (let [check-result (js-await (eval-in-browser "(pr-str @!mv-force-overwrite-cleanup)"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not= (first (.-values check-result)) ":pending"))
            true
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for mv force overwrite pre-cleanup"))
              (do
                (js-await (sleep 20))
                (recur)))))))

    ;; Create two scripts with different names and different content - save sequentially to avoid races
    (let [save-source-result (js-await (eval-in-browser
                                        (str "(def !mv-force-overwrite-save-source (atom :pending))\n"
                                             "(defn ^:async do-it [] (try (reset! !mv-force-overwrite-save-source (pr-str (await (epupp.fs/save! " (pr-str source-code) " {:fs/force? true})))) (catch :default e (reset! !mv-force-overwrite-save-source (str \"ERROR: \" (.-message e))))))\n"
                                             "(do-it)\n"
                                             ":started")))]
      (-> (expect (.-success save-source-result)) (.toBe true)))

    (let [start (.now js/Date)
          timeout-ms 3000]
      (loop []
        (let [check-result (js-await (eval-in-browser "(pr-str @!mv-force-overwrite-save-source)"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not (or (= (first (.-values check-result)) ":pending")
                            (= (first (.-values check-result)) "\":pending\""))))
            (let [result-str (first (.-values check-result))]
              (when (.includes result-str "ERROR:")
                (throw (js/Error. (str "Source save failed: " result-str))))
              (-> (expect (.includes result-str source-name)) (.toBe true)))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for source save"))
              (do (js-await (sleep 20)) (recur)))))))

    (let [save-target-result (js-await (eval-in-browser
                                        (str "(def !mv-force-overwrite-save-target (atom :pending))\n"
                                             "(defn ^:async do-it [] (try (reset! !mv-force-overwrite-save-target (pr-str (await (epupp.fs/save! " (pr-str target-code) " {:fs/force? true})))) (catch :default e (reset! !mv-force-overwrite-save-target (str \"ERROR: \" (.-message e))))))\n"
                                             "(do-it)\n"
                                             ":started")))]
      (-> (expect (.-success save-target-result)) (.toBe true)))

    (let [start (.now js/Date)
          timeout-ms 3000]
      (loop []
        (let [check-result (js-await (eval-in-browser "(pr-str @!mv-force-overwrite-save-target)"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not (or (= (first (.-values check-result)) ":pending")
                            (= (first (.-values check-result)) "\":pending\""))))
            (let [result-str (first (.-values check-result))]
              (when (.includes result-str "ERROR:")
                (throw (js/Error. (str "Target save failed: " result-str))))
              (-> (expect (.includes result-str target-name)) (.toBe true)))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for target save"))
              (do (js-await (sleep 20)) (recur)))))))

    (let [setup-result (js-await (eval-in-browser
                                  (str "(def !mv-force-overwrite-result (atom :pending))\n"
                                       "(defn ^:async do-it [] (try (let [r (await (epupp.fs/mv! \"" source-name "\" \"" target-name "\" {:fs/force? true}))] (reset! !mv-force-overwrite-result {:resolved r})) (catch :default e (reset! !mv-force-overwrite-result {:rejected (.-message e)}))))\n"
                                       "(do-it)\n"
                                       ":setup-done")))]
      (-> (expect (.-success setup-result)) (.toBe true)))

    (let [start (.now js/Date)
          timeout-ms 3000]
      (loop []
        (let [check-result (js-await (eval-in-browser "(let [r @!mv-force-overwrite-result] (cond (= r :pending) :pending (:rejected r) (str \"rejected||\" (:rejected r)) (:resolved r) (str \"resolved||\" (:fs/success (:resolved r)) \"||\" (:fs/from-name (:resolved r)) \"||\" (:fs/to-name (:resolved r))) :else r))"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not= (first (.-values check-result)) ":pending"))
            (let [result-str (unquote-result (first (.-values check-result)))]
              (-> (expect result-str)
                  (.toBe expected-mv-result)))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for mv force overwrite result"))
              (do
                (js-await (sleep 20))
                (recur)))))))

    (let [setup-result (js-await (eval-in-browser
                                  "(def !mv-force-overwrite-ls (atom :pending))\n                                (defn ^:async do-it [] (reset! !mv-force-overwrite-ls (await (epupp.fs/ls))))\n                                (do-it)\n                                :setup-done"))]
      (-> (expect (.-success setup-result)) (.toBe true)))

    (let [start (.now js/Date)
          timeout-ms 3000]
      (loop []
        (let [check-result (js-await (eval-in-browser "(pr-str @!mv-force-overwrite-ls)"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not= (first (.-values check-result)) ":pending"))
            (let [result-str (first (.-values check-result))
                  matches (.match result-str (js/RegExp. "mv_force_overwrite_target\\.cljs" "g"))
                  target-count (if matches (.-length matches) 0)]
              (-> (expect target-count) (.toBe 1))
              (-> (expect (.includes result-str target-name)) (.toBe true))
              (-> (expect (.includes result-str source-name)) (.toBe false)))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for ls after force overwrite"))
              (do
                (js-await (sleep 20))
                (recur)))))))

    (let [setup-result (js-await (eval-in-browser
                                  (str "(def !mv-force-overwrite-target-code (atom :pending))\n"
                                       "(defn ^:async do-it [] (reset! !mv-force-overwrite-target-code (await (epupp.fs/show \"" target-name "\"))))\n"
                                       "(do-it)\n"
                                       ":setup-done")))]
      (-> (expect (.-success setup-result)) (.toBe true)))

    (let [start (.now js/Date)
          timeout-ms 3000]
      (loop []
        (let [check-result (js-await (eval-in-browser "@!mv-force-overwrite-target-code"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not= (first (.-values check-result)) ":pending"))
            (let [target-code-str (first (.-values check-result))]
              (-> (expect (.includes target-code-str source-description)) (.toBe true))
              (-> (expect (.includes target-code-str source-marker)) (.toBe true))
              (-> (expect (.includes target-code-str target-name)) (.toBe true))
              (-> (expect (.includes target-code-str target-description)) (.toBe false))
              (-> (expect (.includes target-code-str target-marker)) (.toBe false)))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for target show after force overwrite"))
              (do
                (js-await (sleep 20))
                (recur)))))))

    (let [setup-result (js-await (eval-in-browser
                                  (str "(def !mv-force-overwrite-source-code (atom :pending))\n"
                                       "(defn ^:async do-it [] (reset! !mv-force-overwrite-source-code (await (epupp.fs/show \"" source-name "\"))))\n"
                                       "(do-it)\n"
                                       ":setup-done")))]
      (-> (expect (.-success setup-result)) (.toBe true)))

    (let [start (.now js/Date)
          timeout-ms 3000]
      (loop []
        (let [check-result (js-await (eval-in-browser "@!mv-force-overwrite-source-code"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not= (first (.-values check-result)) ":pending"))
            (-> (expect (.-values check-result)) (.toContain "nil"))
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for source show after force overwrite"))
              (do
                (js-await (sleep 20))
                (recur)))))))

    ;; Best-effort cleanup to keep the suite isolated
    (let [cleanup-result (js-await (eval-in-browser cleanup-code))]
      (-> (expect (.-success cleanup-result)) (.toBe true)))

    (let [start (.now js/Date)
          timeout-ms 3000]
      (loop []
        (let [check-result (js-await (eval-in-browser "(pr-str @!mv-force-overwrite-cleanup)"))]
          (if (and (.-success check-result)
                   (seq (.-values check-result))
                   (not= (first (.-values check-result)) ":pending"))
            true
            (if (> (- (.now js/Date) start) timeout-ms)
              (throw (js/Error. "Timeout waiting for mv force overwrite cleanup"))
              (do
                (js-await (sleep 20))
                (recur)))))))))

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
