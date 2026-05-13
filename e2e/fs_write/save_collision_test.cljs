(ns e2e.fs-write.save-collision-test
  "E2E tests for REPL FS save! overwrite/collision behavior"
  (:require ["@playwright/test" :refer [test expect]]
            [fs-write-helpers :refer [sleep eval-in-browser unquote-result
                                      setup-browser! wait-for-script-present!]]))

(def ^:private !context (atom nil))

(defn- ^:async test_save_rejects_when_script_already_exists []
  ;; First create a script
  (let [test-code "{:epupp/script-name \"save-collision-test\"\n                   :epupp/auto-run-match \"https://example.com/*\"}\n                  (ns collision-test)"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-first (atom :pending))\n                                     (defn ^:async do-save [] (reset! !save-first (await (epupp.fs/save! " (pr-str test-code) " {:fs/force? true}))))\n                                     (do-save)\n                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  ;; Wait for first save
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(let [r @!save-first] (cond (= r :pending) :pending (map? r) (str (:fs/success r) \"||\" (:fs/name r)) :else r))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          (-> (expect (unquote-result (first (.-values check-result))))
              (.toBe "true||save_collision_test.cljs"))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for initial save"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  (js-await (wait-for-script-present! "save_collision_test.cljs" 3000))

  ;; Now try to save again WITHOUT force - should reject
  (let [new-code "{:epupp/script-name \"save-collision-test\"\n                  :epupp/auto-run-match \"https://example.com/*\"}\n                 (ns collision-test-v2)\n                 (js/console.log \"This should not overwrite!\")"
        setup-result (js-await (eval-in-browser
                                (str "(def !save-collision-result (atom :pending))\n                                     (defn ^:async do-save []\n                                       (try\n                                         (let [r (await (epupp.fs/save! " (pr-str new-code) "))]\n                                           (reset! !save-collision-result {:resolved r}))\n                                         (catch :default e\n                                           (reset! !save-collision-result {:rejected (.-message e)}))))\n                                     (do-save)\n                                     :setup-done")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser
                                    "(let [r @!save-collision-result]
                                       (cond
                                         (= r :pending) :not-settled
                                         (:rejected r) (:rejected r)
                                         :else :resolved))"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result)))
          (let [result-str (unquote-result (first (.-values check-result)))]
            (if (= result-str ":not-settled")
              (if (> (- (.now js/Date) start) timeout-ms)
                (throw (js/Error. "Timeout waiting for save collision result"))
                (do
                  (js-await (sleep 20))
                  (recur)))
              (-> (expect result-str)
                  (.toBe "Script already exists: save_collision_test.cljs"))))
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save collision result"))
            (do
              (js-await (sleep 20))
              (recur)))))))

  ;; Cleanup
  (let [cleanup-result (js-await (eval-in-browser
                                  "(def !save-collision-cleanup (atom :pending))\n                                     (defn ^:async do-cleanup []\n                                       (try\n                                         (await (epupp.fs/rm! \"save_collision_test.cljs\"))\n                                         (reset! !save-collision-cleanup :done)\n                                         (catch :default _\n                                           (reset! !save-collision-cleanup :done))))\n                                     (do-cleanup)\n                                     :cleanup-started"))]
    (-> (expect (.-success cleanup-result)) (.toBe true)))
  (let [start (.now js/Date)
        timeout-ms 3000]
    (loop []
      (let [check-result (js-await (eval-in-browser "(pr-str @!save-collision-cleanup)"))]
        (if (and (.-success check-result)
                 (seq (.-values check-result))
                 (not= (first (.-values check-result)) ":pending"))
          true
          (if (> (- (.now js/Date) start) timeout-ms)
            (throw (js/Error. "Timeout waiting for save collision cleanup"))
            (do
              (js-await (sleep 20))
              (recur))))))))

(defn- ^:async test_save_force_update_preserves_script_id []
  ;; Create a script via REPL FS
  (let [test-code-v1 "{:epupp/script-name \"id-preserve-test\"\n                     :epupp/auto-run-match \"https://example.com/*\"}\n                    (ns id-test)\n                    (js/console.log \"Version 1\")"
        setup-result (js-await (eval-in-browser
                                (str "(defn ^:async do-save-v1 [] (await (epupp.fs/save! " (pr-str test-code-v1) " {:fs/force? true})) :v1-done)\n"
                                     "(do-save-v1)\n")))]
    (-> (expect (.-success setup-result)) (.toBe true)))

  ;; Wait for script to appear
  (js-await (wait-for-script-present! "id_preserve_test.cljs" 3000))

  ;; Get script ID via ls
  (let [id1-result (js-await (eval-in-browser
                              "(defn ^:async do-get-id []\n                                   (let [scripts (await (epupp.fs/ls))\n                                         s (first (filter #(= (:fs/name %) \"id_preserve_test.cljs\") scripts))]\n                                     (pr-str (:fs/id s))))\n                                 (do-get-id)"))]
    (-> (expect (.-success id1-result)) (.toBe true))
    (let [id1 (unquote-result (first (.-values id1-result)))]
      (-> (expect id1) (.not.toBeNull))

      ;; Force-save v2 with same name but different content
      (let [test-code-v2 "{:epupp/script-name \"id-preserve-test\"\n                       :epupp/auto-run-match \"https://example.com/*\"}\n                      (ns id-test)\n                      (js/console.log \"Version 2 - UPDATED\")"
            save2-result (js-await (eval-in-browser
                                    (str "(defn ^:async do-save-v2 [] (await (epupp.fs/save! " (pr-str test-code-v2) " {:fs/force? true})) :v2-done)\n"
                                         "(do-save-v2)\n")))]
        (-> (expect (.-success save2-result)) (.toBe true)))


      ;; Get script ID again
      (let [id2-result (js-await (eval-in-browser
                                  "(defn ^:async do-get-id2 []\n                                     (let [scripts (await (epupp.fs/ls))\n                                           s (first (filter #(= (:fs/name %) \"id_preserve_test.cljs\") scripts))]\n                                       (pr-str (:fs/id s))))\n                                   (do-get-id2)"))]
        (-> (expect (.-success id2-result)) (.toBe true))
        (let [id2 (unquote-result (first (.-values id2-result)))]
          ;; IDs MUST be equal - the force save should update, not delete+create
          ;; This assertion should FAIL to expose the bug
          (-> (expect id1) (.toBe id2))))))

  ;; Cleanup
  (let [cleanup-result (js-await (eval-in-browser
                                  "(defn ^:async do-cleanup []\n                                     (try (await (epupp.fs/rm! \"id_preserve_test.cljs\")) :cleanup-done\n                                       (catch :default _ :cleanup-done)))\n                                   (do-cleanup)"))]
    (-> (expect (.-success cleanup-result)) (.toBe true))))

(.describe test "REPL FS: save - collision & overwrite"
           (fn []
             (.beforeAll test
                         (^:async fn []
                           (reset! !context (js-await (setup-browser!)))))

             (.afterAll test
                        (fn []
                          (when @!context
                            (.close @!context))))

             (test "REPL FS: save - rejects when script with same name already exists"
                   test_save_rejects_when_script_already_exists)

             (test "REPL FS: save - force update preserves script ID"
                   test_save_force_update_preserves_script_id)))
