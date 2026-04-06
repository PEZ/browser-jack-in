(ns ext-dep-test
  (:require ["vitest" :refer [describe test expect]]
            [ext-dep :as ed]))

(def valid-sha "abcdef0123456789abcdef0123456789abcdef01")
(def upper-sha "ABCDEF0123456789ABCDEF0123456789ABCDEF01")

;; ============================================================
;; validate-sha tests
;; ============================================================

(defn- test-valid-sha []
  (-> (expect (ed/validate-sha valid-sha)) (.toBe true)))

(defn- test-upper-sha []
  (-> (expect (ed/validate-sha upper-sha)) (.toBe true)))

(defn- test-short-sha []
  (-> (expect (ed/validate-sha "abcdef0")) (.toBe false)))

(defn- test-39-char-sha []
  (-> (expect (ed/validate-sha "abcdef0123456789abcdef0123456789abcdef0")) (.toBe false)))

(defn- test-branch-name []
  (-> (expect (ed/validate-sha "main")) (.toBe false)))

(defn- test-nil-sha []
  (-> (expect (ed/validate-sha nil)) (.toBe false)))

(defn- test-non-hex-sha []
  (-> (expect (ed/validate-sha "ghijkl0123456789abcdef0123456789abcdef01")) (.toBe false)))

;; ============================================================
;; valid-ext-dep-url? tests
;; ============================================================

(defn- test-valid-repo-url []
  (-> (expect (ed/valid-ext-dep-url?
               (str "https://raw.githubusercontent.com/user/repo/" valid-sha "/lib.cljs")))
      (.toBe true)))

(defn- test-valid-gist-url []
  (-> (expect (ed/valid-ext-dep-url?
               (str "https://gist.githubusercontent.com/user/gistid/raw/" valid-sha "/lib.cljs")))
      (.toBe true)))

(defn- test-valid-deep-path []
  (-> (expect (ed/valid-ext-dep-url?
               (str "https://raw.githubusercontent.com/user/repo/" valid-sha "/path/to/lib.cljs")))
      (.toBe true)))

(defn- test-valid-upper-sha-url []
  (-> (expect (ed/valid-ext-dep-url?
               (str "https://raw.githubusercontent.com/user/repo/" upper-sha "/lib.cljs")))
      (.toBe true)))

(defn- test-rejects-nil []
  (-> (expect (ed/valid-ext-dep-url? nil)) (.toBe false)))

(defn- test-rejects-empty []
  (-> (expect (ed/valid-ext-dep-url? "")) (.toBe false)))

(defn- test-rejects-http []
  (-> (expect (ed/valid-ext-dep-url?
               (str "http://raw.githubusercontent.com/user/repo/" valid-sha "/lib.cljs")))
      (.toBe false)))

(defn- test-rejects-untrusted-host []
  (-> (expect (ed/valid-ext-dep-url?
               (str "https://evil.com/user/repo/" valid-sha "/lib.cljs")))
      (.toBe false)))

(defn- test-rejects-branch-name []
  (-> (expect (ed/valid-ext-dep-url?
               "https://raw.githubusercontent.com/user/repo/main/lib.cljs"))
      (.toBe false)))

(defn- test-rejects-short-sha []
  (-> (expect (ed/valid-ext-dep-url?
               "https://raw.githubusercontent.com/user/repo/abcdef0/lib.cljs"))
      (.toBe false)))

(defn- test-rejects-too-few-segments []
  (-> (expect (ed/valid-ext-dep-url?
               (str "https://raw.githubusercontent.com/user/" valid-sha)))
      (.toBe false)))

(defn- test-rejects-gist-without-raw []
  (-> (expect (ed/valid-ext-dep-url?
               (str "https://gist.githubusercontent.com/user/gistid/" valid-sha "/lib.cljs")))
      (.toBe false)))

(defn- test-rejects-git-scheme []
  (-> (expect (ed/valid-ext-dep-url?
               (str "git://github.com/user/repo@" valid-sha "/lib.cljs")))
      (.toBe false)))

