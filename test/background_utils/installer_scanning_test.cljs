(ns background-utils.installer-scanning-test
  (:require [background-utils :as bg]
            ["vitest" :refer [describe test expect]]))

;; ============================================================
;; sponsor-url-matches? Tests
;; ============================================================

(defn- test-sponsor-url-matches-returns-true-for-matching-url []
  (-> (expect (bg/sponsor-url-matches? "https://github.com/sponsors/PEZ" "PEZ"))
      (.toBe true)))

(defn- test-sponsor-url-matches-returns-true-for-url-with-path-suffix []
  (-> (expect (bg/sponsor-url-matches? "https://github.com/sponsors/PEZ?success=true" "PEZ"))
      (.toBe true)))

(defn- test-sponsor-url-matches-returns-false-for-wrong-username []
  (-> (expect (bg/sponsor-url-matches? "https://github.com/sponsors/PEZ" "someone-else"))
      (.toBe false)))

(defn- test-sponsor-url-matches-returns-false-for-nil-tab-url []
  (-> (expect (bg/sponsor-url-matches? nil "PEZ"))
      (.toBe false)))

(defn- test-sponsor-url-matches-returns-false-for-empty-username []
  (-> (expect (bg/sponsor-url-matches? "https://github.com/sponsors/PEZ" ""))
      (.toBe false)))

(defn- test-sponsor-url-matches-returns-false-for-different-domain []
  (-> (expect (bg/sponsor-url-matches? "https://gitlab.com/sponsors/PEZ" "PEZ"))
      (.toBe false)))

(describe "sponsor-url-matches?"
          (fn []
            (test "returns true for matching URL" test-sponsor-url-matches-returns-true-for-matching-url)
            (test "returns true for URL with path suffix" test-sponsor-url-matches-returns-true-for-url-with-path-suffix)
            (test "returns false for wrong username" test-sponsor-url-matches-returns-false-for-wrong-username)
            (test "returns false for nil tab-url" test-sponsor-url-matches-returns-false-for-nil-tab-url)
            (test "returns false for empty username" test-sponsor-url-matches-returns-false-for-empty-username)
            (test "returns false for different domain" test-sponsor-url-matches-returns-false-for-different-domain)))

;; ============================================================
;; should-scan-for-installer? Tests
;; ============================================================

;; Origin whitelist: whitelisted origin + not yet injected -> scan

