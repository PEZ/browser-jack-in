(ns dep-resolver.ext-deps-test
  "Tests for external dependency resolution (ext-dep protocol)."
  (:require ["vitest" :refer [describe test expect]]
            [dep-resolver :as resolver]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def script-b
  {:script/id "id-b" :script/name "b.cljs" :script/code "(ns b)"
   :script/inject [] :script/enabled true})

(def ext-url-a
  "https://raw.githubusercontent.com/user/libs/abcdef0123456789abcdef0123456789abcdef01/lib_a.cljs")

(def ext-url-b
  "https://raw.githubusercontent.com/user/libs/abcdef0123456789abcdef0123456789abcdef01/lib_b.cljs")

(def ext-url-c
  "https://raw.githubusercontent.com/user/libs/abcdef0123456789abcdef0123456789abcdef01/lib_c.cljs")

(def ext-cache-a
  {:cache/code "(ns lib-a)"
   :cache/url ext-url-a
   :cache/inject []
   :cache/fetched-at 1712419200000
   :cache/schema-version 1})

(def ext-cache-b-depends-on-a
  {:cache/code "(ns lib-b (:require [lib-a]))"
   :cache/url ext-url-b
   :cache/inject [ext-url-a]
   :cache/fetched-at 1712419200000
   :cache/schema-version 1})

(def ext-cache-c-depends-on-b
  {:cache/code "(ns lib-c (:require [lib-b]))"
   :cache/url ext-url-c
   :cache/inject [ext-url-b]
   :cache/fetched-at 1712419200000
   :cache/schema-version 1})

(def ext-cache-cycle-a
  {:cache/code "(ns cycle-a)"
   :cache/url ext-url-a
   :cache/inject [ext-url-b]
   :cache/fetched-at 1712419200000
   :cache/schema-version 1})

(def ext-cache-cycle-b
  {:cache/code "(ns cycle-b)"
   :cache/url ext-url-b
   :cache/inject [ext-url-a]
   :cache/fetched-at 1712419200000
   :cache/schema-version 1})

(def ext-cache-with-scittle
  {:cache/code "(ns lib-with-scittle)"
   :cache/url ext-url-a
   :cache/inject ["scittle://replicant.js"]
   :cache/fetched-at 1712419200000
   :cache/schema-version 1})

(def script-depends-on-ext
  {:script/id "id-ext-user" :script/name "ext_user.cljs" :script/code "(ns ext-user)"
   :script/inject [ext-url-a] :script/enabled true})

(def script-depends-on-ext-and-epupp
  {:script/id "id-mixed-ext" :script/name "mixed_ext.cljs" :script/code "(ns mixed-ext)"
   :script/inject ["scittle://replicant.js" "epupp://b.cljs" ext-url-a] :script/enabled true})

;; ============================================================
;; External dependency tests
;; ============================================================

(defn- test-ext-dep-cached-produces-step []
  (let [cache {ext-url-a ext-cache-a}
        all [script-depends-on-ext]
        plan (resolver/resolve-execution-plan [script-depends-on-ext] all cache)
        steps (:plan/steps plan)
        ext-steps (filterv #(= :ext-dep-script (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count ext-steps))
        (.toBe 1))
    (let [step (first ext-steps)]
      (-> (expect (:step/url step))
          (.toBe ext-url-a))
      (-> (expect (:step/code step))
          (.toBe "(ns lib-a)"))
      (-> (expect (:step/source step))
          (.toBe :ext)))))

(defn- test-ext-dep-cache-miss-produces-error []
  (let [cache {}
        all [script-depends-on-ext]
        plan (resolver/resolve-execution-plan [script-depends-on-ext] all cache)
        steps (:plan/steps plan)
        errors (:plan/errors plan)]
    (-> (expect (count errors))
        (.toBe 1))
    (-> (expect (count (filterv #(= :root-script (:step/type %)) steps)))
        (.toBe 0))
    (let [err (first errors)]
      (-> (expect (:error/type err))
          (.toBe :ext-dep/cache-miss))
      (-> (expect (:error/phase err))
          (.toBe :resolve))
      (-> (expect (:error/dep-raw err))
          (.toBe ext-url-a))
      (-> (expect (:error/message err))
          (.toContain "External dependency not in cache")))))

(defn- test-ext-dep-nil-cache-produces-error []
  (let [all [script-depends-on-ext]
        plan (resolver/resolve-execution-plan [script-depends-on-ext] all nil)
        errors (:plan/errors plan)]
    (-> (expect (count errors))
        (.toBe 1))
    (-> (expect (:error/type (first errors)))
        (.toBe :ext-dep/cache-miss))))

(defn- assert-ext-dep-ordering [cache inject-url expected-count ordered-urls]
  (let [script {:script/id "id-test" :script/name "test.cljs" :script/code "(ns test)"
                :script/inject [inject-url] :script/enabled true}
        all [script]
        plan (resolver/resolve-execution-plan [script] all cache)
        steps (:plan/steps plan)
        ext-steps (filterv #(= :ext-dep-script (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count ext-steps))
        (.toBe expected-count))
    (let [urls (mapv :step/url ext-steps)]
      (doseq [i (range (dec (count ordered-urls)))]
        (-> (expect (< (.indexOf urls (nth ordered-urls i))
                       (.indexOf urls (nth ordered-urls (inc i)))))
            (.toBe true))))))

(defn- test-ext-dep-transitive []
  (assert-ext-dep-ordering
   {ext-url-a ext-cache-a
    ext-url-b ext-cache-b-depends-on-a}
   ext-url-b
   2
   [ext-url-a ext-url-b]))

(defn- test-ext-dep-deep-transitive []
  (assert-ext-dep-ordering
   {ext-url-a ext-cache-a
    ext-url-b ext-cache-b-depends-on-a
    ext-url-c ext-cache-c-depends-on-b}
   ext-url-c
   3
   [ext-url-a ext-url-b ext-url-c]))

(defn- test-ext-dep-mixed-graph []
  (let [cache {ext-url-a ext-cache-a}
        all [script-depends-on-ext-and-epupp script-b]
        plan (resolver/resolve-execution-plan [script-depends-on-ext-and-epupp] all cache)
        steps (:plan/steps plan)
        vendor-steps (filterv #(= :vendor-file (:step/type %)) steps)
        ext-steps (filterv #(= :ext-dep-script (:step/type %)) steps)
        lib-steps (filterv #(= :library-script (:step/type %)) steps)
        root-steps (filterv #(= :root-script (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (> (count vendor-steps) 0)) (.toBe true))
    (-> (expect (count ext-steps)) (.toBe 1))
    (-> (expect (count lib-steps)) (.toBe 1))
    (-> (expect (count root-steps)) (.toBe 1))
    (let [first-vendor-idx 0
          last-step-idx (dec (count steps))]
      (-> (expect (:step/type (nth steps first-vendor-idx)))
          (.toBe :vendor-file))
      (-> (expect (:step/type (nth steps last-step-idx)))
          (.toBe :root-script)))))

(defn- test-ext-dep-cycle-detection []
  (let [cache {ext-url-a ext-cache-cycle-a
               ext-url-b ext-cache-cycle-b}
        script {:script/id "id-cyc" :script/name "cyc.cljs" :script/code "(ns cyc)"
                :script/inject [ext-url-a] :script/enabled true}
        all [script]
        plan (resolver/resolve-execution-plan [script] all cache)
        errors (:plan/errors plan)]
    (-> (expect (count errors))
        (.toBe 1))
    (let [err (first errors)]
      (-> (expect (:error/type err))
          (.toBe :ext-dep/cycle))
      (-> (expect (:error/message err))
          (.toContain "Dependency cycle detected")))))

(defn- test-ext-dep-with-scittle-inject []
  (let [cache {ext-url-a ext-cache-with-scittle}
        all [script-depends-on-ext]
        plan (resolver/resolve-execution-plan [script-depends-on-ext] all cache)
        steps (:plan/steps plan)
        vendor-steps (filterv #(= :vendor-file (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (> (count vendor-steps) 0))
        (.toBe true))))

(defn- test-ext-dep-dedup-across-roots []
  (let [cache {ext-url-a ext-cache-a}
        root-1 {:script/id "id-gr1" :script/name "gr1.cljs" :script/code "(ns gr1)"
                :script/inject [ext-url-a] :script/enabled true}
        root-2 {:script/id "id-gr2" :script/name "gr2.cljs" :script/code "(ns gr2)"
                :script/inject [ext-url-a] :script/enabled true}
        all [root-1 root-2]
        plan (resolver/resolve-execution-plan [root-1 root-2] all cache)
        steps (:plan/steps plan)
        ext-steps (filterv #(= :ext-dep-script (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count ext-steps))
        (.toBe 1))))

(defn- test-ext-dep-step-shape []
  (let [cache {ext-url-a ext-cache-a}
        all [script-depends-on-ext]
        plan (resolver/resolve-execution-plan [script-depends-on-ext] all cache)
        ext-step (first (filterv #(= :ext-dep-script (:step/type %)) (:plan/steps plan)))]
    (-> (expect (:step/type ext-step)) (.toBe :ext-dep-script))
    (-> (expect (:step/url ext-step)) (.toBe ext-url-a))
    (-> (expect (:step/code ext-step)) (.toBe "(ns lib-a)"))
    (-> (expect (:step/source ext-step)) (.toBe :ext))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "dep-resolver"
          (fn []
            (describe "resolve-execution-plan with ext deps"
                      (fn []
                        (test "cached ext dep produces :ext-dep-script step" test-ext-dep-cached-produces-step)
                        (test "cache miss produces :ext-dep/cache-miss error" test-ext-dep-cache-miss-produces-error)
                        (test "nil cache produces cache-miss error" test-ext-dep-nil-cache-produces-error)
                        (test "transitive ext deps resolve in order" test-ext-dep-transitive)
                        (test "deep transitive ext deps (A->B->C) resolve in order" test-ext-dep-deep-transitive)
                        (test "mixed graph: scittle + epupp + ext resolves correctly" test-ext-dep-mixed-graph)
                        (test "detects cycles across ext deps" test-ext-dep-cycle-detection)
                        (test "ext dep with scittle inject produces vendor steps" test-ext-dep-with-scittle-inject)
                        (test "deduplicates ext deps across multiple roots" test-ext-dep-dedup-across-roots)
                        (test "ext dep steps have correct shape" test-ext-dep-step-shape)))))