(defn- test-rejects-scittle-scheme []
  (-> (expect (ed/valid-ext-dep-url? "scittle://replicant.js"))
      (.toBe false)))

(defn- test-rejects-epupp-scheme []
  (-> (expect (ed/valid-ext-dep-url? "epupp://utils/dom.cljs"))
      (.toBe false)))

;; ============================================================
;; extract-ext-dep-urls tests
;; ============================================================

(def repo-url-a (str "https://raw.githubusercontent.com/user/repo/" valid-sha "/a.cljs"))
(def repo-url-b (str "https://raw.githubusercontent.com/user/repo/" valid-sha "/b.cljs"))

(defn- test-extract-filters-mixed []
  (let [urls ["scittle://replicant.js"
              repo-url-a
              "epupp://utils/dom.cljs"
              repo-url-b
              "https://evil.com/bad.cljs"]
        result (ed/extract-ext-dep-urls urls)]
    (-> (expect (count result)) (.toBe 2))
    (-> (expect (first result)) (.toBe repo-url-a))
    (-> (expect (second result)) (.toBe repo-url-b))))

(defn- test-extract-no-ext-deps []
  (let [result (ed/extract-ext-dep-urls ["scittle://foo.js" "epupp://bar.cljs"])]
    (-> (expect (count result)) (.toBe 0))))

(defn- test-extract-empty-input []
  (let [result (ed/extract-ext-dep-urls [])]
    (-> (expect (count result)) (.toBe 0))))

(defn- test-extract-all-ext-deps []
  (let [result (ed/extract-ext-dep-urls [repo-url-a repo-url-b])]
    (-> (expect (count result)) (.toBe 2))))

;; ============================================================
;; resolve-and-fetch! tests
;; ============================================================

(def test-url-1 (str "https://raw.githubusercontent.com/user/libs/" valid-sha "/lib_a.cljs"))
(def test-url-2 (str "https://raw.githubusercontent.com/user/libs/" valid-sha "/lib_b.cljs"))

(defn- make-fetch-fn [url->code]
  (fn [url]
    (js/Promise.resolve (get url->code url))))

