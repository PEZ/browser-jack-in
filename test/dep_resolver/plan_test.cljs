(ns dep-resolver.plan-test
  "Tests for resolve-execution-plan happy path, ordering, and step shapes."
  (:require ["vitest" :refer [describe test expect]]
            [clojure.string :as string]
            [dep-resolver :as resolver]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def script-b
  {:script/id "id-b" :script/name "b.cljs" :script/code "(ns b)"
   :script/inject [] :script/enabled true})

(def script-a
  {:script/id "id-a" :script/name "a.cljs" :script/code "(ns a)"
   :script/inject ["epupp://b.cljs"] :script/enabled true})

(def script-d
  {:script/id "id-d" :script/name "d.cljs" :script/code "(ns d)"
   :script/inject [] :script/enabled true})

(def script-c-diamond
  {:script/id "id-c" :script/name "c.cljs" :script/code "(ns c)"
   :script/inject ["epupp://d.cljs"] :script/enabled true})

(def script-b-diamond
  {:script/id "id-b-d" :script/name "b_diamond.cljs" :script/code "(ns b-diamond)"
   :script/inject ["epupp://d.cljs"] :script/enabled true})

(def script-a-diamond
  {:script/id "id-a-d" :script/name "a_diamond.cljs" :script/code "(ns a-diamond)"
   :script/inject ["epupp://b_diamond.cljs" "epupp://c.cljs"] :script/enabled true})

(def script-mixed
  {:script/id "id-mixed" :script/name "mixed.cljs" :script/code "(ns mixed)"
   :script/inject ["scittle://replicant.js" "epupp://b.cljs"] :script/enabled true})

(def script-no-deps
  {:script/id "id-no-deps" :script/name "no_deps.cljs" :script/code "(ns no-deps)"
   :script/inject [] :script/enabled true})

(def script-root-1
  {:script/id "id-r1" :script/name "root1.cljs" :script/code "(ns root1)"
   :script/inject ["epupp://b.cljs"] :script/enabled true})

(def script-root-2
  {:script/id "id-r2" :script/name "root2.cljs" :script/code "(ns root2)"
   :script/inject ["epupp://b.cljs"] :script/enabled true})

(def script-dual-role
  {:script/id "id-dual" :script/name "dual.cljs" :script/code "(ns dual)"
   :script/inject ["epupp://b.cljs"] :script/enabled true
   :script/match ["https://example.com/*"]})

(def script-uses-dual
  {:script/id "id-uses-dual" :script/name "uses_dual.cljs"
   :script/code "(ns uses-dual)"
   :script/inject ["epupp://dual.cljs"] :script/enabled true})

(def script-disabled-lib
  {:script/id "id-disabled" :script/name "disabled_lib.cljs" :script/code "(ns disabled-lib)"
   :script/inject [] :script/enabled false})

(def script-uses-disabled
  {:script/id "id-uses-disabled" :script/name "uses_disabled.cljs"
   :script/code "(ns uses-disabled)"
   :script/inject ["epupp://disabled_lib.cljs"] :script/enabled true})

(def script-builtin
  {:script/id "epupp-builtin-1" :script/name "epupp/sponsor.cljs"
   :script/code "(ns epupp.sponsor)" :script/inject []
   :script/enabled true :script/builtin? true :script/always-enabled? true})

(def script-uses-builtin
  {:script/id "id-uses-builtin" :script/name "uses_builtin.cljs"
   :script/code "(ns uses-builtin)"
   :script/inject ["epupp://epupp/sponsor.cljs"] :script/enabled true})

;; ============================================================
;; resolve-execution-plan tests (happy path)
;; ============================================================

(defn- test-simple-chain []
  (let [all [script-a script-b]
        plan (resolver/resolve-execution-plan [script-a] all)
        steps (:plan/steps plan)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count steps))
        (.toBe 2))
    (-> (expect (:step/type (first steps)))
        (.toBe :library-script))
    (-> (expect (:step/name (first steps)))
        (.toBe "b.cljs"))
    (-> (expect (:step/type (second steps)))
        (.toBe :root-script))
    (-> (expect (:step/name (second steps)))
        (.toBe "a.cljs"))))

