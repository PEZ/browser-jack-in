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
  "Classify an inject URL by type.
   Returns :css (any .css URL), :scittle, :epupp, :ext-dep, or :unknown."
  [url]
  (cond
    (and (string? url) (string/ends-with? url ".css")) :css
    (and (string? url) (string/starts-with? url "scittle://")) :scittle
    (and (string? url) (string/starts-with? url "epupp://")) :epupp
    (ext-dep/valid-ext-dep-url? url) :ext-dep
    :else :unknown))

(defn parse-epupp-url
  "Parse an epupp:// URL and normalize the script name.
   CSS files (.css) are returned as-is without normalization.
   Returns normalized name string, or nil for invalid URLs."
  [url]
  (when (and (string? url) (string/starts-with? url "epupp://"))
    (let [raw-name (subs url 8)]
      (when (seq raw-name)
        (if (string/ends-with? raw-name ".css")
          raw-name
          (script-utils/normalize-script-name raw-name))))))

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
  "Create a runtime error envelope from a descriptor map."
  [{:keys [error-type script-name dep-raw chain message]}]
  {:error/type error-type
   :error/phase :resolve
   :error/script-name script-name
   :error/dep-raw dep-raw
   :error/dep-chain chain
   :error/message message})

;; ============================================================
;; Graph Resolution
;; ============================================================