(defn- make-manifest-fn []
  (fn [code]
    (when (and code (re-find #"^\{" code))
      (try
        (js/JSON.parse code)
        (catch :default _ nil)))))

(defn- ^:async test-resolve-single-dep []
  (let [code "(ns lib-a)"
        fetch-fn (make-fetch-fn {test-url-1 code})
        result (js-await (ed/resolve-and-fetch!
                          {:inject-urls [test-url-1]
                           :ext-dep-cache {}
                           :fetch-fn fetch-fn
                           :parse-manifest-fn (make-manifest-fn)
                           :now 1712419200000}))]
    (-> (expect (count (:errors result))) (.toBe 0))
    (-> (expect (count (keys (:resolved result)))) (.toBe 1))
    (let [entry (get (:resolved result) test-url-1)]
      (-> (expect (:cache/code entry)) (.toBe code))
      (-> (expect (:cache/url entry)) (.toBe test-url-1))
      (-> (expect (:cache/fetched-at entry)) (.toBe 1712419200000))
      (-> (expect (:cache/schema-version entry)) (.toBe 1)))))

(defn- ^:async test-resolve-transitive []
  (let [manifest-code (str "{\"inject\": [\"" test-url-2 "\"]}")
        fetch-fn (make-fetch-fn {test-url-1 manifest-code
                                 test-url-2 "(ns lib-b)"})
        result (js-await (ed/resolve-and-fetch!
                          {:inject-urls [test-url-1]
                           :ext-dep-cache {}
                           :fetch-fn fetch-fn
                           :parse-manifest-fn (make-manifest-fn)
                           :now 1712419200000}))]
    (-> (expect (count (:errors result))) (.toBe 0))
    (-> (expect (count (keys (:resolved result)))) (.toBe 2))
    (-> (expect (get (:resolved result) test-url-1)) (.toBeTruthy))
    (-> (expect (get (:resolved result) test-url-2)) (.toBeTruthy))))

(defn- ^:async test-resolve-continues-on-failure []
  (let [fetch-fn (fn [url]
                   (if (= url test-url-1)
                     (js/Promise.reject (js/Error. "network error"))
                     (js/Promise.resolve "(ns lib-b)")))
        result (js-await (ed/resolve-and-fetch!
                          {:inject-urls [test-url-1 test-url-2]
                           :ext-dep-cache {}
                           :fetch-fn fetch-fn
                           :parse-manifest-fn (make-manifest-fn)
                           :now 1712419200000}))]
    (-> (expect (count (:errors result))) (.toBe 1))
    (-> (expect (:error/type (first (:errors result)))) (.toBe :ext-dep/fetch-failed))
    (-> (expect (count (keys (:resolved result)))) (.toBe 1))
    (-> (expect (get (:resolved result) test-url-2)) (.toBeTruthy))))

(defn- ^:async test-resolve-skips-cached []
  (let [fetch-fn (make-fetch-fn {})
        result (js-await (ed/resolve-and-fetch!
                          {:inject-urls [test-url-1]
                           :ext-dep-cache {test-url-1 {:cache/code "(ns lib-a)"}}
                           :fetch-fn fetch-fn
                           :parse-manifest-fn (make-manifest-fn)
                           :now 1712419200000}))]
    (-> (expect (count (:errors result))) (.toBe 0))
    (-> (expect (count (keys (:resolved result)))) (.toBe 0))))

;; ============================================================
;; Test Registration
;; ============================================================

(describe "ext-dep"
          (fn []
            (describe "validate-sha"
                      (fn []
                        (test "accepts valid 40-char hex SHA" test-valid-sha)
                        (test "accepts uppercase hex SHA" test-upper-sha)
                        (test "rejects short SHA (7 chars)" test-short-sha)
                        (test "rejects 39-char SHA" test-39-char-sha)
                        (test "rejects branch name" test-branch-name)
                        (test "rejects nil" test-nil-sha)
                        (test "rejects non-hex characters" test-non-hex-sha)))

            (describe "valid-ext-dep-url?"
                      (fn []
                        (test "accepts raw.githubusercontent.com repo URL" test-valid-repo-url)
                        (test "accepts gist.githubusercontent.com gist URL" test-valid-gist-url)
                        (test "accepts deep nested path" test-valid-deep-path)
                        (test "accepts uppercase SHA in URL" test-valid-upper-sha-url)
                        (test "rejects nil" test-rejects-nil)
                        (test "rejects empty string" test-rejects-empty)
                        (test "rejects HTTP (non-HTTPS)" test-rejects-http)
                        (test "rejects untrusted host" test-rejects-untrusted-host)
                        (test "rejects branch name instead of SHA" test-rejects-branch-name)
                        (test "rejects short SHA" test-rejects-short-sha)
                        (test "rejects too few path segments" test-rejects-too-few-segments)
                        (test "rejects gist URL without /raw/ segment" test-rejects-gist-without-raw)
                        (test "rejects git:// scheme" test-rejects-git-scheme)
                        (test "rejects scittle:// scheme" test-rejects-scittle-scheme)
                        (test "rejects epupp:// scheme" test-rejects-epupp-scheme)))

            (describe "extract-ext-dep-urls"
                      (fn []
                        (test "filters only ext-dep URLs from mixed list" test-extract-filters-mixed)
                        (test "returns empty for list with no ext-dep URLs" test-extract-no-ext-deps)
                        (test "returns empty for empty input" test-extract-empty-input)
                        (test "returns all when input contains only ext-dep URLs" test-extract-all-ext-deps)))

            (describe "resolve-and-fetch!"
                      (fn []
                        (test "resolves single dep with correct cache entry shape" test-resolve-single-dep)
                        (test "resolves transitive deps from manifest inject" test-resolve-transitive)
                        (test "continues resolving when one fetch fails" test-resolve-continues-on-failure)
                        (test "skips URLs already in cache" test-resolve-skips-cached)))))
