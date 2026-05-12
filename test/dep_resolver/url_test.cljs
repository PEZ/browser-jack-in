(ns dep-resolver.url-test
  "Tests for classify-inject-url and parse-epupp-url."
  (:require ["vitest" :refer [describe test expect]]
            [dep-resolver :as resolver]))

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

(defn- test-classify-css-epupp-url []
  (-> (expect (resolver/classify-inject-url "epupp://epupp/installer.css"))
      (.toBe :css)))

(defn- test-classify-css-https-url []
  (-> (expect (resolver/classify-inject-url "https://example.com/style.css"))
      (.toBe :css)))

(defn- test-classify-css-ext-dep-url []
  (-> (expect (resolver/classify-inject-url
               (str "https://raw.githubusercontent.com/user/repo/"
                    "abcdef0123456789abcdef0123456789abcdef01"
                    "/theme.css")))
      (.toBe :css)))

(defn- test-classify-epupp-cljs-still-epupp []
  (-> (expect (resolver/classify-inject-url "epupp://some/lib.cljs"))
      (.toBe :epupp)))

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

(defn- test-parse-css-url-no-normalize []
  (-> (expect (resolver/parse-epupp-url "epupp://epupp/installer.css"))
      (.toBe "epupp/installer.css")))

;; ============================================================
;; Test Registration
;; ============================================================

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
                        (test "classifies untrusted HTTPS host as :unknown" test-classify-untrusted-https-as-unknown)
                        (test "classifies epupp:// .css URL as :css" test-classify-css-epupp-url)
                        (test "classifies https:// .css URL as :css" test-classify-css-https-url)
                        (test "classifies ext-dep .css URL as :css (not :ext-dep)" test-classify-css-ext-dep-url)
                        (test "classifies epupp:// .cljs URL as :epupp (no regression)" test-classify-epupp-cljs-still-epupp)))

            (describe "parse-epupp-url"
                      (fn []
                        (test "parses simple epupp:// URL" test-parse-simple-url)
                        (test "normalizes script name" test-parse-url-normalizes-name)
                        (test "returns nil for non-epupp URLs" test-parse-url-nil-for-non-epupp)
                        (test "returns nil for empty name" test-parse-url-nil-for-empty-name)
                        (test "returns nil for nil input" test-parse-url-nil-for-nil)
                        (test "CSS URL preserved without normalization" test-parse-css-url-no-normalize)))))