(defn- test-diamond-dependency []
  (let [all [script-a-diamond script-b-diamond script-c-diamond script-d]
        plan (resolver/resolve-execution-plan [script-a-diamond] all)
        steps (:plan/steps plan)
        names (mapv :step/name steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count steps))
        (.toBe 4))
    ;; D should appear before B-diamond and C, and only once
    (let [d-idx (.indexOf names "d.cljs")
          b-idx (.indexOf names "b_diamond.cljs")
          c-idx (.indexOf names "c.cljs")
          a-idx (.indexOf names "a_diamond.cljs")]
      (-> (expect (< d-idx b-idx)) (.toBe true))
      (-> (expect (< d-idx c-idx)) (.toBe true))
      (-> (expect (< b-idx a-idx)) (.toBe true))
      (-> (expect (< c-idx a-idx)) (.toBe true)))
    ;; D appears exactly once
    (-> (expect (count (filter #(= "d.cljs" (:step/name %)) steps)))
        (.toBe 1))))

(defn- test-mixed-scittle-and-epupp []
  (let [all [script-mixed script-b]
        plan (resolver/resolve-execution-plan [script-mixed] all)
        steps (:plan/steps plan)
        vendor-steps (filterv #(= :vendor-file (:step/type %)) steps)
        lib-steps (filterv #(= :library-script (:step/type %)) steps)
        root-steps (filterv #(= :root-script (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    ;; Should have vendor files (replicant needs react + replicant plugin)
    (-> (expect (> (count vendor-steps) 0))
        (.toBe true))
    ;; B is a library step
    (-> (expect (count lib-steps))
        (.toBe 1))
    (-> (expect (:step/name (first lib-steps)))
        (.toBe "b.cljs"))
    ;; Mixed is root
    (-> (expect (count root-steps))
        (.toBe 1))
    (-> (expect (:step/name (first root-steps)))
        (.toBe "mixed.cljs"))
    ;; Vendor steps come first
    (let [first-vendor-idx 0
          first-lib-idx (count vendor-steps)]
      (-> (expect (:step/type (nth steps first-vendor-idx)))
          (.toBe :vendor-file))
      (-> (expect (:step/type (nth steps first-lib-idx)))
          (.toBe :library-script)))))

(defn- test-dual-role-script []
  (let [all [script-uses-dual script-dual-role script-b]
        plan (resolver/resolve-execution-plan [script-dual-role script-uses-dual] all)
        steps (:plan/steps plan)
        names (mapv :step/name steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    ;; dual.cljs should appear before uses_dual.cljs
    (let [dual-idx (.indexOf names "dual.cljs")
          uses-idx (.indexOf names "uses_dual.cljs")]
      (-> (expect (< dual-idx uses-idx)) (.toBe true)))
    ;; b.cljs should appear before dual.cljs
    (let [b-idx (.indexOf names "b.cljs")
          dual-idx (.indexOf names "dual.cljs")]
      (-> (expect (< b-idx dual-idx)) (.toBe true)))))

(defn- test-dedup-across-roots []
  (let [all [script-root-1 script-root-2 script-b]
        plan (resolver/resolve-execution-plan [script-root-1 script-root-2] all)
        steps (:plan/steps plan)
        b-steps (filterv #(= "b.cljs" (:step/name %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    ;; b.cljs appears exactly once despite being referenced by both roots
    (-> (expect (count b-steps))
        (.toBe 1))
    (-> (expect (:step/type (first b-steps)))
        (.toBe :library-script))))

(defn- test-disabled-script-as-library []
  (let [all [script-uses-disabled script-disabled-lib]
        plan (resolver/resolve-execution-plan [script-uses-disabled] all)
        steps (:plan/steps plan)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count steps))
        (.toBe 2))
    (-> (expect (:step/name (first steps)))
        (.toBe "disabled_lib.cljs"))
    (-> (expect (:step/type (first steps)))
        (.toBe :library-script))))

(defn- test-builtin-script-as-library []
  (let [all [script-uses-builtin script-builtin]
        plan (resolver/resolve-execution-plan [script-uses-builtin] all)
        steps (:plan/steps plan)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count steps))
        (.toBe 2))
    (-> (expect (:step/name (first steps)))
        (.toBe "epupp/sponsor.cljs"))
    (-> (expect (:step/type (first steps)))
        (.toBe :library-script))))

(defn- test-unknown-protocol-ignored []
  (let [script-with-unknown {:script/id "id-unk" :script/name "unk.cljs"
                              :script/code "(ns unk)"
                              :script/inject ["https://cdn.example.com/lib.js"]
                              :script/enabled true}
        plan (resolver/resolve-execution-plan [script-with-unknown] [script-with-unknown])
        steps (:plan/steps plan)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    ;; Only the root script, no vendor or library steps for unknown URL
    (-> (expect (count steps))
        (.toBe 1))
    (-> (expect (:step/type (first steps)))
        (.toBe :root-script))))

(defn- test-no-deps-script []
  (let [plan (resolver/resolve-execution-plan [script-no-deps] [script-no-deps])
        steps (:plan/steps plan)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count steps))
        (.toBe 1))
    (-> (expect (:step/type (first steps)))
        (.toBe :root-script))
    (-> (expect (:step/name (first steps)))
        (.toBe "no_deps.cljs"))))

(defn- test-vendor-steps-have-correct-shape []
  (let [all [script-mixed script-b]
        plan (resolver/resolve-execution-plan [script-mixed] all)
        vendor-steps (filterv #(= :vendor-file (:step/type %)) (:plan/steps plan))]
    (doseq [step vendor-steps]
      (-> (expect (:step/source step))
          (.toBe :scittle))
      (-> (expect (string/starts-with? (:step/path step) "vendor/"))
          (.toBe true)))))

(defn- test-epupp-steps-have-correct-shape []
  (let [all [script-a script-b]
        plan (resolver/resolve-execution-plan [script-a] all)
        steps (:plan/steps plan)]
    (doseq [step steps]
      (-> (expect (:step/source step))
          (.toBe :epupp))
      (-> (expect (:step/id step))
          (.toBeTruthy))
      (-> (expect (:step/name step))
          (.toBeTruthy))
      (-> (expect (:step/code step))
          (.toBeTruthy)))))

(defn- test-multiple-roots-ordering []
  (let [all [script-root-1 script-root-2 script-b]
        plan (resolver/resolve-execution-plan [script-root-1 script-root-2] all)
        steps (:plan/steps plan)
        names (mapv :step/name steps)
        b-idx (.indexOf names "b.cljs")
        r1-idx (.indexOf names "root1.cljs")
        r2-idx (.indexOf names "root2.cljs")]
    (-> (expect (< b-idx r1-idx)) (.toBe true))
    (-> (expect (< b-idx r2-idx)) (.toBe true))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "dep-resolver"
          (fn []
            (describe "resolve-execution-plan"
                      (fn []
                        (test "resolves simple chain (A depends on B)" test-simple-chain)
                        (test "resolves diamond dependency (A->B,C B->D,C->D, D once)" test-diamond-dependency)
                        (test "resolves mixed scittle:// + epupp:// graph" test-mixed-scittle-and-epupp)
                        (test "handles dual-role scripts (auto-run + library)" test-dual-role-script)
                        (test "deduplicates across multiple roots" test-dedup-across-roots)
                        (test "resolves disabled script as library" test-disabled-script-as-library)
                        (test "resolves built-in script as library" test-builtin-script-as-library)
                        (test "ignores unknown protocol URLs" test-unknown-protocol-ignored)
                        (test "handles script with no dependencies" test-no-deps-script)
                        (test "vendor steps have correct shape" test-vendor-steps-have-correct-shape)
                        (test "epupp steps have correct shape" test-epupp-steps-have-correct-shape)
                        (test "multiple roots: library appears before all roots" test-multiple-roots-ordering)))))
