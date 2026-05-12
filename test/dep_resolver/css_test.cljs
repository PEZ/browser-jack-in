(ns dep-resolver.css-test
  "Tests for CSS injection in resolve-execution-plan."
  (:require ["vitest" :refer [describe test expect]]
            [dep-resolver :as resolver]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def script-b
  {:script/id "id-b" :script/name "b.cljs" :script/code "(ns b)"
   :script/inject [] :script/enabled true})

(def script-with-css
  {:script/id "id-css" :script/name "uses_css.cljs" :script/code "(ns uses-css)"
   :script/inject ["epupp://epupp/installer.css" "scittle://replicant.js" "epupp://b.cljs"]
   :script/enabled true})

(def script-with-dup-css
  {:script/id "id-dup-css" :script/name "dup_css.cljs" :script/code "(ns dup-css)"
   :script/inject ["epupp://epupp/installer.css"] :script/enabled true})

;; ============================================================
;; CSS tests
;; ============================================================

(defn- test-css-steps-in-plan []
  (let [all [script-with-css script-b]
        plan (resolver/resolve-execution-plan [script-with-css] all)
        steps (:plan/steps plan)
        css-steps (filterv #(= :css-file (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count css-steps))
        (.toBe 1))
    (let [step (first css-steps)]
      (-> (expect (:step/source step))
          (.toBe :epupp))
      (-> (expect (:step/path step))
          (.toBe "userscripts/epupp/installer.css")))))

(defn- test-css-steps-before-vendor []
  (let [all [script-with-css script-b]
        plan (resolver/resolve-execution-plan [script-with-css] all)
        steps (:plan/steps plan)
        css-steps (filterv #(= :css-file (:step/type %)) steps)
        vendor-steps (filterv #(= :vendor-file (:step/type %)) steps)]
    (when (and (seq css-steps) (seq vendor-steps))
      (let [types (mapv :step/type steps)
            first-css-idx (.indexOf types :css-file)
            first-vendor-idx (.indexOf types :vendor-file)]
        (-> (expect (< first-css-idx first-vendor-idx))
            (.toBe true))))))

(defn- test-css-dedup []
  (let [all [script-with-css script-with-dup-css script-b]
        plan (resolver/resolve-execution-plan [script-with-css script-with-dup-css] all)
        steps (:plan/steps plan)
        css-steps (filterv #(= :css-file (:step/type %)) steps)]
    (-> (expect (count css-steps))
        (.toBe 1))))

(defn- test-css-no-transitive-resolution []
  (let [script-css-only {:script/id "id-css-only" :script/name "css_only.cljs"
                          :script/code "(ns css-only)"
                          :script/inject ["epupp://theme.css"]
                          :script/enabled true}
        plan (resolver/resolve-execution-plan [script-css-only] [script-css-only])
        steps (:plan/steps plan)
        css-steps (filterv #(= :css-file (:step/type %)) steps)
        root-steps (filterv #(= :root-script (:step/type %)) steps)]
    (-> (expect (count (:plan/errors plan)))
        (.toBe 0))
    (-> (expect (count css-steps))
        (.toBe 1))
    (-> (expect (count root-steps))
        (.toBe 1))
    (-> (expect (:step/path (first css-steps)))
        (.toBe "userscripts/theme.css"))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "dep-resolver"
          (fn []
            (describe "resolve-execution-plan with CSS"
                      (fn []
                        (test "CSS inject produces :css-file step" test-css-steps-in-plan)
                        (test "CSS steps ordered before vendor steps" test-css-steps-before-vendor)
                        (test "deduplicates CSS entries across roots" test-css-dedup)
                        (test "CSS entries don't participate in transitive resolution" test-css-no-transitive-resolution)))))
