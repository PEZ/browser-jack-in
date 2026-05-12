(ns background-actions.ext-dep-actions-test
  "Tests for external dependency action handlers"
  (:require ["vitest" :refer [describe test expect]]
            [background-actions :as bg-actions]))

;; ============================================================
;; Test Fixtures
;; ============================================================

(def base-script
  {:script/id "script-123"
   :script/name "test.cljs"
   :script/description "Test script"
   :script/match ["*://example.com/*"]
   :script/code "(println \"hello\")"
   :script/enabled true
   :script/created "2026-01-01T00:00:00.000Z"
   :script/modified "2026-01-01T00:00:00.000Z"
   :script/run-at "document-idle"
   :script/inject []})

(def uf-data {:system/now 1737100000000})

(def ^:private ext-dep-sha "abcdef0123456789abcdef0123456789abcdef01")

(defn- sample-ext-cache-entry
  [url fetched-at]
  {:cache/code (str ";; cached " url)
   :cache/url url
   :cache/inject []
   :cache/fetched-at fetched-at
   :cache/schema-version 1})

;; ============================================================
;; External Dep Action Tests
;; ============================================================

(defn- test-resolve-uncached-urls-with-uncached-returns-fetch-effect []
  (let [ext-url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        follow-up-actions [[:runtime/ax.re-resolve-on-change [base-script]]]
        state {:storage/ext-dep-cache {}}
        result (bg-actions/handle-action state uf-data
                 [:ext-dep/ax.resolve-uncached-urls [ext-url] follow-up-actions])]
    (-> (expect result) (.toBeTruthy))
    (-> (expect (:uf/fxs result)) (.toBeTruthy))
    (let [fx (first (:uf/fxs result))]
      (-> (expect (first fx)) (.toBe :uf/await))
      (-> (expect (second fx)) (.toBe :ext-dep/fx.fetch-deps)))
    (-> (expect (:uf/dxs result)) (.toBeTruthy))
    (let [dx (first (:uf/dxs result))]
      (-> (expect (first dx)) (.toBe :ext-dep/ax.cache-results))
      (-> (expect (second dx)) (.toBe :uf/prev-result))
      (-> (expect (nth dx 2)) (.toEqual follow-up-actions)))))

(defn- test-resolve-uncached-urls-empty-list-returns-nil []
  (let [state {:storage/ext-dep-cache {}}
        result (bg-actions/handle-action state uf-data
                 [:ext-dep/ax.resolve-uncached-urls []])]
    (-> (expect result) (.toBeFalsy))))

(defn- test-resolve-uncached-urls-all-cached-returns-nil []
  (let [ext-url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        state {:storage/ext-dep-cache {ext-url {:cache/code "(ns lib)" :cache/url ext-url}}}
        result (bg-actions/handle-action state uf-data
                 [:ext-dep/ax.resolve-uncached-urls [ext-url]])]
    (-> (expect result) (.toBeFalsy))))

(defn- test-resolve-uncached-urls-all-cached-returns-follow-up-actions []
  (let [ext-url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        follow-up-actions [[:runtime/ax.re-resolve-on-change [base-script]]]
        state {:storage/ext-dep-cache {ext-url {:cache/code "(ns lib)" :cache/url ext-url}}}
        result (bg-actions/handle-action state uf-data
                 [:ext-dep/ax.resolve-uncached-urls [ext-url] follow-up-actions])]
    (-> (expect result) (.toBeTruthy))
    (-> (expect (:uf/fxs result)) (.toBeFalsy))
    (-> (expect (:uf/dxs result)) (.toEqual follow-up-actions))))

(describe ":ext-dep/ax.resolve-uncached-urls"
          (fn []
            (test "with uncached URLs returns fetch effect and cache-results deferred action"
                  test-resolve-uncached-urls-with-uncached-returns-fetch-effect)
            (test "with empty URL list returns nil" test-resolve-uncached-urls-empty-list-returns-nil)
            (test "with all cached URLs returns nil" test-resolve-uncached-urls-all-cached-returns-nil)
            (test "with all cached URLs and follow-up actions returns those actions immediately"
                  test-resolve-uncached-urls-all-cached-returns-follow-up-actions)))

;; ============================================================
;; External Dep Cache Results Action Tests
;; ============================================================

