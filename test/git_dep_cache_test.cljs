(ns git-dep-cache-test
  "Unit tests for git dependency cache storage accessors."
  (:require ["vitest" :refer [describe test expect]]
            [storage :as storage]))

;; ============================================================
;; Test setup
;; ============================================================

(defn- setup-empty-cache! []
  (swap! storage/!db assoc :storage/git-dep-cache {}))

(defn- setup-populated-cache! []
  (swap! storage/!db assoc :storage/git-dep-cache
         {"git://github.com/user/repo@abc123/lib.cljs"
          {:cache/code "(ns lib)"
           :cache/sha "abc123"
           :cache/raw-url "https://raw.githubusercontent.com/user/repo/abc123/lib.cljs"
           :cache/inject []
           :cache/fetched-at 1712419200000
           :cache/schema-version 1}
          "git://github.com/other/repo@def456/utils.cljs"
          {:cache/code "(ns utils)"
           :cache/sha "def456"
           :cache/raw-url "https://raw.githubusercontent.com/other/repo/def456/utils.cljs"
           :cache/inject ["scittle://replicant.js"]
           :cache/fetched-at 1712419300000
           :cache/schema-version 1}}))

;; ============================================================
;; get-git-dep-cache tests
;; ============================================================

(defn- test-empty-cache-returns-empty-map []
  (setup-empty-cache!)
  (-> (expect (storage/get-git-dep-cache))
      (.toEqual {})))

(defn- test-populated-cache-returns-full-map []
  (setup-populated-cache!)
  (let [cache (storage/get-git-dep-cache)]
    (-> (expect (count (keys cache)))
        (.toBe 2))
    (-> (expect (get cache "git://github.com/user/repo@abc123/lib.cljs"))
        (.toBeTruthy))))

;; ============================================================
;; get-cached-git-dep tests
;; ============================================================

(defn- test-missing-key-returns-nil []
  (setup-empty-cache!)
  (-> (expect (storage/get-cached-git-dep "git://nonexistent/repo@sha/file.cljs"))
      (.toBeUndefined)))

(defn- test-existing-key-returns-entry []
  (setup-populated-cache!)
  (let [entry (storage/get-cached-git-dep "git://github.com/user/repo@abc123/lib.cljs")]
    (-> (expect entry)
        (.toBeTruthy))
    (-> (expect (:cache/code entry))
        (.toBe "(ns lib)"))
    (-> (expect (:cache/sha entry))
        (.toBe "abc123"))))

(defn- test-different-key-returns-correct-entry []
  (setup-populated-cache!)
  (let [entry (storage/get-cached-git-dep "git://github.com/other/repo@def456/utils.cljs")]
    (-> (expect (:cache/code entry))
        (.toBe "(ns utils)"))
    (-> (expect (:cache/sha entry))
        (.toBe "def456"))
    (-> (expect (:cache/inject entry))
        (.toEqual ["scittle://replicant.js"]))))

;; ============================================================
;; Initial state test
;; ============================================================

(defn- test-initial-db-has-empty-cache []
  (setup-empty-cache!)
  (let [initial-cache (:storage/git-dep-cache @storage/!db)]
    (-> (expect initial-cache)
        (.toBeDefined))
    (-> (expect (count (keys initial-cache)))
        (.toBe 0))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "get-git-dep-cache"
          (fn []
            (test "empty cache returns empty map" test-empty-cache-returns-empty-map)
            (test "populated cache returns full map" test-populated-cache-returns-full-map)))

(describe "get-cached-git-dep"
          (fn []
            (test "missing key returns nil/undefined" test-missing-key-returns-nil)
            (test "existing key returns correct entry" test-existing-key-returns-entry)
            (test "different key returns its own entry" test-different-key-returns-correct-entry)))

(describe "git dep cache initial state"
          (fn []
            (test "!db starts with empty git-dep-cache" test-initial-db-has-empty-cache)))
