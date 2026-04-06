(ns git-dep-test
  (:require ["vitest" :refer [describe test expect]]
            [git-dep :as gd]))

;; ============================================================
;; Test SHA
;; ============================================================

(def valid-sha "abcdef0123456789abcdef0123456789abcdef01")
(def valid-sha-upper "ABCDEF0123456789ABCDEF0123456789ABCDEF01")

;; ============================================================
;; validate-sha tests
;; ============================================================

(defn- test-valid-sha []
  (-> (expect (gd/validate-sha valid-sha))
      (.toBe true)))

(defn- test-valid-sha-uppercase []
  (-> (expect (gd/validate-sha valid-sha-upper))
      (.toBe true)))

(defn- test-invalid-sha-too-short []
  (-> (expect (gd/validate-sha "abcdef0"))
      (.toBe false)))

(defn- test-invalid-sha-39-chars []
  (-> (expect (gd/validate-sha "abcdef0123456789abcdef0123456789abcdef0"))
      (.toBe false)))

(defn- test-invalid-sha-branch-name []
  (-> (expect (gd/validate-sha "main"))
      (.toBe false)))

(defn- test-invalid-sha-nil []
  (-> (expect (gd/validate-sha nil))
      (.toBe false)))

(defn- test-invalid-sha-non-hex []
  (-> (expect (gd/validate-sha "zzzzzz0123456789abcdef0123456789abcdef01"))
      (.toBe false)))

;; ============================================================
;; forge-for-host tests
;; ============================================================

(defn- test-forge-github []
  (-> (expect (gd/forge-for-host "github.com"))
      (.toBe :github)))

(defn- test-forge-gist-github []
  (-> (expect (gd/forge-for-host "gist.github.com"))
      (.toBe :gist-github)))

(defn- test-forge-gitlab []
  (-> (expect (gd/forge-for-host "gitlab.com"))
      (.toBe :gitlab)))

(defn- test-forge-codeberg []
  (-> (expect (gd/forge-for-host "codeberg.org"))
      (.toBe :codeberg)))

(defn- test-forge-localhost []
  (-> (expect (gd/forge-for-host "localhost"))
      (.toBe :localhost)))

(defn- test-forge-127 []
  (-> (expect (gd/forge-for-host "127.0.0.1"))
      (.toBe :localhost)))

(defn- test-forge-unknown []
  (-> (expect (gd/forge-for-host "evil.com"))
      (.toBeFalsy)))

;; ============================================================
;; parse-git-dep-url - valid git:// URLs
;; ============================================================

(defn- test-parse-github-url []
  (let [url (str "git://github.com/user/repo@" valid-sha "/path/to/file.cljs")
        parsed (gd/parse-git-dep-url url)]
    (-> (expect parsed) (.toBeTruthy))
    (-> (expect (:git/scheme parsed)) (.toBe :git))
    (-> (expect (:git/host parsed)) (.toBe "github.com"))
    (-> (expect (:git/owner parsed)) (.toBe "user"))
    (-> (expect (:git/repo parsed)) (.toBe "repo"))
    (-> (expect (:git/sha parsed)) (.toBe valid-sha))
    (-> (expect (:git/path parsed)) (.toBe "path/to/file.cljs"))
    (-> (expect (:git/forge parsed)) (.toBe :github))
    (-> (expect (:git/inject-url parsed)) (.toBe url))
    (-> (expect (:git/raw-url parsed))
        (.toBe (str "https://raw.githubusercontent.com/user/repo/" valid-sha "/path/to/file.cljs")))))

(defn- test-parse-gitlab-url []
  (let [url (str "git://gitlab.com/org/project@" valid-sha "/src/lib.cljs")
        parsed (gd/parse-git-dep-url url)]
    (-> (expect parsed) (.toBeTruthy))
    (-> (expect (:git/scheme parsed)) (.toBe :git))
    (-> (expect (:git/host parsed)) (.toBe "gitlab.com"))
    (-> (expect (:git/forge parsed)) (.toBe :gitlab))
    (-> (expect (:git/raw-url parsed))
        (.toBe (str "https://gitlab.com/org/project/-/raw/" valid-sha "/src/lib.cljs")))))

