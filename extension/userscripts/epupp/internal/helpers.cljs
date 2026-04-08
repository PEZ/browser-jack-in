{:epupp/script-name "epupp/internal/helpers.cljs"
 :epupp/description "Internal helpers for built-in Epupp scripts: bridge messaging and manifest parsing"
 :epupp/library? true}

(ns epupp.internal.helpers
  (:require [clojure.edn :as edn]
            [clojure.string :as string]))

(def valid-run-at-values
  #{"document-start" "document-end" "document-idle"})

(def default-run-at "document-idle")

(defn- next-request-id []
  (str "epupp-" (js/Date.now) "-" (rand-int 1000000000)))

(defn send-and-receive
  "Send a message to the content bridge and return a promise of the response.
   Resolves with the response message object, or nil on timeout."
  ([msg-type response-type]
   (send-and-receive msg-type response-type {}))
  ([msg-type response-type payload]
   (send-and-receive msg-type response-type payload 2000))
  ([msg-type response-type payload timeout-ms]
   (let [request-id (next-request-id)]
     (js/Promise.
      (fn [resolve _reject]
        (let [timeout-id (atom nil)
              handler (fn handler [e]
                        (when (= (.-source e) js/window)
                          (let [msg (.-data e)]
                            (when (and msg
                                       (= "epupp-bridge" (.-source msg))
                                       (= response-type (.-type msg))
                                       (= request-id (.-requestId msg)))
                              (when-let [tid @timeout-id]
                                (js/clearTimeout tid))
                              (.removeEventListener js/window "message" handler)
                              (resolve msg)))))]
          (.addEventListener js/window "message" handler)
          (reset! timeout-id
                  (js/setTimeout
                   (fn []
                     (.removeEventListener js/window "message" handler)
                     (resolve nil))
                   timeout-ms))
          (.postMessage js/window
                        (clj->js (assoc payload
                                        :source "epupp-page"
                                        :type msg-type
                                        :requestId request-id))
                        "*")))))))

(defn- get-first-form
  "Read the first form from code text. Returns map or nil."
  [code-text]
  (try
    (let [form (edn/read-string code-text)]
      (when (map? form) form))
    (catch :default e
      (js/console.error "[Epupp Internal Helpers] Parse error:" e)
      nil)))

(defn normalize-script-name
  "Normalize a script name to a consistent format.
   Dots become path separators to support Clojure namespace conventions."
  [input-name]
  (let [base-name (if (string/ends-with? input-name ".cljs")
                    (subs input-name 0 (- (count input-name) 5))
                    input-name)]
    (-> base-name
        string/lower-case
        (string/replace #"[.]+" "/")
        (string/replace #"[\s-]+" "_")
        (string/replace #"[^a-z0-9_/]" "")
        (str ".cljs"))))

(defn normalize-inject
  "Normalize :epupp/inject to vector of strings.
   Accepts nil, string, vector, or seq. Keeps all string URLs, including epupp:// entries."
  [inject-value]
  (let [to-vec (fn [value]
                 (cond
                   (nil? value) []
                   (string? value) [value]
                   (vector? value) value
                   (sequential? value) (vec value)
                   :else []))]
    (->> (to-vec inject-value)
         (filter string?)
         vec)))

(defn extract-manifest
  "Extract manifest from the first form (must be a data map)."
  [code-text]
  (when-let [m (get-first-form code-text)]
    (when-let [raw-name (get m :epupp/script-name)]
      (let [normalized-name (normalize-script-name raw-name)
            raw-run-at (get m :epupp/run-at)
            run-at (if (contains? valid-run-at-values raw-run-at)
                     raw-run-at
                     default-run-at)
            auto-run-match (get m :epupp/auto-run-match)
            raw-inject (get m :epupp/inject)
            inject (normalize-inject raw-inject)
            library? (boolean (get m :epupp/library?))]
        {:script-name normalized-name
         :raw-script-name raw-name
         :name-normalized? (not= raw-name normalized-name)
         :auto-run-match auto-run-match
         :description (get m :epupp/description)
         :inject inject
         :inject-invalid? (and (contains? m :epupp/inject)
                               (or (not (or (string? raw-inject)
                                            (vector? raw-inject)
                                            (sequential? raw-inject)))
                                   (not-every? string? (if (or (vector? raw-inject)
                                                                (sequential? raw-inject))
                                                         raw-inject
                                                         []))))
         :run-at run-at
         :raw-run-at raw-run-at
         :library? library?
         :run-at-invalid? (and raw-run-at
                               (not (contains? valid-run-at-values raw-run-at)))}))))