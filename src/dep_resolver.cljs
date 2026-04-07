(ns dep-resolver
  "Pure dependency resolver for mixed scittle:// + epupp:// + external dependency graphs.
   Receives all data as arguments - no storage reads, no side effects.
   Produces an ordered execution plan of vendor files, library scripts,
   and root scripts with deduplication and error detection."
  (:require [clojure.string :as string]
            [ext-dep :as ext-dep]
            [scittle-libs :as scittle-libs]
            [script-utils :as script-utils]))

;; ============================================================
;; URL Classification
;; ============================================================

(defn classify-inject-url
  "Classify an inject URL by protocol.
   Returns :scittle, :epupp, :ext-dep, or :unknown."
  [url]
  (cond
    (and (string? url) (string/starts-with? url "scittle://")) :scittle
    (and (string? url) (string/starts-with? url "epupp://")) :epupp
    (ext-dep/valid-ext-dep-url? url) :ext-dep
    :else :unknown))

(defn parse-epupp-url
  "Parse an epupp:// URL and normalize the script name.
   Returns normalized name string, or nil for invalid URLs."
  [url]
  (when (and (string? url) (string/starts-with? url "epupp://"))
    (let [raw-name (subs url 8)]
      (when (seq raw-name)
        (script-utils/normalize-script-name raw-name)))))

;; ============================================================
;; Script Catalog
;; ============================================================

(defn build-catalog
  "Build a script catalog keyed by normalized name from a collection of scripts.
   Each script should have :script/name (already normalized)."
  [scripts]
  (reduce (fn [catalog script]
            (if-let [script-name (:script/name script)]
              (assoc catalog script-name script)
              catalog))
          {}
          scripts))

;; ============================================================
;; Error Helpers
;; ============================================================

(defn- format-required-by
  "Format the 'required by' portion of an error message from a dep chain.
   Chain includes the missing/problematic dep as last element."
  [chain]
  (let [parents (reverse (butlast chain))]
    (when (seq parents)
      (str " (required by " (string/join ", required by " parents) ")"))))

(defn- make-error
  "Create a runtime error envelope."
  [error-type script-name dep-raw chain message]
  {:error/type error-type
   :error/phase :resolve
   :error/script-name script-name
   :error/dep-raw dep-raw
   :error/dep-chain chain
   :error/message message})

;; ============================================================
;; Graph Resolution
;; ============================================================