(defn- test-parse-codeberg-url []
  (let [url (str "git://codeberg.org/user/repo@" valid-sha "/script.cljs")
        parsed (gd/parse-git-dep-url url)]
    (-> (expect parsed) (.toBeTruthy))
    (-> (expect (:git/forge parsed)) (.toBe :codeberg))
    (-> (expect (:git/raw-url parsed))
        (.toBe (str "https://codeberg.org/user/repo/raw/commit/" valid-sha "/script.cljs")))))

(defn- test-parse-localhost-url []
  (let [url (str "git://localhost/user/repo@" valid-sha "/file.cljs")
        parsed (gd/parse-git-dep-url url)]
    (-> (expect parsed) (.toBeTruthy))
    (-> (expect (:git/forge parsed)) (.toBe :localhost))
    (-> (expect (:git/raw-url parsed))
        (.toBe (str "http://localhost/user/repo/raw/commit/" valid-sha "/file.cljs")))))

(defn- test-parse-localhost-with-port []
  (let [url (str "git://localhost:8080/user/repo@" valid-sha "/file.cljs")
        parsed (gd/parse-git-dep-url url)]
    (-> (expect parsed) (.toBeTruthy))
    (-> (expect (:git/host parsed)) (.toBe "localhost:8080"))
    (-> (expect (:git/forge parsed)) (.toBe :localhost))
    (-> (expect (:git/raw-url parsed))
        (.toBe (str "http://localhost:8080/user/repo/raw/commit/" valid-sha "/file.cljs")))))

(defn- test-parse-127-url []
  (let [url (str "git://127.0.0.1:3000/user/repo@" valid-sha "/file.cljs")
        parsed (gd/parse-git-dep-url url)]
    (-> (expect parsed) (.toBeTruthy))
    (-> (expect (:git/host parsed)) (.toBe "127.0.0.1:3000"))
    (-> (expect (:git/forge parsed)) (.toBe :localhost))))

;; ============================================================
;; parse-git-dep-url - valid gist:// URLs
;; ============================================================

(defn- test-parse-gist-url []
  (let [url (str "gist://gist.github.com/user/abc123@" valid-sha "/helpers.cljs")
        parsed (gd/parse-git-dep-url url)]
    (-> (expect parsed) (.toBeTruthy))
    (-> (expect (:git/scheme parsed)) (.toBe :gist))
    (-> (expect (:git/host parsed)) (.toBe "gist.github.com"))
    (-> (expect (:git/owner parsed)) (.toBe "user"))
    (-> (expect (:git/repo parsed)) (.toBe "abc123"))
    (-> (expect (:git/sha parsed)) (.toBe valid-sha))
    (-> (expect (:git/path parsed)) (.toBe "helpers.cljs"))
    (-> (expect (:git/forge parsed)) (.toBe :gist-github))
    (-> (expect (:git/raw-url parsed))
        (.toBe (str "https://gist.githubusercontent.com/user/abc123/raw/" valid-sha "/helpers.cljs")))))

;; ============================================================
;; parse-git-dep-url - invalid URLs
;; ============================================================

(defn- test-parse-nil []
  (-> (expect (gd/parse-git-dep-url nil))
      (.toBeFalsy)))

(defn- test-parse-empty-string []
  (-> (expect (gd/parse-git-dep-url ""))
      (.toBeFalsy)))

(defn- test-parse-wrong-scheme []
  (-> (expect (gd/parse-git-dep-url "https://github.com/user/repo"))
      (.toBeFalsy)))

(defn- test-parse-disallowed-host []
  (-> (expect (gd/parse-git-dep-url (str "git://evil.com/user/repo@" valid-sha "/file.cljs")))
      (.toBeFalsy)))

