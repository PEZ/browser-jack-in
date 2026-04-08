(ns userscript-loader-test
  (:require ["vitest" :refer [describe test expect]]
            [userscript-loader :as loader]))

;; ============================================================
;; pattern->regex
;; ============================================================

(defn- test-all-urls-matches-https []
  (let [regex (loader/pattern->regex "<all_urls>")]
    (-> (expect (.test regex "https://example.com/page"))
        (.toBe true))))

(defn- test-all-urls-matches-http []
  (let [regex (loader/pattern->regex "<all_urls>")]
    (-> (expect (.test regex "http://example.com"))
        (.toBe true))))

(defn- test-all-urls-rejects-ftp []
  (let [regex (loader/pattern->regex "<all_urls>")]
    (-> (expect (.test regex "ftp://example.com"))
        (.toBe false))))

(defn- test-wildcard-pattern []
  (let [regex (loader/pattern->regex "https://example.com/*")]
    (-> (expect (.test regex "https://example.com/page"))
        (.toBe true))))

(defn- test-pattern-escapes-dots []
  (let [regex (loader/pattern->regex "https://example.com/*")]
    (-> (expect (.test regex "https://exampleXcom/page"))
        (.toBe false))))

;; ============================================================
;; url-matches-pattern?
;; ============================================================

(defn- test-exact-url-match []
  (-> (expect (loader/url-matches-pattern? "https://example.com/page" "https://example.com/page"))
      (.toBe true)))

(defn- test-wildcard-url-match []
  (-> (expect (loader/url-matches-pattern? "https://example.com/some/path" "https://example.com/*"))
      (.toBe true)))

(defn- test-no-match-different-domain []
  (-> (expect (loader/url-matches-pattern? "https://other.com/page" "https://example.com/*"))
      (.toBe false)))

(defn- test-subdomain-wildcard []
  (-> (expect (loader/url-matches-pattern? "https://foo.example.com/page" "https://*.example.com/*"))
      (.toBe true)))

;; ============================================================
;; Test registration
;; ============================================================

(describe "Loader: pattern->regex"
          (fn []
            (test "matches https URLs for <all_urls>" test-all-urls-matches-https)
            (test "matches http URLs for <all_urls>" test-all-urls-matches-http)
            (test "rejects ftp URLs for <all_urls>" test-all-urls-rejects-ftp)
            (test "converts wildcard pattern" test-wildcard-pattern)
            (test "escapes dots in patterns" test-pattern-escapes-dots)))

(describe "Loader: url-matches-pattern?"
          (fn []
            (test "matches exact URL" test-exact-url-match)
            (test "matches with wildcard" test-wildcard-url-match)
            (test "rejects different domain" test-no-match-different-domain)
            (test "matches subdomain wildcard" test-subdomain-wildcard)))

;; ============================================================
;; early-timing? (parsed Clojure maps)
;; ============================================================

(defn- test-early-timing-document-start []
  (-> (expect (loader/early-timing? {:script/run-at "document-start"}))
      (.toBe true)))

(defn- test-early-timing-document-end []
  (-> (expect (loader/early-timing? {:script/run-at "document-end"}))
      (.toBe true)))

(defn- test-early-timing-document-idle []
  (-> (expect (loader/early-timing? {:script/run-at "document-idle"}))
      (.toBe false)))

(defn- test-early-timing-nil-defaults-to-idle []
  (-> (expect (loader/early-timing? {}))
      (.toBe false)))

;; ============================================================
;; parsed-script-matches-url?
;; ============================================================

(defn- test-parsed-enabled-early-matching []
  (-> (expect (loader/parsed-script-matches-url?
               {:script/enabled true
                :script/run-at "document-start"
                :script/match ["https://example.com/*"]}
               "https://example.com/page"))
      (.toBe true)))

(defn- test-parsed-disabled-script []
  (-> (expect (loader/parsed-script-matches-url?
               {:script/enabled false
                :script/run-at "document-start"
                :script/match ["https://example.com/*"]}
               "https://example.com/page"))
      (.toBe false)))

(defn- test-parsed-idle-timing []
  (-> (expect (loader/parsed-script-matches-url?
               {:script/enabled true
                :script/run-at "document-idle"
                :script/match ["https://example.com/*"]}
               "https://example.com/page"))
      (.toBe false)))

(defn- test-parsed-non-matching-url []
  (-> (expect (loader/parsed-script-matches-url?
               {:script/enabled true
                :script/run-at "document-start"
                :script/match ["https://other.com/*"]}
               "https://example.com/page"))
      (.toBe false)))

(defn- test-parsed-empty-match []
  (-> (expect (loader/parsed-script-matches-url?
               {:script/enabled true
                :script/run-at "document-start"
                :script/match []}
               "https://example.com/page"))
      (.toBeFalsy)))

