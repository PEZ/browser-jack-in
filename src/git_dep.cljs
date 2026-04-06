(ns git-dep
  "Pure parser and classifier for git:// and gist:// dependency URLs.
   All functions are pure - no side effects, no chrome APIs."
  (:require [clojure.string :as string]))

;; ============================================================
;; Constants
;; ============================================================

(def allowed-hosts
  "Set of hostnames allowed in git:// and gist:// URLs."
  #{"github.com" "gist.github.com" "gitlab.com" "codeberg.org" "localhost" "127.0.0.1"})

(def ^:private allowed-gist-hosts
  "Hostnames that support the gist:// scheme."
  #{"gist.github.com"})

;; ============================================================
;; Validation
;; ============================================================

(defn validate-sha
  "Check if string is a valid full 40-char hex SHA.
   Returns true for valid, false otherwise."
  [s]
  (boolean (and (string? s)
                (re-matches #"^[0-9a-fA-F]{40}$" s))))

;; ============================================================
;; Host Classification
;; ============================================================

(defn- extract-hostname
  "Extract hostname from a host string that may include a port.
   'localhost:8080' -> 'localhost'"
  [host]
  (let [colon-idx (.indexOf host ":")]
    (if (>= colon-idx 0)
      (subs host 0 colon-idx)
      host)))

(defn forge-for-host
  "Map hostname to forge keyword.
   Returns :github, :gitlab, :codeberg, :gist-github, :localhost, or nil."
  [hostname]
  (case hostname
    "github.com" :github
    "gist.github.com" :gist-github
    "gitlab.com" :gitlab
    "codeberg.org" :codeberg
    "localhost" :localhost
    "127.0.0.1" :localhost
    nil))

;; ============================================================
;; Raw URL Construction
;; ============================================================

(defn- github-raw-url
  [{:git/keys [owner repo sha path]}]
  (str "https://raw.githubusercontent.com/" owner "/" repo "/" sha "/" path))

(defn- gist-github-raw-url
  [{:git/keys [owner repo sha path]}]
  (str "https://gist.githubusercontent.com/" owner "/" repo "/raw/" sha "/" path))

(defn- gitlab-raw-url
  [{:git/keys [owner repo sha path]}]
  (str "https://gitlab.com/" owner "/" repo "/-/raw/" sha "/" path))

(defn- codeberg-raw-url
  [{:git/keys [owner repo sha path]}]
  (str "https://codeberg.org/" owner "/" repo "/raw/commit/" sha "/" path))

(defn- localhost-raw-url
  [{:git/keys [host owner repo sha path]}]
  (str "http://" host "/" owner "/" repo "/raw/commit/" sha "/" path))

(defn to-raw-url
  "Convert parsed git dep data to a forge-specific raw content URL.
   Returns nil if forge is unsupported."
  [parsed]
  (when parsed
    (case (:git/forge parsed)
      :github (github-raw-url parsed)
      :gist-github (gist-github-raw-url parsed)
      :gitlab (gitlab-raw-url parsed)
      :codeberg (codeberg-raw-url parsed)
      :localhost (localhost-raw-url parsed)
      nil)))

;; ============================================================
;; URL Parsing
;; ============================================================

(defn parse-git-dep-url
  "Parse a git:// or gist:// dependency URL into a data map.

   URL formats:
     git://host/owner/repo@SHA/path/to/file.cljs
     gist://gist.github.com/user/GIST_ID@SHA/filename.cljs

   Returns map with :git/* keys, or nil for invalid URLs."
  [url]
  (when (string? url)
    (let [scheme (cond
                   (string/starts-with? url "git://") :git
                   (string/starts-with? url "gist://") :gist
                   :else nil)]
      (when scheme
        (let [prefix-len (if (= scheme :git) 6 7)
              rest-str (subs url prefix-len)
              segments (string/split rest-str #"/")]
          (when (>= (count segments) 3)
            (let [host (first segments)
                  hostname (extract-hostname host)
                  forge (forge-for-host hostname)]
              (when (and (contains? allowed-hosts hostname)
                         forge
                         (or (= scheme :git)
                             (contains? allowed-gist-hosts hostname)))
                (let [owner (nth segments 1)
                      repo-sha-str (nth segments 2)
                      at-idx (.indexOf repo-sha-str "@")]
                  (when (pos? at-idx)
                    (let [repo (subs repo-sha-str 0 at-idx)
                          sha (subs repo-sha-str (inc at-idx))
                          path-segments (drop 3 segments)
                          path (when (seq path-segments)
                                 (string/join "/" path-segments))]
                      (when (and (seq repo)
                                 (validate-sha sha)
                                 (seq path))
                        (let [parsed {:git/scheme scheme
                                      :git/host host
                                      :git/owner owner
                                      :git/repo repo
                                      :git/sha sha
                                      :git/path path
                                      :git/forge forge
                                      :git/inject-url url}]
                          (assoc parsed :git/raw-url (to-raw-url parsed)))))))))))))))

;; ============================================================
;; Predicate
;; ============================================================

(defn git-dep-url?
  "Returns true if the string is a valid git:// or gist:// dependency URL
   on an allowed host."
  [url]
  (boolean (parse-git-dep-url url)))

;; ============================================================
;; URL Extraction
;; ============================================================

(defn extract-git-dep-urls
  "Filter a vector of inject URLs, returning only valid git:// or gist:// URLs."
  [inject-urls]
  (filterv git-dep-url? inject-urls))

;; ============================================================
;; Fetch Engine
;; ============================================================

(defn- ^:async fetch-and-cache-url!
  "Fetch a single git dep URL and cache the result. Handles transitive deps.
   Mutates resolved, errors, and visited atoms. Returns nil."
  [url fetch-fn parse-manifest-fn now resolved errors visited]
  (when-not (contains? @visited url)
    (swap! visited conj url)
    (let [parsed (parse-git-dep-url url)]
      (if-not parsed
        (swap! errors conj {:error/type :git-dep/parse-failed
                            :error/phase :resolve
                            :error/dep-raw url
                            :error/message (str "Failed to parse git dep URL: " url)})
        (let [raw-url (:git/raw-url parsed)]
          (js-await
           (-> (fetch-fn raw-url)
               (.then (fn [code]
                        (let [manifest (when code (parse-manifest-fn code))
                              inject-urls (if manifest
                                            (let [raw-inject (aget manifest "inject")]
                                              (if (vector? raw-inject) raw-inject []))
                                            [])
                              transitive-git-urls (extract-git-dep-urls inject-urls)]
                          ;; Build cache entry immediately
                          (swap! resolved assoc url
                                 {:cache/code code
                                  :cache/sha (:git/sha parsed)
                                  :cache/raw-url raw-url
                                  :cache/inject inject-urls
                                  :cache/fetched-at now
                                  :cache/schema-version 1})
                          ;; Return transitive URLs for sequential resolution
                          transitive-git-urls)))
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
                         (swap! errors conj {:error/type :git-dep/fetch-failed
                                             :error/phase :resolve
                                             :error/dep-raw url
                                             :error/message (str "Failed to fetch " url ": " (.-message e))}))))))))))

(defn ^:async resolve-and-fetch!
  "Resolve and fetch git dependencies, building cache entries.
   Parameters (as a map):
   - :inject-urls   - vector of inject URLs (pre-filtered to git dep URLs)
   - :git-dep-cache - existing cache map {url -> cache-entry}
   - :fetch-fn      - async function (raw-url) -> code-string (injected for testability)
   - :parse-manifest-fn - function (code) -> manifest map or nil
   - :now           - current timestamp in epoch ms

   Returns {:resolved {url -> cache-entry} :errors [error-envelopes]}

   Handles transitive git deps found in fetched manifest :epupp/inject.
   Skips URLs already in cache. Continues on individual fetch failures.
   Detects cycles via visited set."
  [{:keys [inject-urls git-dep-cache fetch-fn parse-manifest-fn now]}]
  (let [resolved (atom {})
        errors (atom [])
        visited (atom (set (keys (or git-dep-cache {}))))]
    ;; Process top-level URLs sequentially
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