(defn- test-parse-no-sha []
  (-> (expect (gd/parse-git-dep-url "git://github.com/user/repo/file.cljs"))
      (.toBeFalsy)))

(defn- test-parse-branch-instead-of-sha []
  (-> (expect (gd/parse-git-dep-url "git://github.com/user/repo@main/file.cljs"))
      (.toBeFalsy)))

(defn- test-parse-short-sha []
  (-> (expect (gd/parse-git-dep-url "git://github.com/user/repo@abcdef0/file.cljs"))
      (.toBeFalsy)))

(defn- test-parse-missing-path []
  (-> (expect (gd/parse-git-dep-url (str "git://github.com/user/repo@" valid-sha)))
      (.toBeFalsy)))

(defn- test-parse-missing-owner []
  (-> (expect (gd/parse-git-dep-url (str "git://github.com/repo@" valid-sha "/file.cljs")))
      (.toBeFalsy)))

(defn- test-parse-gist-on-gitlab []
  (-> (expect (gd/parse-git-dep-url (str "gist://gitlab.com/user/id@" valid-sha "/file.cljs")))
      (.toBeFalsy)))

(defn- test-parse-gist-on-github-repo []
  (-> (expect (gd/parse-git-dep-url (str "gist://github.com/user/id@" valid-sha "/file.cljs")))
      (.toBeFalsy)))

(defn- test-parse-uppercase-sha []
  (let [url (str "git://github.com/user/repo@" valid-sha-upper "/file.cljs")
        parsed (gd/parse-git-dep-url url)]
    (-> (expect parsed) (.toBeTruthy))
    (-> (expect (:git/sha parsed)) (.toBe valid-sha-upper))))

(defn- test-parse-deep-path []
  (let [url (str "git://github.com/user/repo@" valid-sha "/a/b/c/d/file.cljs")
        parsed (gd/parse-git-dep-url url)]
    (-> (expect parsed) (.toBeTruthy))
    (-> (expect (:git/path parsed)) (.toBe "a/b/c/d/file.cljs"))))

;; ============================================================
;; git-dep-url? predicate tests
;; ============================================================

(defn- test-predicate-valid []
  (-> (expect (gd/git-dep-url? (str "git://github.com/user/repo@" valid-sha "/file.cljs")))
      (.toBe true)))

(defn- test-predicate-invalid []
  (-> (expect (gd/git-dep-url? "https://example.com"))
      (.toBe false)))

(defn- test-predicate-nil []
  (-> (expect (gd/git-dep-url? nil))
      (.toBe false)))

;; ============================================================
;; to-raw-url tests
;; ============================================================

(defn- test-raw-url-github []
  (-> (expect (gd/to-raw-url {:git/forge :github
                               :git/owner "user" :git/repo "repo"
                               :git/sha valid-sha :git/path "lib.cljs"}))
      (.toBe (str "https://raw.githubusercontent.com/user/repo/" valid-sha "/lib.cljs"))))

(defn- test-raw-url-gist []
  (-> (expect (gd/to-raw-url {:git/forge :gist-github
                               :git/owner "user" :git/repo "gist-id"
                               :git/sha valid-sha :git/path "file.cljs"}))
      (.toBe (str "https://gist.githubusercontent.com/user/gist-id/raw/" valid-sha "/file.cljs"))))

(defn- test-raw-url-gitlab []
  (-> (expect (gd/to-raw-url {:git/forge :gitlab
                               :git/owner "org" :git/repo "project"
                               :git/sha valid-sha :git/path "src/core.cljs"}))
      (.toBe (str "https://gitlab.com/org/project/-/raw/" valid-sha "/src/core.cljs"))))

(defn- test-raw-url-codeberg []
  (-> (expect (gd/to-raw-url {:git/forge :codeberg
                               :git/owner "user" :git/repo "repo"
                               :git/sha valid-sha :git/path "script.cljs"}))
      (.toBe (str "https://codeberg.org/user/repo/raw/commit/" valid-sha "/script.cljs"))))

