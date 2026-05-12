(ns dep-resolver.catalog-test
  "Tests for build-catalog."
  (:require ["vitest" :refer [describe test expect]]
            [dep-resolver :as resolver]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def script-a
  {:script/id "id-a" :script/name "a.cljs" :script/code "(ns a)"
   :script/inject ["epupp://b.cljs"] :script/enabled true})

(def script-b
  {:script/id "id-b" :script/name "b.cljs" :script/code "(ns b)"
   :script/inject [] :script/enabled true})

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
;; Test Registration
;; ============================================================

(describe "dep-resolver"
          (fn []
            (describe "build-catalog"
                      (fn []
                        (test "keys scripts by normalized name" test-build-catalog-keys-by-name)
                        (test "skips scripts without name" test-build-catalog-skips-scripts-without-name)))))