(defn- test-parsed-nil-match []
  (-> (expect (loader/parsed-script-matches-url?
               {:script/enabled true
                :script/run-at "document-start"}
               "https://example.com/page"))
      (.toBeFalsy)))

;; ============================================================
;; get-matching-early-scripts
;; ============================================================

(defn- test-get-matching-early-filters-correctly []
  (let [scripts [{:script/id "1" :script/enabled true :script/run-at "document-start"
                  :script/match ["https://example.com/*"]}
                 {:script/id "2" :script/enabled true :script/run-at "document-idle"
                  :script/match ["https://example.com/*"]}
                 {:script/id "3" :script/enabled false :script/run-at "document-start"
                  :script/match ["https://example.com/*"]}
                 {:script/id "4" :script/enabled true :script/run-at "document-end"
                  :script/match ["https://example.com/*"]}]
        result (loader/get-matching-early-scripts scripts "https://example.com/page")]
    ;; Only scripts 1 (doc-start, enabled) and 4 (doc-end, enabled)
    (-> (expect (count result)) (.toBe 2))
    (-> (expect (:script/id (first result))) (.toBe "1"))
    (-> (expect (:script/id (second result))) (.toBe "4"))))

(defn- test-get-matching-early-empty []
  (let [result (loader/get-matching-early-scripts [] "https://example.com/page")]
    (-> (expect (count result)) (.toBe 0))))

(defn- test-get-matching-early-no-match []
  (let [scripts [{:script/id "1" :script/enabled true :script/run-at "document-start"
                  :script/match ["https://other.com/*"]}]
        result (loader/get-matching-early-scripts scripts "https://example.com/page")]
    (-> (expect (count result)) (.toBe 0))))

;; ============================================================
;; errors->js
;; ============================================================

(defn- test-errors-to-js-converts-envelope []
  (let [errors [{:error/type :library/not-found
                 :error/script-name "my/tweaks.cljs"
                 :error/dep-raw "epupp://missing.cljs"
                 :error/dep-chain ["my/tweaks.cljs" "missing.cljs"]
                 :error/message "Library not found: missing.cljs"}]
        result (loader/errors->js errors)
        first-err (aget result 0)]
    (-> (expect (.-errorType first-err)) (.toBe :library/not-found))
    (-> (expect (.-scriptName first-err)) (.toBe "my/tweaks.cljs"))
    (-> (expect (.-depRaw first-err)) (.toBe "epupp://missing.cljs"))
    (-> (expect (.-message first-err)) (.toBe "Library not found: missing.cljs"))
    (-> (expect (.-length (.-depChain first-err))) (.toBe 2))))

(defn- test-errors-to-js-empty []
  (let [result (loader/errors->js [])]
    (-> (expect (.-length result)) (.toBe 0))))

(defn- test-errors-to-js-multiple []
  (let [errors [{:error/type :library/not-found
                 :error/script-name "a.cljs"
                 :error/dep-raw "epupp://x.cljs"
                 :error/message "not found"}
                {:error/type :library/cycle
                 :error/script-name "b.cljs"
                 :error/dep-raw "epupp://y.cljs"
                 :error/message "cycle"}]
        result (loader/errors->js errors)]
    (-> (expect (.-length result)) (.toBe 2))))

;; ============================================================
;; Test registration for new functions
;; ============================================================

(describe "Loader: early-timing?"
          (fn []
            (test "document-start is early" test-early-timing-document-start)
            (test "document-end is early" test-early-timing-document-end)
            (test "document-idle is not early" test-early-timing-document-idle)
            (test "nil run-at defaults to idle" test-early-timing-nil-defaults-to-idle)))

(describe "Loader: parsed-script-matches-url?"
          (fn []
            (test "enabled script with early timing and matching URL" test-parsed-enabled-early-matching)
            (test "disabled script" test-parsed-disabled-script)
            (test "idle timing" test-parsed-idle-timing)
            (test "non-matching URL" test-parsed-non-matching-url)
            (test "empty match patterns" test-parsed-empty-match)
            (test "nil match patterns" test-parsed-nil-match)))

(describe "Loader: get-matching-early-scripts"
          (fn []
            (test "filters to matching early scripts only" test-get-matching-early-filters-correctly)
            (test "returns empty for empty input" test-get-matching-early-empty)
            (test "returns empty when no URL matches" test-get-matching-early-no-match)))

(describe "Loader: errors->js"
          (fn []
            (test "converts error envelope to JS" test-errors-to-js-converts-envelope)
            (test "handles empty errors" test-errors-to-js-empty)
            (test "handles multiple errors" test-errors-to-js-multiple)))