(defn- test-cache-results-merges-into-cache-and-persists []
  (let [url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        entry {:cache/code "(ns lib)" :cache/url url :cache/schema-version 1}
        fetch-result {:resolved {url entry} :errors []}
        state {:storage/ext-dep-cache {}}
        result (bg-actions/handle-action state uf-data
                 [:ext-dep/ax.cache-results fetch-result])]
    (-> (expect (get-in result [:uf/db :storage/ext-dep-cache url]))
        (.toBeTruthy))
    (-> (expect (:cache/code (get-in result [:uf/db :storage/ext-dep-cache url])))
        (.toBe "(ns lib)"))
    (-> (expect (some #(and (= :storage/fx.persist-ext-dep-cache! (first %))
                            (= (get (second %) url) entry))
                      (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-cache-results-preserves-existing-cache []
  (let [existing-url "https://raw.githubusercontent.com/old/repo/abcdef0123456789abcdef0123456789abcdef01/old.cljs"
        new-url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/new.cljs")
        fetch-result {:resolved {new-url {:cache/code "(ns new)" :cache/url new-url}} :errors []}
        state {:storage/ext-dep-cache {existing-url {:cache/code "(ns old)"}}}
        result (bg-actions/handle-action state uf-data
                 [:ext-dep/ax.cache-results fetch-result])]
    (-> (expect (get-in result [:uf/db :storage/ext-dep-cache existing-url]))
        (.toBeTruthy))
    (-> (expect (get-in result [:uf/db :storage/ext-dep-cache new-url]))
        (.toBeTruthy))))

(defn- test-cache-results-empty-resolved-still-persists []
  (let [fetch-result {:resolved {} :errors []}
        state {:storage/ext-dep-cache {"existing" {:cache/code "old"}}}
        result (bg-actions/handle-action state uf-data
                 [:ext-dep/ax.cache-results fetch-result])]
    (-> (expect (some #(and (= :storage/fx.persist-ext-dep-cache! (first %))
                            (= (second %) {"existing" {:cache/code "old"}}))
                      (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (get-in result [:uf/db :storage/ext-dep-cache "existing"]))
        (.toBeTruthy))))

(defn- test-cache-results-with-errors-broadcasts-banner []
  (let [fetch-result {:resolved {}
                      :errors [{:error/type :ext-dep/fetch-failed
                                :error/message "Failed to fetch dep"}]}
        state {:storage/ext-dep-cache {}}
        result (bg-actions/handle-action state uf-data
                 [:ext-dep/ax.cache-results fetch-result])]
    (-> (expect (count (:uf/fxs result))) (.toBe 2))
    (-> (expect (some #(= :storage/fx.persist-ext-dep-cache! (first %)) (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (some #(= :banner/fx.broadcast-system (first %)) (:uf/fxs result)))
        (.toBeTruthy))))

(defn- test-cache-results-returns-follow-up-actions-after-persist []
  (let [url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        entry {:cache/code "(ns lib)" :cache/url url :cache/schema-version 1}
        fetch-result {:resolved {url entry} :errors []}
        follow-up-actions [[:runtime/ax.re-resolve-on-change [base-script]]]
        state {:storage/ext-dep-cache {}}
        result (bg-actions/handle-action state uf-data
                 [:ext-dep/ax.cache-results fetch-result follow-up-actions])]
    (-> (expect (get-in result [:uf/db :storage/ext-dep-cache url]))
        (.toEqual entry))
    (-> (expect (some #(and (= :storage/fx.persist-ext-dep-cache! (first %))
                            (= (get (second %) url) entry))
                      (:uf/fxs result)))
        (.toBeTruthy))
    (-> (expect (:uf/dxs result))
        (.toEqual follow-up-actions))))

(describe ":ext-dep/ax.cache-results"
  (fn []
    (test "merges resolved entries into cache and persists" test-cache-results-merges-into-cache-and-persists)
    (test "preserves existing cache entries" test-cache-results-preserves-existing-cache)
    (test "empty resolved still triggers persist" test-cache-results-empty-resolved-still-persists)
    (test "with errors broadcasts banner" test-cache-results-with-errors-broadcasts-banner)
    (test "returns follow-up actions after merging and persisting" test-cache-results-returns-follow-up-actions-after-persist)))
