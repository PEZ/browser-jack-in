(ns ext-dep-cache-test
  "Unit tests for external dependency cache storage accessors."
  (:require ["vitest" :refer [describe test expect]]
            [storage :as storage]))

;; ============================================================
;; Test setup
;; ============================================================

(defn- setup-empty-cache! []
  (swap! storage/!db assoc :storage/ext-dep-cache {}))

(defn- setup-populated-cache! []
  (swap! storage/!db assoc :storage/ext-dep-cache
         {"https://raw.githubusercontent.com/user/repo/abc12301234567890123456789012345678901ab/lib.cljs"
          {:cache/code "(ns lib)"
           :cache/url "https://raw.githubusercontent.com/user/repo/abc12301234567890123456789012345678901ab/lib.cljs"
           :cache/inject []
           :cache/fetched-at 1712419200000
           :cache/schema-version 1}
          "https://raw.githubusercontent.com/other/repo/def45601234567890123456789012345678901cd/utils.cljs"
          {:cache/code "(ns utils)"
           :cache/url "https://raw.githubusercontent.com/other/repo/def45601234567890123456789012345678901cd/utils.cljs"
           :cache/inject ["scittle://replicant.js"]
           :cache/fetched-at 1712419300000
           :cache/schema-version 1}}))

;; ============================================================
;; get-ext-dep-cache tests
;; ============================================================

(defn- test-empty-cache-returns-empty-map []
  (setup-empty-cache!)
  (-> (expect (storage/get-ext-dep-cache))
      (.toEqual {})))

(defn- test-populated-cache-returns-full-map []
  (setup-populated-cache!)
  (let [cache (storage/get-ext-dep-cache)]
    (-> (expect (count (keys cache)))
        (.toBe 2))
    (-> (expect (get cache "https://raw.githubusercontent.com/user/repo/abc12301234567890123456789012345678901ab/lib.cljs"))
        (.toBeTruthy))))


;; ============================================================
;; Initial state test
;; ============================================================

(defn- test-initial-db-has-empty-cache []
  (setup-empty-cache!)
  (let [initial-cache (:storage/ext-dep-cache @storage/!db)]
    (-> (expect initial-cache)
        (.toBeDefined))
    (-> (expect (count (keys initial-cache)))
        (.toBe 0))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "get-ext-dep-cache"
          (fn []
            (test "empty cache returns empty map" test-empty-cache-returns-empty-map)
            (test "populated cache returns full map" test-populated-cache-returns-full-map)
            (test "initial db has empty cache" test-initial-db-has-empty-cache)))

