(ns e2e.fs-write-rm-test
  "E2E tests for REPL file system rm! operations"
  (:require ["@playwright/test" :refer [test expect]]
            [fixtures.browser :refer [get-extension-id]]
            [fixtures.events :refer [assert-no-errors!]]
            [fs-write-helpers :refer [eval-in-browser eval-async-and-poll! setup-browser!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_rm_deletes_a_script []
  (let [fn-check (js-await (eval-in-browser "(fn? epupp.fs/rm!)"))]
    (-> (expect (.-success fn-check)) (.toBe true))
    (-> (expect (.-values fn-check)) (.toContain "true")))

  (let [test-code "{:epupp/script-name \"delete-test-script\"\n                                   :epupp/auto-run-match \"https://example.com/*\"}\n                                  (ns delete-test)"]
    (js-await (eval-async-and-poll!
               (str "(def !rm-setup (atom :pending))\n (defn ^:async do-it [] (reset! !rm-setup (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n (do-it)\n :setup-done")
               "(pr-str @!rm-setup)"
               3000)))

  (let [result (js-await (eval-async-and-poll!
                          "(def !ls-before-rm (atom :pending))\n (defn ^:async do-it [] (reset! !ls-before-rm (await (epupp.fs/ls))))\n (do-it)\n :setup-done"
                          "(pr-str @!ls-before-rm)"
                          3000))]
    (-> (expect (.includes result "delete_test_script.cljs"))
        (.toBe true)))

  (let [result (js-await (eval-async-and-poll!
                          "(def !rm-result (atom :pending))\n (defn ^:async do-it [] (reset! !rm-result (await (epupp.fs/rm! \"delete_test_script.cljs\"))))\n (do-it)\n :setup-done"
                          "(let [r @!rm-result] (cond (= r :pending) :pending (map? r) (:fs/success r) :else r))"
                          3000))]
    (-> (expect result) (.toBe "true")))

  (let [result (js-await (eval-async-and-poll!
                          "(def !ls-after-rm (atom :pending))\n (defn ^:async do-it [] (reset! !ls-after-rm (await (epupp.fs/ls))))\n (do-it)\n :setup-done"
                          "(pr-str @!ls-after-rm)"
                          3000))]
    (-> (expect (.includes result "delete_test_script.cljs"))
        (.toBe false))))

(defn- ^:async test_rm_rejects_deleting_builtin_scripts []
  (let [result (js-await (eval-async-and-poll!
                          "(def !rm-builtin-result (atom :pending))\n (defn ^:async do-it []\n   (try\n     (let [r (await (epupp.fs/rm! \"epupp/web_userscript_installer.cljs\"))]\n       (reset! !rm-builtin-result {:resolved r}))\n     (catch :default e\n       (reset! !rm-builtin-result {:rejected (.-message e)}))))\n (do-it)\n :setup-done"
                          "(let [r @!rm-builtin-result]\n                                       (cond\n                                         (= r :pending) :not-settled\n                                         (:rejected r) (:rejected r)\n                                         :else :resolved))"
                          3000))]
    (-> (expect result)
        (.toBe "Cannot delete built-in scripts"))))

(defn- ^:async test_rm_with_vector_rejects_when_any_missing []
  (let [code1 "{:epupp/script-name \"bulk-rm-test-1\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-rm-1)"
        code2 "{:epupp/script-name \"bulk-rm-test-2\"\n                               :epupp/auto-run-match \"https://example.com/*\"}\n                              (ns bulk-rm-2)"
        result (js-await (eval-async-and-poll!
                          (str "(def !bulk-rm-setup (atom :pending))\n (defn ^:async do-it []\n   (try\n     (let [r (await (js/Promise.all #js [(epupp.fs/save! " (pr-str code1) " {:fs/force? true})\n                                         (epupp.fs/save! " (pr-str code2) " {:fs/force? true})]))]
       (reset! !bulk-rm-setup {:resolved r}))\n     (catch :default e\n       (reset! !bulk-rm-setup {:rejected (.-message e)}))))\n (do-it)\n :setup-started")
                          "(pr-str @!bulk-rm-setup)"
                          3000))]
    (js/console.log "=== Bulk rm setup result ===" result)
    (-> (expect (.includes result "resolved")) (.toBe true)))

  (let [result (js-await (eval-async-and-poll!
                          "(def !bulk-rm-result (atom :pending))\n (defn ^:async do-it []\n   (try\n     (let [r (await (epupp.fs/rm! [\"bulk_rm_test_1.cljs\" \"bulk_rm_test_2.cljs\" \"does-not-exist.cljs\"]))]\n       (reset! !bulk-rm-result {:resolved r}))\n     (catch :default e\n       (reset! !bulk-rm-result {:rejected (.-message e)}))))\n (do-it)\n :delete-started"
                          "(pr-str @!bulk-rm-result)"
                          3000))]
    (js/console.log "=== Bulk rm result ===" result)
    (-> (expect (.includes result "rejected"))
        (.toBe true))
    (-> (expect (.includes result "does-not-exist.cljs"))
        (.toBe true))
    (-> (expect (or (.includes result "Script not found")
                    (.includes result "not found")
                    (.includes result "does not exist")))
        (.toBe true)))

  (let [result (js-await (eval-async-and-poll!
                          "(def !bulk-rm-after (atom :pending))\n (defn ^:async do-it [] (reset! !bulk-rm-after (await (epupp.fs/ls))))\n (do-it)\n :setup-done"
                          "(pr-str @!bulk-rm-after)"
                          3000))]
    (-> (expect (.includes result "bulk_rm_test_1.cljs"))
        (.toBe false))
    (-> (expect (.includes result "bulk_rm_test_2.cljs"))
        (.toBe false))))

(defn- ^:async test_rm_returns_existed_flag []
  (let [unique-name (str "existed-test-rm-" (.now js/Date))
        normalized-name (-> unique-name
                            (.toLowerCase)
                            (.replace (js/RegExp. "[\\s.-]+" "g") "_")
                            (.replace (js/RegExp. "[^a-z0-9_/]" "g") "")
                            (str ".cljs"))
        test-code (str "{:epupp/script-name \"" unique-name "\"\n"
                       " :epupp/auto-run-match \"https://example.com/*\"}\n"
                       "(ns existed-test)")
        result (js-await (eval-async-and-poll!
                          (str "(def !existed-rm-result (atom {:save :pending :rm :pending}))\n"
                               "(defn ^:async do-it []\n"
                               "  (try\n"
                               "    (let [save-r (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))]\n"
                               "      (swap! !existed-rm-result assoc :save save-r)\n"
                               "      (let [rm-r (await (epupp.fs/rm! \"" normalized-name "\"))]\n"
                               "        (swap! !existed-rm-result assoc :rm rm-r)))\n"
                               "    (catch :default e\n"
                               "      (swap! !existed-rm-result assoc :rm {:rejected (.-message e)}))))\n"
                               "(do-it)\n"
                               ":setup-done")
                          "(let [r @!existed-rm-result] (if (= (:rm r) :pending) :pending (pr-str r)))"
                          3000))]
    (-> (expect (.includes result "rejected"))
        (.toBe false))
    (-> (expect (or (.includes result ":fs/success true")
                    (and (.includes result "#:fs")
                         (.includes result ":success true"))))
        (.toBe true))
    (-> (expect (or (.includes result ":fs/name")
                    (and (.includes result "#:fs")
                         (.includes result ":name"))))
        (.toBe true))
    (-> (expect (or (.includes result ":fs/existed? true")
                    (and (.includes result "#:fs")
                         (.includes result ":existed? true"))))
        (.toBe true))
    (-> (expect (.includes result ":requestId")) (.toBe false))
    (-> (expect (.includes result ":source")) (.toBe false))
    (-> (expect (.includes result ":type")) (.toBe false))))

(defn- ^:async test_no_uncaught_errors_during_fs_tests []
  (let [ext-id (js-await (get-extension-id @!context))
        popup (js-await (.newPage @!context))]
    (js-await (.goto popup (str "chrome-extension://" ext-id "/popup.html")
                     #js {:waitUntil "networkidle"}))
    (js-await (assert-no-errors! popup))
    (js-await (.close popup))))

(.describe test "REPL FS: rm operations"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: rm - deletes a script"
                   test_rm_deletes_a_script)

             (test "REPL FS: rm - rejects deleting built-in scripts"
                   test_rm_rejects_deleting_builtin_scripts)

             (test "REPL FS: rm - vector rejects when any missing"
                   test_rm_with_vector_rejects_when_any_missing)

             (test "REPL FS: rm - returns result with :fs/existed? flag"
                   test_rm_returns_existed_flag)

             (test "REPL FS: rm - no uncaught errors"
                   test_no_uncaught_errors_during_fs_tests)))