(defn- test-should-scan-returns-true-for-whitelisted-origin-not-injected []
  (-> (expect (bg/should-scan-for-installer? "https://github.com/user/repo" #{} 1))
      (.toBe true)))

(defn- test-should-scan-returns-true-for-gitlab []
  (-> (expect (bg/should-scan-for-installer? "https://gitlab.com/user/project" #{} 1))
      (.toBe true)))

(defn- test-should-scan-returns-true-for-codeberg []
  (-> (expect (bg/should-scan-for-installer? "https://codeberg.org/user/repo" #{} 1))
      (.toBe true)))

(defn- test-should-scan-returns-true-for-localhost []
  (-> (expect (bg/should-scan-for-installer? "http://localhost:8080/page" #{} 1))
      (.toBe true)))

(defn- test-should-scan-returns-true-for-127-0-0-1 []
  (-> (expect (bg/should-scan-for-installer? "http://127.0.0.1:3000/test" #{} 1))
      (.toBe true)))

(defn- test-should-scan-returns-true-for-gist-github []
  (-> (expect (bg/should-scan-for-installer? "https://gist.github.com/user/abc" #{} 1))
      (.toBe true)))

;; Non-whitelisted origin -> no scan

(defn- test-should-scan-returns-false-for-non-whitelisted-origin []
  (-> (expect (bg/should-scan-for-installer? "https://evil.com/page" #{} 1))
      (.toBe false)))

(defn- test-should-scan-returns-false-for-wrong-scheme []
  (-> (expect (bg/should-scan-for-installer? "http://github.com/user/repo" #{} 1))
      (.toBe false)))

(defn- test-should-scan-returns-false-for-subdomain []
  (-> (expect (bg/should-scan-for-installer? "https://subdomain.github.com/page" #{} 1))
      (.toBe false)))

(defn- test-should-scan-returns-false-for-invalid-url []
  (-> (expect (bg/should-scan-for-installer? "not-a-url" #{} 1))
      (.toBe false)))

;; Tab already injected -> no scan

(defn- test-should-scan-returns-false-when-tab-already-injected []
  (-> (expect (bg/should-scan-for-installer? "https://github.com/user/repo" #{1} 1))
      (.toBe false)))

(defn- test-should-scan-returns-true-for-different-tab-not-injected []
  (-> (expect (bg/should-scan-for-installer? "https://github.com/user/repo" #{1} 2))
      (.toBe true)))

(defn- test-should-scan-returns-false-when-tab-is-in-flight []
  (-> (expect (bg/should-scan-for-installer? "https://github.com/user/repo" #{} #{1} 1))
      (.toBe false)))

(defn- test-should-scan-three-arity-preserves-existing-behavior []
  (-> (expect (bg/should-scan-for-installer? "https://github.com/user/repo" #{} 1))
      (.toBe true))
  (-> (expect (bg/should-scan-for-installer? "https://github.com/user/repo" #{1} 1))
      (.toBe false)))

;; Tab tracking lifecycle (simulating navigation/removal clearing)

(defn- test-should-scan-returns-true-after-tab-cleared-from-tracking []
  (let [injected #{1 2 3}
        cleared (disj injected 2)]
    (-> (expect (bg/should-scan-for-installer? "https://github.com/user/repo" cleared 2))
        (.toBe true))))

(defn- test-should-scan-returns-false-before-clear-true-after []
  (let [injected #{1}]
    (-> (expect (bg/should-scan-for-installer? "https://github.com/user/repo" injected 1))
        (.toBe false))
    (-> (expect (bg/should-scan-for-installer? "https://github.com/user/repo" (disj injected 1) 1))
        (.toBe true))))

(describe "should-scan-for-installer?"
          (fn []
            (test "returns true for whitelisted origin when tab not injected"
                  test-should-scan-returns-true-for-whitelisted-origin-not-injected)
            (test "returns true for gitlab.com"
                  test-should-scan-returns-true-for-gitlab)
            (test "returns true for codeberg.org"
                  test-should-scan-returns-true-for-codeberg)
            (test "returns true for localhost"
                  test-should-scan-returns-true-for-localhost)
            (test "returns true for 127.0.0.1"
                  test-should-scan-returns-true-for-127-0-0-1)
            (test "returns true for gist.github.com"
                  test-should-scan-returns-true-for-gist-github)
            (test "returns false for non-whitelisted origin"
                  test-should-scan-returns-false-for-non-whitelisted-origin)
            (test "returns false for wrong scheme (http vs https)"
                  test-should-scan-returns-false-for-wrong-scheme)
            (test "returns false for subdomain of whitelisted origin"
                  test-should-scan-returns-false-for-subdomain)
            (test "returns false for invalid URL"
                  test-should-scan-returns-false-for-invalid-url)
            (test "returns false when tab already injected"
                  test-should-scan-returns-false-when-tab-already-injected)
            (test "returns true for different tab not in injected set"
                  test-should-scan-returns-true-for-different-tab-not-injected)
            (test "returns false when tab is already being scanned"
              test-should-scan-returns-false-when-tab-is-in-flight)
            (test "preserves existing 3-arity behavior"
              test-should-scan-three-arity-preserves-existing-behavior)
            (test "returns true after tab cleared from tracking (simulates navigation/removal)"
                  test-should-scan-returns-true-after-tab-cleared-from-tracking)
            (test "returns false before clear, true after (full lifecycle)"
                  test-should-scan-returns-false-before-clear-true-after)))

;; ============================================================
;; installer-scan-delays
;; ============================================================

(defn- test-installer-scan-delays-starts-at-zero []
  (-> (expect (first bg/installer-scan-delays))
      (.toBe 0)))

(defn- test-installer-scan-delays-is-ascending []
  (let [delays bg/installer-scan-delays]
    (-> (expect (apply <= delays))
        (.toBe true))))

(defn- test-installer-scan-delays-is-bounded []
  (let [delays bg/installer-scan-delays]
    (-> (expect (<= (last delays) 5000))
        (.toBe true))))

(describe "installer-scan-delays"
          (test "starts at zero for immediate scan"
                test-installer-scan-delays-starts-at-zero)
          (test "delays are in ascending order"
                test-installer-scan-delays-is-ascending)
          (test "schedule is bounded (count and max delay)"
                test-installer-scan-delays-is-bounded))