(defn- resolve-script-deps
  "Resolve transitive dependencies for a single script.
   Walks depth-first, collecting vendor URLs and resolved scripts in order.
   Detects missing libraries, self-references, cycles, and cache misses.
   ext-dep-cache is a map of URL->cache-entry for external dependencies (may be nil).
   Returns {:resolved [items-in-order] :errors [envelopes] :vendor-urls [strings]}"
  [root-script catalog ext-dep-cache]
  (let [errors (atom [])
        vendor-urls (atom [])
        resolved-order (atom [])
        seen (atom #{})
        error-count (fn [] (count @errors))]
    (letfn [(resolve-deps [inject-urls chain]
              (doseq [url inject-urls]
                (let [kind (classify-inject-url url)]
                  (cond
                    (= kind :scittle)
                    (swap! vendor-urls conj url)

                    (= kind :epupp)
                    (let [dep-name (parse-epupp-url url)]
                      (when dep-name
                        (let [new-chain (conj chain dep-name)]
                          (cond
                            (= dep-name (peek chain))
                            (swap! errors conj
                                   (make-error :library/self-reference
                                               (first chain) url new-chain
                                               (str "Self-reference: " dep-name " depends on itself")))

                            (some #(= % dep-name) chain)
                            (swap! errors conj
                                   (make-error :library/cycle
                                               (first chain) url new-chain
                                               (str "Dependency cycle detected: "
                                                    (string/join " -> " new-chain))))

                            (nil? (get catalog dep-name))
                            (swap! errors conj
                                   (make-error :library/not-found
                                               (first chain) url new-chain
                                               (str "Library not found: " dep-name
                                                    (format-required-by new-chain))))

                            (contains? @seen dep-name)
                            nil

                            :else
                            (walk (get catalog dep-name) new-chain)))))

                    (= kind :ext-dep)
                    (walk-ext-dep url chain)))))

            (walk-ext-dep [url chain]
              (let [new-chain (conj chain url)]
                (cond
                  (some #(= % url) chain)
                  (swap! errors conj
                         (make-error :ext-dep/cycle
                                     (first chain) url new-chain
                                     (str "Dependency cycle detected: "
                                          (string/join " -> " new-chain))))

                  (contains? @seen url)
                  nil

                  :else
                  (if-let [entry (get ext-dep-cache url)]
                    (let [errors-before (error-count)]
                      (swap! seen conj url)
                      (resolve-deps (get entry :cache/inject []) new-chain)
                      (when (= errors-before (error-count))
                        (swap! resolved-order conj
                               {:step/type :ext-dep-script
                                :step/url url
                                :step/code (:cache/code entry)
                                :step/source :ext})))
                    (swap! errors conj
                           (make-error :ext-dep/cache-miss
                                       (first chain) url new-chain
                                       (str "External dependency not in cache: " url
                                            (format-required-by new-chain))))))))

            (walk [current-script chain]
              (let [script-name (:script/name current-script)]
                (when-not (contains? @seen script-name)
                  (let [errors-before (error-count)]
                  (swap! seen conj script-name)
                  (resolve-deps (get current-script :script/inject []) chain)
                  (when (= errors-before (error-count))
                    (swap! resolved-order conj current-script))))))]
      (walk root-script [(:script/name root-script)])
      {:resolved @resolved-order
       :errors @errors
       :vendor-urls @vendor-urls})))

;; ============================================================
;; Public API
;; ============================================================

(defn resolve-execution-plan
  "Resolve a complete execution plan for a set of root scripts.

   Parameters:
   - root-scripts: collection of scripts to resolve (the 'roots')
   - all-scripts: collection of ALL available scripts (for library lookup)
   - ext-dep-cache: (optional) map of ext dep URL -> cache entry for external dependencies

   Returns:
   {:plan/steps [{:step/type :vendor-file|:library-script|:ext-dep-script|:root-script ...}]
    :plan/vendor-namespaces [string]  ; namespace names for vendor verification
    :plan/errors [error-envelopes]}"
  ([root-scripts all-scripts]
   (resolve-execution-plan root-scripts all-scripts nil))
  ([root-scripts all-scripts ext-dep-cache]
   (let [catalog (build-catalog all-scripts)
         root-ids (set (map :script/id root-scripts))
         results (mapv #(resolve-script-deps % catalog ext-dep-cache) root-scripts)
         all-errors (vec (mapcat :errors results))
         all-vendor-urls (vec (distinct (mapcat :vendor-urls results)))
         vendor-files (scittle-libs/collect-lib-files
                       [{:script/inject all-vendor-urls}])
         vendor-namespaces (scittle-libs/collect-lib-namespaces
                            [{:script/inject all-vendor-urls}])
         all-resolved (mapcat :resolved results)
         seen-ids (atom #{})
         deduped (reduce (fn [acc item]
                           (let [id (or (:script/id item) (:step/url item))]
                             (if (or (nil? id) (contains? @seen-ids id))
                               acc
                               (do (swap! seen-ids conj id)
                                   (conj acc item)))))
                         []
                         all-resolved)
         non-roots (filterv #(not (contains? root-ids (:script/id %))) deduped)
         root-scripts-ordered (filterv #(contains? root-ids (:script/id %)) deduped)
         vendor-steps (mapv (fn [file]
                              {:step/type :vendor-file
                               :step/path (str "vendor/" file)
                               :step/source :scittle})
                            vendor-files)
         non-root-steps (mapv (fn [item]
                                (if (= :ext-dep-script (:step/type item))
                                  item
                                  {:step/type :library-script
                                   :step/id (:script/id item)
                                   :step/name (:script/name item)
                                   :step/code (:script/code item)
                                   :step/source :epupp}))
                              non-roots)
         root-steps (mapv (fn [script]
                            {:step/type :root-script
                             :step/id (:script/id script)
                             :step/name (:script/name script)
                             :step/code (:script/code script)
                             :step/source :epupp})
                          root-scripts-ordered)]
     {:plan/steps (vec (concat vendor-steps non-root-steps root-steps))
      :plan/vendor-namespaces vendor-namespaces
      :plan/errors all-errors})))

(defn plan-vendor-files
  "Extract vendor file paths from plan steps."
  [plan]
  (mapv :step/path (filterv #(= :vendor-file (:step/type %)) (:plan/steps plan))))

(defn plan-script-steps
  "Extract library, ext-dep, and root script steps from plan (in order)."
  [plan]
  (filterv #(contains? #{:library-script :ext-dep-script :root-script} (:step/type %))
           (:plan/steps plan)))
