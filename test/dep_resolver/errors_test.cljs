(ns dep-resolver.errors-test
  "Tests for error detection: missing libraries, self-references, cycles,
   deep chain errors, and transitive suppression."
  (:require ["vitest" :refer [describe test expect]]
            [dep-resolver :as resolver]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def script-missing-dep
  {:script/id "id-missing" :script/name "missing_dep.cljs" :script/code "(ns missing-dep)"
   :script/inject ["epupp://nonexistent.cljs"] :script/enabled true})

(def script-self-ref
  {:script/id "id-self" :script/name "self.cljs" :script/code "(ns self)"
   :script/inject ["epupp://self.cljs"] :script/enabled true})

(def script-x-cycle
  {:script/id "id-x" :script/name "x.cljs" :script/code "(ns x)"
   :script/inject ["epupp://y.cljs"] :script/enabled true})

(def script-y-cycle
  {:script/id "id-y" :script/name "y.cljs" :script/code "(ns y)"
   :script/inject ["epupp://x.cljs"] :script/enabled true})

;; ============================================================
;; Error detection tests
;; ============================================================

(defn- test-missing-library-error []
  (let [all [script-missing-dep]
        plan (resolver/resolve-execution-plan [script-missing-dep] all)
        steps (:plan/steps plan)
        errors (:plan/errors plan)]
    (-> (expect (count errors))
        (.toBe 1))
    (-> (expect (count (filterv #(= :root-script (:step/type %)) steps)))
        (.toBe 0))
    (let [err (first errors)]
      (-> (expect (:error/type err))
          (.toBe :library/not-found))
      (-> (expect (:error/phase err))
          (.toBe :resolve))
      (-> (expect (:error/script-name err))
          (.toBe "missing_dep.cljs"))
      (-> (expect (:error/dep-raw err))
          (.toBe "epupp://nonexistent.cljs"))
      (-> (expect (:error/dep-chain err))
          (.toEqual ["missing_dep.cljs" "nonexistent.cljs"]))
      (-> (expect (:error/message err))
          (.toContain "Library not found: nonexistent.cljs")))))

(defn- test-self-reference-error []
  (let [all [script-self-ref]
        plan (resolver/resolve-execution-plan [script-self-ref] all)
        errors (:plan/errors plan)]
    (-> (expect (count errors))
        (.toBe 1))
    (let [err (first errors)]
      (-> (expect (:error/type err))
          (.toBe :library/self-reference))
      (-> (expect (:error/dep-chain err))
          (.toEqual ["self.cljs" "self.cljs"]))
      (-> (expect (:error/message err))
          (.toContain "Self-reference")))))

(defn- test-cycle-detection-error []
  (let [all [script-x-cycle script-y-cycle]
        plan (resolver/resolve-execution-plan [script-x-cycle] all)
        errors (:plan/errors plan)]
    (-> (expect (count errors))
        (.toBe 1))
    (let [err (first errors)]
      (-> (expect (:error/type err))
          (.toBe :library/cycle))
      (-> (expect (:error/dep-chain err))
          (.toEqual ["x.cljs" "y.cljs" "x.cljs"]))
      (-> (expect (:error/message err))
          (.toContain "Dependency cycle detected")))))

(defn- test-deep-chain-error-message []
  (let [script-leaf {:script/id "id-leaf" :script/name "leaf.cljs"
                      :script/code "(ns leaf)"
                      :script/inject ["epupp://nonexistent.cljs"]
                      :script/enabled true}
        script-mid {:script/id "id-mid" :script/name "mid.cljs"
                     :script/code "(ns mid)"
                     :script/inject ["epupp://leaf.cljs"]
                     :script/enabled true}
        script-top {:script/id "id-top" :script/name "top.cljs"
                     :script/code "(ns top)"
                     :script/inject ["epupp://mid.cljs"]
                     :script/enabled true}
        all [script-top script-mid script-leaf]
        plan (resolver/resolve-execution-plan [script-top] all)
        err (first (:plan/errors plan))]
    (-> (expect (:error/type err))
        (.toBe :library/not-found))
    (-> (expect (:error/dep-chain err))
        (.toEqual ["top.cljs" "mid.cljs" "leaf.cljs" "nonexistent.cljs"]))
    (-> (expect (:error/message err))
        (.toContain "required by leaf.cljs"))
    (-> (expect (:error/message err))
        (.toContain "required by mid.cljs"))
    (-> (expect (:error/message err))
        (.toContain "required by top.cljs"))))

(defn- test-transitive-missing-library-suppresses-failing-subtree []
  (let [leaf-script {:script/id "id-leaf-missing" :script/name "leaf.cljs"
                     :script/code "(ns leaf)"
                     :script/inject ["epupp://missing.cljs"]
                     :script/enabled true}
        root-script {:script/id "id-root-missing" :script/name "root.cljs"
                     :script/code "(ns root)"
                     :script/inject ["epupp://leaf.cljs"]
                     :script/enabled true}
        plan (resolver/resolve-execution-plan [root-script] [root-script leaf-script])
        steps (:plan/steps plan)
        step-names (set (keep :step/name steps))
        errors (:plan/errors plan)]
    (-> (expect (count errors))
        (.toBe 1))
    (-> (expect (:error/type (first errors)))
        (.toBe :library/not-found))
    (-> (expect (contains? step-names "leaf.cljs"))
        (.toBe false))
    (-> (expect (count (filterv #(= :root-script (:step/type %)) steps)))
        (.toBe 0))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "dep-resolver"
          (fn []
            (describe "resolve-execution-plan errors"
                      (fn []
                        (test "produces error for missing library" test-missing-library-error)
                        (test "produces error for self-reference" test-self-reference-error)
                        (test "detects dependency cycles" test-cycle-detection-error)
                        (test "deep chain: error message includes full chain" test-deep-chain-error-message)
                        (test "suppresses failing transitive library subtree" test-transitive-missing-library-suppresses-failing-subtree)))))
