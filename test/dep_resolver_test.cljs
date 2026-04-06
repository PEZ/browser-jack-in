(ns dep-resolver-test
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

(def script-x-cycle
  {:script/id "id-x" :script/name "x.cljs" :script/code "(ns x)"
   :script/inject ["epupp://y.cljs"] :script/enabled true})

(def script-y-cycle
  {:script/id "id-y" :script/name "y.cljs" :script/code "(ns y)"
   :script/inject ["epupp://x.cljs"] :script/enabled true})

(def script-self-ref
  {:script/id "id-self" :script/name "self.cljs" :script/code "(ns self)"
   :script/inject ["epupp://self.cljs"] :script/enabled true})

(def script-missing-dep
  {:script/id "id-missing" :script/name "missing_dep.cljs" :script/code "(ns missing-dep)"
   :script/inject ["epupp://nonexistent.cljs"] :script/enabled true})

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

;; ============================================================
;; classify-inject-url tests
;; ============================================================

(defn- test-classify-scittle-url []
  (-> (expect (resolver/classify-inject-url "scittle://replicant.js"))
      (.toBe :scittle)))

(defn- test-classify-epupp-url []
  (-> (expect (resolver/classify-inject-url "epupp://utils/dom.cljs"))
      (.toBe :epupp)))

(defn- test-classify-unknown-url []
  (-> (expect (resolver/classify-inject-url "https://cdn.example.com/lib.js"))
      (.toBe :unknown)))

(defn- test-classify-nil []
  (-> (expect (resolver/classify-inject-url nil))
      (.toBe :unknown)))

(defn- test-classify-non-string []
  (-> (expect (resolver/classify-inject-url 42))
      (.toBe :unknown)))

(defn- test-classify-ext-dep-repo-url []
  (-> (expect (resolver/classify-inject-url
               (str "https://raw.githubusercontent.com/user/repo/" "abcdef0123456789abcdef0123456789abcdef01" "/file.cljs")))
      (.toBe :ext-dep)))

(defn- test-classify-ext-dep-gist-url []
  (-> (expect (resolver/classify-inject-url
               (str "https://gist.githubusercontent.com/user/gistid/raw/" "abcdef0123456789abcdef0123456789abcdef01" "/file.cljs")))
      (.toBe :ext-dep)))

(defn- test-classify-untrusted-https-as-unknown []
  (-> (expect (resolver/classify-inject-url "https://evil.com/user/repo/abcdef0123456789abcdef0123456789abcdef01/file.cljs"))
      (.toBe :unknown)))

;; ============================================================
;; parse-epupp-url tests
;; ============================================================

(defn- test-parse-simple-url []
  (-> (expect (resolver/parse-epupp-url "epupp://utils/dom.cljs"))
      (.toBe "utils/dom.cljs")))

(defn- test-parse-url-normalizes-name []
  (-> (expect (resolver/parse-epupp-url "epupp://My Utils.cljs"))
      (.toBe "my_utils.cljs"))
  (-> (expect (resolver/parse-epupp-url "epupp://my-utils"))
      (.toBe "my_utils.cljs")))

(defn- test-parse-url-nil-for-non-epupp []
  (-> (expect (resolver/parse-epupp-url "scittle://pprint.js"))
      (.toBeFalsy)))

(defn- test-parse-url-nil-for-empty-name []
  (-> (expect (resolver/parse-epupp-url "epupp://"))
      (.toBeFalsy)))

(defn- test-parse-url-nil-for-nil []
  (-> (expect (resolver/parse-epupp-url nil))
      (.toBeFalsy)))

;; ============================================================
;; build-catalog tests
;; ============================================================

(defn- test-build-catalog-keys-by-name []
  (let [catalog (resolver/build-catalog [script-a script-b])]
    (-> (expect (get catalog "a.cljs"))
        (.toBeTruthy))
    (-> (expect (:script/id (get catalog "a.cljs")))
        (.toBe "id-a"))
    (-> (expect (get catalog "b.cljs"))
        (.toBeTruthy))))

(defn- test-build-catalog-skips-scripts-without-name []
  (let [catalog (resolver/build-catalog [{:script/id "no-name" :script/code "()"}])]
    (-> (expect (count (keys catalog)))
        (.toBe 0))))

;; ============================================================
;; resolve-execution-plan tests
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

(defn- test-missing-library-error []
  (let [all [script-missing-dep]
        plan (resolver/resolve-execution-plan [script-missing-dep] all)
        errors (:plan/errors plan)]
    (-> (expect (count errors))
        (.toBe 1))
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
        errors (:plan/errors plan)]
    (-> (expect (count errors))
        (.toBe 1))
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

(defn- test-ext-dep-transitive []
  (let [cache {ext-url-a ext-cache-a
               ext-url-b ext-cache-b-depends-on-a}
        script {:script/id "id-trans" :script/name "trans.cljs" :script/code "(ns trans)"
                :script/inject [ext-url-b] :script/enabled true}
        all [script]
        plan (resolver/resolve-execution-plan [script] all cache)
        steps (:plan/steps plan)
        ext-steps (filterv #(= :ext-dep-script (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count ext-steps))
        (.toBe 2))
    (let [urls (mapv :step/url ext-steps)
          a-idx (.indexOf urls ext-url-a)
          b-idx (.indexOf urls ext-url-b)]
      (-> (expect (< a-idx b-idx)) (.toBe true)))))

(defn- test-ext-dep-deep-transitive []
  (let [cache {ext-url-a ext-cache-a
               ext-url-b ext-cache-b-depends-on-a
               ext-url-c ext-cache-c-depends-on-b}
        script {:script/id "id-deep" :script/name "deep.cljs" :script/code "(ns deep)"
                :script/inject [ext-url-c] :script/enabled true}
        all [script]
        plan (resolver/resolve-execution-plan [script] all cache)
        steps (:plan/steps plan)
        ext-steps (filterv #(= :ext-dep-script (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count ext-steps))
        (.toBe 3))
    (let [urls (mapv :step/url ext-steps)
          a-idx (.indexOf urls ext-url-a)
          b-idx (.indexOf urls ext-url-b)
          c-idx (.indexOf urls ext-url-c)]
      (-> (expect (< a-idx b-idx)) (.toBe true))
      (-> (expect (< b-idx c-idx)) (.toBe true)))))

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

(describe "dep-resolver"
          (fn []
            (describe "classify-inject-url"
                      (fn []
                        (test "classifies scittle:// URLs" test-classify-scittle-url)
                        (test "classifies epupp:// URLs" test-classify-epupp-url)
                        (test "classifies unknown URLs" test-classify-unknown-url)
                        (test "classifies nil as unknown" test-classify-nil)
                        (test "classifies non-string as unknown" test-classify-non-string)
                        (test "classifies raw.githubusercontent.com URL as :ext-dep" test-classify-ext-dep-repo-url)
                        (test "classifies gist.githubusercontent.com URL as :ext-dep" test-classify-ext-dep-gist-url)
                        (test "classifies untrusted HTTPS host as :unknown" test-classify-untrusted-https-as-unknown)))

            (describe "parse-epupp-url"
                      (fn []
                        (test "parses simple epupp:// URL" test-parse-simple-url)
                        (test "normalizes script name" test-parse-url-normalizes-name)
                        (test "returns nil for non-epupp URLs" test-parse-url-nil-for-non-epupp)
                        (test "returns nil for empty name" test-parse-url-nil-for-empty-name)
                        (test "returns nil for nil input" test-parse-url-nil-for-nil)))

            (describe "build-catalog"
                      (fn []
                        (test "keys scripts by normalized name" test-build-catalog-keys-by-name)
                        (test "skips scripts without name" test-build-catalog-skips-scripts-without-name)))

            (describe "resolve-execution-plan"
                      (fn []
                        (test "resolves simple chain (A depends on B)" test-simple-chain)
                        (test "resolves diamond dependency (A->B,C B->D,C->D, D once)" test-diamond-dependency)
                        (test "resolves mixed scittle:// + epupp:// graph" test-mixed-scittle-and-epupp)
                        (test "handles dual-role scripts (auto-run + library)" test-dual-role-script)
                        (test "produces error for missing library" test-missing-library-error)
                        (test "produces error for self-reference" test-self-reference-error)
                        (test "detects dependency cycles" test-cycle-detection-error)
                        (test "deduplicates across multiple roots" test-dedup-across-roots)
                        (test "resolves disabled script as library" test-disabled-script-as-library)
                        (test "resolves built-in script as library" test-builtin-script-as-library)
                        (test "ignores unknown protocol URLs" test-unknown-protocol-ignored)
                        (test "handles script with no dependencies" test-no-deps-script)
                        (test "vendor steps have correct shape" test-vendor-steps-have-correct-shape)
                        (test "epupp steps have correct shape" test-epupp-steps-have-correct-shape)
                        (test "deep chain: error message includes full chain" test-deep-chain-error-message)
                        (test "multiple roots: library appears before all roots" test-multiple-roots-ordering)))

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