(defn- test-raw-url-localhost []
  (-> (expect (gd/to-raw-url {:git/forge :localhost
                               :git/host "localhost:8080"
                               :git/owner "user" :git/repo "repo"
                               :git/sha valid-sha :git/path "file.cljs"}))
      (.toBe (str "http://localhost:8080/user/repo/raw/commit/" valid-sha "/file.cljs"))))

(defn- test-raw-url-nil-input []
  (-> (expect (gd/to-raw-url nil))
      (.toBeFalsy)))

(defn- test-raw-url-unknown-forge []
  (-> (expect (gd/to-raw-url {:git/forge :bitbucket
                               :git/owner "user" :git/repo "repo"
                               :git/sha valid-sha :git/path "file.cljs"}))
      (.toBeFalsy)))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "git-dep"
  (fn []
    (describe "validate-sha"
      (fn []
        (test "accepts valid 40-char hex SHA" test-valid-sha)
        (test "accepts uppercase hex SHA" test-valid-sha-uppercase)
        (test "rejects short SHA (7 chars)" test-invalid-sha-too-short)
        (test "rejects 39-char SHA" test-invalid-sha-39-chars)
        (test "rejects branch name" test-invalid-sha-branch-name)
        (test "rejects nil" test-invalid-sha-nil)
        (test "rejects non-hex characters" test-invalid-sha-non-hex)))

    (describe "forge-for-host"
      (fn []
        (test "maps github.com to :github" test-forge-github)
        (test "maps gist.github.com to :gist-github" test-forge-gist-github)
        (test "maps gitlab.com to :gitlab" test-forge-gitlab)
        (test "maps codeberg.org to :codeberg" test-forge-codeberg)
        (test "maps localhost to :localhost" test-forge-localhost)
        (test "maps 127.0.0.1 to :localhost" test-forge-127)
        (test "returns nil for unknown host" test-forge-unknown)))

    (describe "parse-git-dep-url"
      (fn []
        (describe "valid git:// URLs"
          (fn []
            (test "parses github.com URL" test-parse-github-url)
            (test "parses gitlab.com URL" test-parse-gitlab-url)
            (test "parses codeberg.org URL" test-parse-codeberg-url)
            (test "parses localhost URL" test-parse-localhost-url)
            (test "parses localhost with port" test-parse-localhost-with-port)
            (test "parses 127.0.0.1 with port" test-parse-127-url)
            (test "handles uppercase SHA" test-parse-uppercase-sha)
            (test "handles deep nested path" test-parse-deep-path)))

        (describe "valid gist:// URLs"
          (fn []
            (test "parses gist.github.com URL" test-parse-gist-url)))

        (describe "invalid URLs"
          (fn []
            (test "returns nil for nil" test-parse-nil)
            (test "returns nil for empty string" test-parse-empty-string)
            (test "returns nil for wrong scheme" test-parse-wrong-scheme)
            (test "returns nil for disallowed host" test-parse-disallowed-host)
            (test "returns nil for URL without SHA" test-parse-no-sha)
            (test "returns nil for branch instead of SHA" test-parse-branch-instead-of-sha)
            (test "returns nil for short SHA" test-parse-short-sha)
            (test "returns nil for missing path" test-parse-missing-path)
            (test "returns nil for missing owner (only 2 segments)" test-parse-missing-owner)
            (test "returns nil for gist:// on gitlab.com" test-parse-gist-on-gitlab)
            (test "returns nil for gist:// on github.com (not gist host)" test-parse-gist-on-github-repo)))))

    (describe "git-dep-url?"
      (fn []
        (test "returns true for valid git:// URL" test-predicate-valid)
        (test "returns false for invalid URL" test-predicate-invalid)
        (test "returns false for nil" test-predicate-nil)))

    (describe "to-raw-url"
      (fn []
        (test "constructs github raw URL" test-raw-url-github)
        (test "constructs gist raw URL" test-raw-url-gist)
        (test "constructs gitlab raw URL" test-raw-url-gitlab)
        (test "constructs codeberg raw URL" test-raw-url-codeberg)
        (test "constructs localhost raw URL with port" test-raw-url-localhost)
        (test "returns nil for nil input" test-raw-url-nil-input)
        (test "returns nil for unknown forge" test-raw-url-unknown-forge)))))

