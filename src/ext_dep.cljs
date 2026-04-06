(ns ext-dep
  "Pure validator and fetcher for external dependency URLs.
   Validates raw HTTPS URLs from trusted content hosts, pinned to full SHA commits.
   All validation functions are pure - no side effects, no chrome APIs."
  (:require [clojure.string :as string]))

;; ============================================================
;; Constants
;; ============================================================

(def trusted-hosts
  "Set of hostnames allowed for external dependency URLs (GitHub only for now)."
  #{"raw.githubusercontent.com"
    "gist.githubusercontent.com"})

;; ============================================================
;; Validation
;; ============================================================

(defn validate-sha
  "Check if string is a valid full 40-char hex SHA.
   Returns true for valid, false otherwise."
  [s]
  (boolean (and (string? s)
                (re-matches #"^[0-9a-fA-F]{40}$" s))))

(defn- parse-url-parts
  "Split an HTTPS URL into {:host ... :path-segments [...]}.
   Returns nil for non-HTTPS URLs."
  [url]
  (when (and (string? url) (string/starts-with? url "https://"))
    (let [without-scheme (subs url 8)
          slash-idx (.indexOf without-scheme "/")]
      (when (pos? slash-idx)
        (let [host (subs without-scheme 0 slash-idx)
              path (subs without-scheme (inc slash-idx))
              segments (string/split path #"/")]
          {:host host :path-segments segments})))))

(defn- extract-sha-from-path
  "Extract the SHA from URL path segments based on host.
   raw.githubusercontent.com: /{owner}/{repo}/{SHA}/... -> SHA at index 2
   gist.githubusercontent.com: /{owner}/{gist_id}/raw/{SHA}/... -> SHA at index 3"
  [host segments]
  (case host
    "raw.githubusercontent.com"
    (when (>= (count segments) 4)
      (nth segments 2))
    "gist.githubusercontent.com"
    (when (and (>= (count segments) 5)
               (= "raw" (nth segments 2)))
      (nth segments 3))
    nil))

;; ============================================================
;; Predicate
;; ============================================================

(defn valid-ext-dep-url?
  "Returns true if the URL is a valid external dependency URL:
   - Starts with https://
   - Host is in trusted-hosts
   - URL path contains a valid 40-char hex SHA at the expected position"
  [url]
  (boolean
   (when-let [{:keys [host path-segments]} (parse-url-parts url)]
     (and (contains? trusted-hosts host)
          (when-let [sha (extract-sha-from-path host path-segments)]
            (validate-sha sha))))))

;; ============================================================
;; URL Extraction
;; ============================================================

(defn extract-ext-dep-urls
  "Filter a vector of inject URLs, returning only valid ext-dep URLs."
  [inject-urls]
  (filterv valid-ext-dep-url? inject-urls))

;; ============================================================
;; Fetch Engine
;; ============================================================

(defn- ^:async fetch-and-cache-url!
  "Fetch a single ext dep URL and cache the result. Handles transitive deps.
   Mutates resolved, errors, and visited atoms. Returns nil."
  [url fetch-fn parse-manifest-fn now resolved errors visited]
  (when-not (contains? @visited url)
    (swap! visited conj url)
    (js-await
     (-> (fetch-fn url)
         (.then (fn [code]
                  (let [manifest (when code (parse-manifest-fn code))
                        inject-urls (if manifest
                                      (let [raw-inject (aget manifest "inject")]
                                        (if (vector? raw-inject) raw-inject []))
                                      [])
                        transitive-ext-urls (extract-ext-dep-urls inject-urls)]
                    (swap! resolved assoc url
                           {:cache/code code
                            :cache/url url
                            :cache/inject inject-urls
                            :cache/fetched-at now
                            :cache/schema-version 1})
                    transitive-ext-urls)))
         (.then (fn [transitive-urls]
                  (when (seq transitive-urls)
                    (js/Promise.resolve
                     (reduce (fn [chain t-url]
                               (.then chain
                                      (fn [_]
                                        (fetch-and-cache-url! t-url fetch-fn parse-manifest-fn
                                                              now resolved errors visited))))
                             (js/Promise.resolve nil)
                             transitive-urls)))))
         (.catch (fn [e]
                   (swap! errors conj {:error/type :ext-dep/fetch-failed
                                       :error/phase :resolve
                                       :error/dep-raw url
                                       :error/message (str "Failed to fetch " url ": " (.-message e))})))))))

(defn ^:async resolve-and-fetch!
  "Resolve and fetch external dependencies, building cache entries.
   Parameters (as a map):
   - :inject-urls     - vector of ext dep URLs to fetch
   - :ext-dep-cache   - existing cache map {url -> cache-entry}
   - :fetch-fn        - async function (url) -> code-string
   - :parse-manifest-fn - function (code) -> manifest map or nil
   - :now             - current timestamp in epoch ms

   Returns {:resolved {url -> cache-entry} :errors [error-envelopes]}

   Handles transitive ext deps found in fetched manifest :epupp/inject.
   Skips URLs already in cache. Continues on individual fetch failures.
   Detects cycles via visited set."
  [{:keys [inject-urls ext-dep-cache fetch-fn parse-manifest-fn now]}]
  (let [resolved (atom {})
        errors (atom [])
        visited (atom (set (keys (or ext-dep-cache {}))))]
    (js-await
     (reduce (fn [chain url]
               (.then chain
                      (fn [_]
                        (fetch-and-cache-url! url fetch-fn parse-manifest-fn
                                              now resolved errors visited))))
             (js/Promise.resolve nil)
             inject-urls))
    {:resolved @resolved
     :errors @errors}))