(defn- resolve-epupp-url
  "Handle resolution of an epupp:// dependency URL.
   ctx contains :catalog, :seen, :errors atoms.
   walk-fn is the recursive walk function from the resolution context."
  [ctx url chain walk-fn]
  (let [dep-name (parse-epupp-url url)]
    (when dep-name
      (let [new-chain (conj chain dep-name)]
        (cond
          (= dep-name (peek chain))
          (swap! (:errors ctx) conj
                 (make-error {:error-type :library/self-reference
                              :script-name (first chain) :dep-raw url :chain new-chain
                              :message (str "Self-reference: " dep-name " depends on itself")}))

          (some #(= % dep-name) chain)
          (swap! (:errors ctx) conj
                 (make-error {:error-type :library/cycle
                              :script-name (first chain) :dep-raw url :chain new-chain
                              :message (str "Dependency cycle detected: "
                                            (string/join " -> " new-chain))}))

          (nil? (get (:catalog ctx) dep-name))
          (swap! (:errors ctx) conj
                 (make-error {:error-type :library/not-found
                              :script-name (first chain) :dep-raw url :chain new-chain
                              :message (str "Library not found: " dep-name
                                            (format-required-by new-chain))}))

          (contains? @(:seen ctx) dep-name)
          nil

          :else
          (walk-fn (get (:catalog ctx) dep-name) new-chain))))))

(defn- resolve-ext-dep-url
  "Handle resolution of an external dependency URL.
   ctx contains :ext-dep-cache, :seen, :errors, :resolved-order atoms.
   resolve-deps-fn is the recursive resolve function from the resolution context."
  [ctx url chain resolve-deps-fn]
  (let [new-chain (conj chain url)]
    (cond
      (some #(= % url) chain)
      (swap! (:errors ctx) conj
             (make-error {:error-type :ext-dep/cycle
                          :script-name (first chain) :dep-raw url :chain new-chain
                          :message (str "Dependency cycle detected: "
                                        (string/join " -> " new-chain))}))

      (contains? @(:seen ctx) url)
      nil

      :else
      (if-let [entry (get (:ext-dep-cache ctx) url)]
        (let [errors-before (count @(:errors ctx))]
          (swap! (:seen ctx) conj url)
          (resolve-deps-fn (get entry :cache/inject []) new-chain)
          (when (= errors-before (count @(:errors ctx)))
            (swap! (:resolved-order ctx) conj
                   {:step/type :ext-dep-script
                    :step/url url
                    :step/code (:cache/code entry)
                    :step/source :ext})))
        (swap! (:errors ctx) conj
               (make-error {:error-type :ext-dep/cache-miss
                            :script-name (first chain) :dep-raw url :chain new-chain
                            :message (str "External dependency not in cache: " url
                                          (format-required-by new-chain))}))))))

(defn- walk-and-collect
  "Walk a script node: mark as seen, resolve its deps, collect if no errors added."
  [ctx current-script chain resolve-deps-fn]
  (let [errors-before (count @(:errors ctx))]
    (swap! (:seen ctx) conj (:script/name current-script))
    (resolve-deps-fn (get current-script :script/inject []) chain)
    (when (= errors-before (count @(:errors ctx)))
      (swap! (:resolved-order ctx) conj current-script))))

(defn- resolve-script-deps
  "Resolve transitive dependencies for a single script.
   Walks depth-first, collecting vendor URLs, CSS URLs, and resolved scripts in order.
   Detects missing libraries, self-references, cycles, and cache misses.
   ext-dep-cache is a map of URL->cache-entry for external dependencies (may be nil).
   Returns {:resolved [items-in-order] :errors [envelopes] :vendor-urls [strings] :css-urls [strings]}"
  [root-script catalog ext-dep-cache]
  (let [ctx {:catalog catalog
             :ext-dep-cache ext-dep-cache
             :errors (atom [])
             :vendor-urls (atom [])
             :css-urls (atom [])
             :resolved-order (atom [])
             :seen (atom #{})}]
    (letfn [(resolve-deps [inject-urls chain]
              (doseq [url inject-urls]
                (let [kind (classify-inject-url url)]
                  (cond
                    (= kind :css) (swap! (:css-urls ctx) conj url)
                    (= kind :scittle) (swap! (:vendor-urls ctx) conj url)
                    (= kind :epupp) (resolve-epupp-url ctx url chain walk)
                    (= kind :ext-dep) (resolve-ext-dep-url ctx url chain resolve-deps)))))
            (walk [current-script chain]
              (when-not (contains? @(:seen ctx) (:script/name current-script))
                (walk-and-collect ctx current-script chain resolve-deps)))]
      (walk root-script [(:script/name root-script)])
      {:resolved @(:resolved-order ctx)
       :errors @(:errors ctx)
       :vendor-urls @(:vendor-urls ctx)
       :css-urls @(:css-urls ctx)})))

;; ============================================================
;; Public API
;; ============================================================

(defn- dedup-resolved-items
  "Deduplicate resolved items by script ID or ext-dep URL, preserving order."
  [items]
  (let [seen-ids (atom #{})]
    (reduce (fn [acc item]
              (let [id (or (:script/id item) (:step/url item))]
                (if (or (nil? id) (contains? @seen-ids id))
                  acc
                  (do (swap! seen-ids conj id)
                      (conj acc item)))))
            []
            items)))

(defn- item-to-step
  "Convert a resolved item to an execution step with the appropriate type."
  [root-ids item]
  (if (= :ext-dep-script (:step/type item))
    item
    (let [is-root? (contains? root-ids (:script/id item))]
      {:step/type (if is-root? :root-script :library-script)
       :step/id (:script/id item)
       :step/name (:script/name item)
       :step/code (:script/code item)
       :step/source :epupp})))

(defn- css-url-to-step
  "Convert a CSS URL to a :css-file execution step."
  [url]
  (if (string/starts-with? url "epupp://")
    (let [raw-name (parse-epupp-url url)]
      {:step/type :css-file
       :step/source :epupp
       :step/path (str "userscripts/" raw-name)})
    {:step/type :css-file
     :step/source :external
     :step/url url}))

(defn resolve-execution-plan
  "Resolve a complete execution plan for a set of root scripts.

   Parameters:
   - root-scripts: collection of scripts to resolve (the 'roots')
   - all-scripts: collection of ALL available scripts (for library lookup)
   - ext-dep-cache: (optional) map of ext dep URL -> cache entry for external dependencies

   Returns:
   {:plan/steps [{:step/type :css-file|:vendor-file|:library-script|:ext-dep-script|:root-script ...}]
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
         all-css-urls (vec (distinct (mapcat :css-urls results)))
         vendor-files (scittle-libs/collect-lib-files
                       [{:script/inject all-vendor-urls}])
         vendor-namespaces (scittle-libs/collect-lib-namespaces
                            [{:script/inject all-vendor-urls}])
         deduped (dedup-resolved-items (mapcat :resolved results))
         css-steps (mapv css-url-to-step all-css-urls)
         vendor-steps (mapv (fn [file]
                              {:step/type :vendor-file
                               :step/path (str "vendor/" file)
                               :step/source :scittle})
                            vendor-files)
         script-steps (mapv #(item-to-step root-ids %) deduped)
         non-root-steps (filterv #(not= :root-script (:step/type %)) script-steps)
         root-steps (filterv #(= :root-script (:step/type %)) script-steps)]
     {:plan/steps (vec (concat css-steps vendor-steps non-root-steps root-steps))
      :plan/vendor-namespaces vendor-namespaces
      :plan/errors all-errors})))