;; ============================================================
;; extract-git-dep-urls tests
;; ============================================================

(defn- test-extract-filters-only-git-and-gist-urls []
  (let [urls ["scittle://replicant.js"
              (str "git://github.com/user/repo@" valid-sha "/lib.cljs")
              "epupp://utils.cljs"
              (str "gist://gist.github.com/user/abc@" valid-sha "/helper.cljs")]
        result (gd/extract-git-dep-urls urls)]
    (-> (expect (count result)) (.toBe 2))
    (-> (expect (.includes (first result) "git://")) (.toBe true))
    (-> (expect (.includes (second result) "gist://")) (.toBe true))))

(defn- test-extract-returns-empty-for-no-git-urls []
  (let [result (gd/extract-git-dep-urls ["scittle://replicant.js" "epupp://lib.cljs"])]
    (-> (expect (count result)) (.toBe 0))))

(defn- test-extract-returns-empty-for-empty-input []
  (-> (expect (count (gd/extract-git-dep-urls []))) (.toBe 0)))

(defn- test-extract-returns-all-when-only-git-urls []
  (let [urls [(str "git://github.com/a/b@" valid-sha "/x.cljs")
              (str "git://gitlab.com/c/d@" valid-sha "/y.cljs")]
        result (gd/extract-git-dep-urls urls)]
    (-> (expect (count result)) (.toBe 2))))

(describe "extract-git-dep-urls"
  (fn []
    (test "filters only git:// and gist:// URLs from mixed list" test-extract-filters-only-git-and-gist-urls)
    (test "returns empty for list with no git dep URLs" test-extract-returns-empty-for-no-git-urls)
    (test "returns empty for empty input" test-extract-returns-empty-for-empty-input)
    (test "returns all when input contains only git URLs" test-extract-returns-all-when-only-git-urls)))

;; ============================================================
;; resolve-and-fetch! tests
;; ============================================================

(defn- ^:async test-resolve-single-dep []
  (let [url (str "git://github.com/user/repo@" valid-sha "/lib.cljs")
        fetch-fn (fn [_raw-url] (js/Promise.resolve "(ns lib)"))
        parse-manifest-fn (fn [_code] {"inject" []})
        result (js-await (gd/resolve-and-fetch!
                          {:inject-urls [url]
                           :git-dep-cache {}
                           :fetch-fn fetch-fn
                           :parse-manifest-fn parse-manifest-fn
                           :now 1700000000000}))]
    (-> (expect (count (keys (:resolved result)))) (.toBe 1))
    (let [entry (get (:resolved result) url)]
      (-> (expect (:cache/code entry)) (.toBe "(ns lib)"))
      (-> (expect (:cache/sha entry)) (.toBe valid-sha))
      (-> (expect (:cache/raw-url entry)) (.toBeTruthy))
      (-> (expect (:cache/fetched-at entry)) (.toBe 1700000000000))
      (-> (expect (:cache/schema-version entry)) (.toBe 1)))
    (-> (expect (count (:errors result))) (.toBe 0))))

