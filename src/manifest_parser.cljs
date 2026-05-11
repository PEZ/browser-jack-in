(ns manifest-parser
  "Parses Epupp manifest metadata from script code.
   Returns rich structure with raw values, coerced values, and warnings.

   SYNC WARNING: A parallel parser exists in extension/userscripts/epupp/gist_installer.cljs
   for the SCI/Scittle environment. When modifying manifest parsing behavior, ensure both
   parsers stay in sync. Key behaviors:
   - auto-run-match is OPTIONAL (nil means manual-only script)
   - auto-run-match preserves vector/string as-is"
  (:require ["edn-data" :as edn-data]
            [clojure.string :as string]
            [script-utils :as script-utils]))

(def valid-run-at-values
  #{"document-start" "document-end" "document-idle"})

(def default-run-at "document-idle")

(def known-epupp-keys
  "Set of known epupp manifest keys."
  #{"epupp/script-name" "epupp/auto-run-match" "epupp/description" "epupp/run-at" "epupp/inject" "epupp/library?"})

(defn- get-epupp-keys
  "Returns vector of all epupp/ prefixed keys found in parsed object."
  [parsed]
  (when parsed
    (->> (js/Object.keys parsed)
         (filter #(string/starts-with? % "epupp/"))
         vec)))

(defn normalize-inject
  "Normalize :epupp/inject to vector of strings.
   Accepts nil, string, or vector/array."
  [require-value]
  (cond
    (nil? require-value) []
    (string? require-value) [require-value]
    (vector? require-value) (vec require-value)
    (array? require-value) (vec require-value)
    :else []))



(defn- coerce-manifest-values
  "Coerce raw manifest values into their derived/validated forms."
  [raw-script-name raw-run-at raw-inject]
  (let [script-name (when raw-script-name
                      (script-utils/normalize-script-name raw-script-name))
        name-normalized? (and (some? raw-script-name)
                              (not= raw-script-name script-name))
        run-at-invalid? (and (some? raw-run-at)
                             (not (contains? valid-run-at-values raw-run-at)))
        run-at (if (or (nil? raw-run-at) run-at-invalid?)
                 default-run-at
                 raw-run-at)]
    {:script-name script-name
     :name-normalized? name-normalized?
     :run-at run-at
     :run-at-invalid? run-at-invalid?
     :inject (normalize-inject raw-inject)}))

(defn extract-manifest
  "Extracts Epupp manifest data from code string containing ^{:epupp/...} metadata.
   Returns a rich map with:
   - :script-name - normalized name (or nil if missing)
   - :raw-script-name - original name before normalization
   - :name-normalized? - true if normalization changed the name
   - :auto-run-match - URL pattern(s), preserved as string or vector
   - :description - description text (validated as string)
   - :run-at - timing value (defaults to document-idle)
   - :raw-run-at - original run-at value
   - :run-at-invalid? - true if run-at was invalid and defaulted
   - :require - vector of require URLs (normalized from string/vector/nil)
   - :found-keys - vector of all epupp/* keys found
   - :unknown-keys - vector of unrecognized epupp/* keys
   Returns nil if no valid manifest found."
  [code]
  (let [parsed (edn-data/parseEDNString code #js {:mapAs "object" :keywordAs "string"})
        found-keys (get-epupp-keys parsed)]
    (when (seq found-keys)
      (let [raw-script-name (aget parsed "epupp/script-name")
            raw-run-at (aget parsed "epupp/run-at")
            coerced (coerce-manifest-values raw-script-name
                                            raw-run-at
                                            (aget parsed "epupp/inject"))
            description (let [d (aget parsed "epupp/description")]
                          (when (string? d) d))
            unknown-keys (->> found-keys
                              (remove #(contains? known-epupp-keys %))
                              vec)]
        (merge coerced
               {:raw-script-name raw-script-name
                :auto-run-match (aget parsed "epupp/auto-run-match")
                :description description
                :raw-run-at raw-run-at
                :library? (boolean (aget parsed "epupp/library?"))
                :found-keys found-keys
                :unknown-keys unknown-keys})))))

(defn update-manifest-script-name
  "Update :epupp/script-name in the manifest section of code, if present.
   Returns original code when no manifest or script-name key exists."
  [code new-name]
  (if (and (string? code) (string? new-name))
    (let [manifest (try (extract-manifest code) (catch :default _ nil))
          found-keys (get manifest "found-keys")
          has-name-key? (some #(= % "epupp/script-name") found-keys)]
      (if has-name-key?
        (string/replace code
                        (js/RegExp. ":epupp/script-name\\s+\"[^\"]*\"" "g")
                        (str ":epupp/script-name \"" new-name "\""))
        code))
    code))