(defn- ^:async test-resolve-transitive-deps []
  (let [url-a (str "git://github.com/user/repo@" valid-sha "/lib_a.cljs")
        url-b (str "git://github.com/other/repo@" valid-sha "/lib_b.cljs")
        fetch-fn (fn [raw-url]
                   (if (.includes raw-url "user/repo")
                     (js/Promise.resolve "(ns lib-a)")
                     (js/Promise.resolve "(ns lib-b)")))
        parse-manifest-fn (fn [code]
                            (if (.includes code "lib-a")
                              {"inject" [url-b] "script-name" "lib_a.cljs"}
                              {"inject" [] "script-name" "lib_b.cljs"}))
        result (js-await (gd/resolve-and-fetch!
                          {:inject-urls [url-a]
                           :git-dep-cache {}
                           :fetch-fn fetch-fn
                           :parse-manifest-fn parse-manifest-fn
                           :now 1700000000000}))]
    (-> (expect (count (keys (:resolved result)))) (.toBe 2))
    (-> (expect (get (:resolved result) url-a)) (.toBeTruthy))
    (-> (expect (get (:resolved result) url-b)) (.toBeTruthy))
    (-> (expect (count (:errors result))) (.toBe 0))))

(defn- ^:async test-resolve-fetch-failure-continues-others []
  (let [url-ok (str "git://github.com/user/repo@" valid-sha "/lib.cljs")
        url-fail (str "git://gitlab.com/org/project@" valid-sha "/broken.cljs")
        fetch-fn (fn [raw-url]
                   (if (.includes raw-url "github")
                     (js/Promise.resolve "(ns lib)")
                     (js/Promise.reject (js/Error. "404 Not Found"))))
        parse-manifest-fn (fn [_code] {"inject" []})
        result (js-await (gd/resolve-and-fetch!
                          {:inject-urls [url-ok url-fail]
                           :git-dep-cache {}
                           :fetch-fn fetch-fn
                           :parse-manifest-fn parse-manifest-fn
                           :now 1700000000000}))]
    (-> (expect (get (:resolved result) url-ok)) (.toBeTruthy))
    (-> (expect (count (:errors result))) (.toBeGreaterThanOrEqual 1))
    (-> (expect (:error/type (first (:errors result)))) (.toBe :git-dep/fetch-failed))))

(defn- ^:async test-resolve-skips-cached-urls []
  (let [url (str "git://github.com/user/repo@" valid-sha "/lib.cljs")
        fetch-called (atom false)
        fetch-fn (fn [_raw-url]
                   (reset! fetch-called true)
                   (js/Promise.resolve "(ns lib)"))
        parse-manifest-fn (fn [_code] {"inject" []})
        existing-cache {url {:cache/code "(ns lib-old)"
                             :cache/sha valid-sha
                             :cache/schema-version 1}}
        result (js-await (gd/resolve-and-fetch!
                          {:inject-urls [url]
                           :git-dep-cache existing-cache
                           :fetch-fn fetch-fn
                           :parse-manifest-fn parse-manifest-fn
                           :now 1700000000000}))]
    (-> (expect @fetch-called) (.toBe false))
    (-> (expect (count (keys (:resolved result)))) (.toBe 0))
    (-> (expect (count (:errors result))) (.toBe 0))))

(defn- ^:async test-resolve-invalid-url-produces-parse-error []
  (let [url "git://invalid-url-no-sha"
        fetch-fn (fn [_] (js/Promise.resolve "code"))
        parse-manifest-fn (fn [_] {"inject" []})
        result (js-await (gd/resolve-and-fetch!
                          {:inject-urls [url]
                           :git-dep-cache {}
                           :fetch-fn fetch-fn
                           :parse-manifest-fn parse-manifest-fn
                           :now 1700000000000}))]
    (-> (expect (count (keys (:resolved result)))) (.toBe 0))
    (-> (expect (count (:errors result))) (.toBe 1))
    (-> (expect (:error/type (first (:errors result)))) (.toBe :git-dep/parse-failed))))

(describe "resolve-and-fetch!"
  (fn []
    (test "resolves single dep with correct cache entry shape" test-resolve-single-dep)
    (test "resolves transitive deps from manifest inject" test-resolve-transitive-deps)
    (test "continues resolving when one fetch fails" test-resolve-fetch-failure-continues-others)
    (test "skips URLs already in cache" test-resolve-skips-cached-urls)
    (test "produces parse-failed error for invalid URL" test-resolve-invalid-url-produces-parse-error)))
