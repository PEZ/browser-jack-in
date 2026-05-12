(ns background-actions.inject-actions-test
  "Tests for load-manifest, inject-libs, and resolver integration action handlers"
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

(def initial-state
  {:storage/scripts [base-script]
   :storage/granted-origins []
   :storage/ext-dep-cache {}})

(def uf-data {:system/now 1737100000000})

;; ============================================================
;; Plan helpers
;; ============================================================

(defn- execute-plan-effect
  [fxs]
  (some #(when (= :msg/fx.execute-plan (second %)) %) fxs))

(defn- effect-plan
  [fxs]
  (nth (execute-plan-effect fxs) 3))

(defn- plan-step-count
  [plan step-type]
  (count (filter #(= step-type (:step/type %)) (:plan/steps plan))))

(defn- vendor-step-paths
  [plan]
  (mapv :step/path
        (filter #(= :vendor-file (:step/type %)) (:plan/steps plan))))

(defn- response-effect
  [fxs]
  (some #(when (= :msg/fx.send-response (first %)) %) fxs))

(defn- response-payload
  [fxs]
  (nth (response-effect fxs) 2))

(defn- await-effect
  [fxs effect-name]
  (some #(when (and (= :uf/await (first %))
                    (= effect-name (second %)))
           %)
        fxs))

(def ^:private ext-dep-sha "abcdef0123456789abcdef0123456789abcdef01")

(defn- sample-ext-cache-entry
  [url fetched-at]
  {:cache/code (str ";; cached " url)
   :cache/url url
   :cache/inject []
   :cache/fetched-at fetched-at
   :cache/schema-version 1})

;; ============================================================
;; load-manifest and inject-libs baseline tests
;; ============================================================

(defn- test-load-manifest-with-scittle-libs-produces-inject-effects []
  (let [manifest #js {"inject" #js ["scittle://reagent.js"]}
        send-response :mock-send-response
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.load-manifest send-response 42 manifest []])
        fxs (:uf/fxs result)
        plan (effect-plan fxs)
        response-effect (last fxs)]
    (-> (expect (count fxs)) (.toBe 3))
    (-> (expect (second (first fxs))) (.toBe :msg/fx.ensure-scittle-tab))
    (-> (expect (second (second fxs))) (.toBe :msg/fx.execute-plan))
    (-> (expect (first response-effect)) (.toBe :msg/fx.send-response))
    (-> (expect (vendor-step-paths plan))
        (.toEqual ["vendor/react.production.min.js"
                   "vendor/react-dom.production.min.js"
                   "vendor/scittle.reagent.js"]))
    (-> (expect (plan-step-count plan :root-script)) (.toBe 0))))

(defn- test-load-manifest-without-libs-sends-success-immediately []
  (let [manifest #js {"inject" #js []}
        send-response :mock-send-response
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.load-manifest send-response 42 manifest []])
        fxs (:uf/fxs result)]
    (-> (expect (count fxs)) (.toBe 1))
    (-> (expect (first (first fxs))) (.toBe :msg/fx.send-response))))

(defn- test-load-manifest-nil-manifest-sends-success []
  (let [send-response :mock-send-response
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.load-manifest send-response 42 nil []])
        fxs (:uf/fxs result)]
    (-> (expect (count fxs)) (.toBe 1))
    (-> (expect (first (first fxs))) (.toBe :msg/fx.send-response))))

(defn- test-load-manifest-unknown-urls-send-success-immediately []
  (let [manifest #js {"inject" #js ["https://cdn.example.com/lib.js"]}
        send-response :mock-send-response
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.load-manifest send-response 42 manifest []])
        fxs (:uf/fxs result)]
    (-> (expect (count fxs)) (.toBe 1))
    (-> (expect (first (first fxs))) (.toBe :msg/fx.send-response))))

(defn- test-inject-libs-produces-bridge-and-inject-effects []
  (let [send-response :mock-send-response
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.inject-libs send-response 42 ["scittle://pprint.js"] []])
        fxs (:uf/fxs result)
        plan (effect-plan fxs)]
    (-> (expect (second (first fxs))) (.toBe :msg/fx.ensure-scittle-tab))
    (-> (expect (second (second fxs))) (.toBe :msg/fx.execute-plan))
    (-> (expect (first (last fxs))) (.toBe :msg/fx.send-response))
    (-> (expect (vendor-step-paths plan))
        (.toContain "vendor/scittle.pprint.js"))
    (-> (expect (plan-step-count plan :root-script)) (.toBe 0))))

(defn- test-inject-libs-empty-libs-sends-success-only []
  (let [send-response :mock-send-response
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.inject-libs send-response 42 [] []])
        fxs (:uf/fxs result)]
    (-> (expect (count fxs)) (.toBe 1))
    (-> (expect (first (first fxs))) (.toBe :msg/fx.send-response))))

(defn- test-inject-libs-unknown-urls-produce-no-steps []
  (let [send-response :mock-send-response
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.inject-libs send-response 42 ["https://cdn.example.com/lib.js"] []])
        fxs (:uf/fxs result)]
    (-> (expect (count fxs)) (.toBe 1))
    (-> (expect (first (first fxs))) (.toBe :msg/fx.send-response))))

(defn- test-inject-libs-with-uncached-ext-dep-defers-ready-action []
  (let [ext-url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        send-response :mock-send-response
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.inject-libs send-response 42 [ext-url] []])
        fetch-fx (await-effect (:uf/fxs result) :ext-dep/fx.fetch-deps)
        ready-dx (first (:uf/dxs result))]
    (-> (expect fetch-fx) (.toBeTruthy))
    (-> (expect (nth fetch-fx 2)) (.toEqual [ext-url]))
    (-> (expect (nth fetch-fx 3)) (.toEqual {}))
    (-> (expect (first ready-dx)) (.toBe :msg/ax.inject-libs-ready))
    (-> (expect (nth ready-dx 1)) (.toBe send-response))
    (-> (expect (nth ready-dx 2)) (.toBe 42))
    (-> (expect (nth ready-dx 3)) (.toEqual [ext-url]))
    (-> (expect (nth ready-dx 4)) (.toEqual []))
    (-> (expect (nth ready-dx 5)) (.toBe :uf/prev-result))))

(defn- test-storage-set-ext-dep-cache-hydrates-background-state []
  (let [ext-url "https://example.com/lib.cljs"
        cache {ext-url (sample-ext-cache-entry ext-url 1700000000000)}
        result (bg-actions/handle-action initial-state uf-data
                 [:storage/ax.set-ext-dep-cache cache])]
    (-> (expect (:uf/db result)) (.toBeTruthy))
    (-> (expect (get-in result [:uf/db :storage/ext-dep-cache])) (.toEqual cache))
    (-> (expect (:uf/fxs result)) (.toBeFalsy))))

(defn- test-evaluate-script-passes-state-cache-into-effect []
  (let [ext-url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        cache-entry (sample-ext-cache-entry ext-url 1700000000000)
        state (assoc initial-state :storage/ext-dep-cache {ext-url cache-entry})
        result (bg-actions/handle-action state uf-data
                 [:msg/ax.evaluate-script :mock-send 42 "(println \"hi\")" [ext-url] "eval-script"])
        evaluate-fx (await-effect (:uf/fxs result) :script/fx.evaluate)]
    (-> (expect evaluate-fx) (.toBeTruthy))
    (-> (expect (nth evaluate-fx 2)) (.toBe 42))
    (-> (expect (nth evaluate-fx 4)) (.toBe :disconnected))
    (-> (expect (nth evaluate-fx 5)) (.toEqual {ext-url cache-entry}))
    (-> (expect (:script/inject (nth evaluate-fx 3))) (.toEqual [ext-url]))))

(describe "load-manifest message action"
  (fn []
    (test "with scittle libs produces ensure + execute-plan effects" test-load-manifest-with-scittle-libs-produces-inject-effects)
    (test "without libs sends success immediately" test-load-manifest-without-libs-sends-success-immediately)
    (test "nil manifest sends success" test-load-manifest-nil-manifest-sends-success)
    (test "unknown URLs produce no executable steps" test-load-manifest-unknown-urls-send-success-immediately)))

(describe "inject-libs message action"
  (fn []
    (test "produces ensure + execute-plan effects for scittle libs" test-inject-libs-produces-bridge-and-inject-effects)
    (test "empty libs sends success only" test-inject-libs-empty-libs-sends-success-only)
    (test "with uncached ext deps fetches first and defers ready action" test-inject-libs-with-uncached-ext-dep-defers-ready-action)
    (test "unknown URLs produce no executable steps" test-inject-libs-unknown-urls-produce-no-steps)
    (test "storage cache hydrate action sets ext-dep cache in state" test-storage-set-ext-dep-cache-hydrates-background-state)
    (test "evaluate-script passes the state cache into the effect" test-evaluate-script-passes-state-cache-into-effect)))

;; ============================================================
;; Resolver integration fixtures
;; ============================================================

(def library-script-with-vendor
  {:script/id "lib-utils"
   :script/name "utils.cljs"
   :script/code "(ns utils)\n(def hello 42)"
   :script/match []
   :script/enabled true
   :script/inject ["scittle://pprint.js"]})

(def library-script-plain
  {:script/id "lib-helpers"
   :script/name "helpers.cljs"
   :script/code "(ns helpers)\n(def help true)"
   :script/match []
   :script/enabled true
   :script/inject []})

(def library-script-transitive
  {:script/id "lib-transitive"
   :script/name "advanced.cljs"
   :script/code "(ns advanced (:require [helpers]))\n(def adv true)"
   :script/match []
   :script/enabled true
   :script/inject ["epupp://helpers.cljs"]})

;; ============================================================
;; inject-libs resolver integration tests
;; ============================================================

(defn- test-inject-libs-resolves-epupp-library-script []
  (let [all-scripts [library-script-plain]
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.inject-libs :mock-send 42 ["epupp://helpers.cljs"] all-scripts])
        fxs (:uf/fxs result)
        plan (effect-plan fxs)]
    (-> (expect (count (filter #(= :msg/fx.execute-plan (second %)) fxs))) (.toBe 1))
    (-> (expect (plan-step-count plan :library-script)) (.toBe 1))
    (-> (expect (plan-step-count plan :vendor-file)) (.toBe 0))
    (-> (expect (plan-step-count plan :root-script)) (.toBe 0))
    (-> (expect (:uf/dxs result)) (.toBeFalsy))))

(defn- test-inject-libs-resolves-epupp-with-vendor-deps []
  (let [all-scripts [library-script-with-vendor]
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.inject-libs :mock-send 42 ["epupp://utils.cljs"] all-scripts])
        fxs (:uf/fxs result)
        plan (effect-plan fxs)]
    (-> (expect (count (filter #(= :msg/fx.execute-plan (second %)) fxs))) (.toBe 1))
    (-> (expect (plan-step-count plan :vendor-file)) (.toBeGreaterThanOrEqual 1))
    (-> (expect (plan-step-count plan :library-script)) (.toBe 1))
    (-> (expect (plan-step-count plan :root-script)) (.toBe 0))
    (-> (expect (:uf/dxs result)) (.toBeFalsy))))

(defn- test-inject-libs-epupp-missing-library-produces-errors []
  (let [result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.inject-libs :mock-send 42 ["epupp://nonexistent.cljs"] []])
        dxs (:uf/dxs result)
        fxs (:uf/fxs result)
        response (response-payload fxs)]
    (-> (expect (seq dxs)) (.toBeTruthy))
    (-> (expect (first (first dxs))) (.toBe :banner/ax.broadcast-resolution-errors))
    (-> (expect (execute-plan-effect fxs)) (.toBeFalsy))
    (-> (expect (:success response)) (.toBe false))))

(defn- test-inject-libs-mixed-scittle-and-epupp []
  (let [all-scripts [library-script-plain]
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.inject-libs :mock-send 42
                  ["scittle://pprint.js" "epupp://helpers.cljs"] all-scripts])
        fxs (:uf/fxs result)
        plan (effect-plan fxs)]
    (-> (expect (count (filter #(= :msg/fx.execute-plan (second %)) fxs))) (.toBe 1))
    (-> (expect (plan-step-count plan :vendor-file)) (.toBeGreaterThanOrEqual 1))
    (-> (expect (plan-step-count plan :library-script)) (.toBe 1))
    (-> (expect (plan-step-count plan :root-script)) (.toBe 0))
    (-> (expect (:uf/dxs result)) (.toBeFalsy))))

(describe "inject-libs resolver integration"
          (fn []
            (test "resolves epupp:// library script"
                  test-inject-libs-resolves-epupp-library-script)
            (test "resolves epupp:// library with vendor deps"
                  test-inject-libs-resolves-epupp-with-vendor-deps)
            (test "missing epupp:// library produces error dispatches"
                  test-inject-libs-epupp-missing-library-produces-errors)
            (test "mixed scittle:// and epupp:// produces both effects"
                  test-inject-libs-mixed-scittle-and-epupp)))

;; ============================================================
;; load-manifest resolver integration tests
;; ============================================================

(defn- test-load-manifest-resolves-epupp-library []
  (let [manifest #js {"inject" #js ["epupp://helpers.cljs"]}
        all-scripts [library-script-plain]
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.load-manifest :mock-send 42 manifest all-scripts])
        fxs (:uf/fxs result)
        plan (effect-plan fxs)]
    (-> (expect (count (filter #(= :msg/fx.execute-plan (second %)) fxs))) (.toBe 1))
    (-> (expect (plan-step-count plan :library-script)) (.toBe 1))
    (-> (expect (plan-step-count plan :vendor-file)) (.toBe 0))
    (-> (expect (plan-step-count plan :root-script)) (.toBe 0))
    (-> (expect (first (last fxs))) (.toBe :msg/fx.send-response))
    (-> (expect (:uf/dxs result)) (.toBeFalsy))))

(defn- test-load-manifest-epupp-missing-library-produces-errors []
  (let [manifest #js {"inject" #js ["epupp://missing.cljs"]}
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.load-manifest :mock-send 42 manifest []])
        dxs (:uf/dxs result)
        fxs (:uf/fxs result)
        response (response-payload fxs)]
    (-> (expect (seq dxs)) (.toBeTruthy))
    (-> (expect (first (first dxs))) (.toBe :banner/ax.broadcast-resolution-errors))
    (-> (expect (execute-plan-effect fxs)) (.toBeFalsy))
    (-> (expect (:success response)) (.toBe false))))

(defn- test-load-manifest-transitive-epupp-deps []
  (let [manifest #js {"inject" #js ["epupp://advanced.cljs"]}
        all-scripts [library-script-plain library-script-transitive]
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.load-manifest :mock-send 42 manifest all-scripts])
        fxs (:uf/fxs result)
        plan (effect-plan fxs)]
    (-> (expect (count (filter #(= :msg/fx.execute-plan (second %)) fxs))) (.toBe 1))
    (-> (expect (plan-step-count plan :library-script)) (.toBe 2))
    (-> (expect (plan-step-count plan :vendor-file)) (.toBe 0))
    (-> (expect (plan-step-count plan :root-script)) (.toBe 0))
    (-> (expect (:uf/dxs result)) (.toBeFalsy))))

(describe "load-manifest resolver integration"
          (fn []
            (test "resolves epupp:// library from all-scripts"
                  test-load-manifest-resolves-epupp-library)
            (test "missing epupp:// library produces error dispatches"
                  test-load-manifest-epupp-missing-library-produces-errors)
            (test "resolves transitive epupp:// dependencies"
                  test-load-manifest-transitive-epupp-deps)))

;; ============================================================
;; Manual ext-dep ready action tests
;; ============================================================

(defn- test-inject-libs-ready-success-persists-cache-executes-plan-and-sends-success []
  (let [ext-url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        fetched-entry (sample-ext-cache-entry ext-url 1700000000000)
        fetch-result {:resolved {ext-url fetched-entry}
                      :errors []}
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.inject-libs-ready :mock-send 42 [ext-url] [] fetch-result])
        fxs (:uf/fxs result)
        plan (effect-plan fxs)
        response (response-payload fxs)]
    (-> (expect (get-in result [:uf/db :storage/ext-dep-cache ext-url]))
        (.toEqual fetched-entry))
    (-> (expect (some #(and (= :storage/fx.persist-ext-dep-cache! (first %))
                            (= (second %) {ext-url fetched-entry}))
                      fxs))
        (.toBeTruthy))
    (-> (expect (await-effect fxs :msg/fx.ensure-scittle-tab))
        (.toBeTruthy))
    (-> (expect (execute-plan-effect fxs))
        (.toBeTruthy))
    (-> (expect (plan-step-count plan :ext-dep-script))
        (.toBe 1))
    (-> (expect (plan-step-count plan :root-script))
        (.toBe 0))
    (-> (expect response)
        (.toEqual {:success true}))))

(defn- test-inject-libs-ready-merges-fetched-cache-with-existing-state-cache []
  (let [existing-url "https://example.com/existing.cljs"
        fetched-url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        existing-entry (sample-ext-cache-entry existing-url 1699999999000)
        fetched-entry (sample-ext-cache-entry fetched-url 1700000000000)
        state (assoc initial-state :storage/ext-dep-cache {existing-url existing-entry})
        fetch-result {:resolved {fetched-url fetched-entry}
                      :errors []}
        result (bg-actions/handle-action state uf-data
                 [:msg/ax.inject-libs-ready :mock-send 42 [fetched-url] [] fetch-result])
        merged-cache {existing-url existing-entry
                      fetched-url fetched-entry}
        fxs (:uf/fxs result)]
    (-> (expect (get-in result [:uf/db :storage/ext-dep-cache])) (.toEqual merged-cache))
    (-> (expect (some #(and (= :storage/fx.persist-ext-dep-cache! (first %))
                            (= (second %) merged-cache))
                      fxs))
        (.toBeTruthy))))

(defn- test-load-manifest-ready-fetch-failure-sends-failure-and-broadcasts-errors []
  (let [ext-url (str "https://raw.githubusercontent.com/user/repo/" ext-dep-sha "/lib.cljs")
        manifest #js {"inject" #js [ext-url]}
        fetch-error {:error/type :ext-dep/fetch-failed
                     :error/phase :resolve
                     :error/dep-raw ext-url
                     :error/message (str "Failed to fetch " ext-url ": boom")}
        result (bg-actions/handle-action initial-state uf-data
                 [:msg/ax.load-manifest-ready :mock-send 42 manifest []
                  {:resolved {} :errors [fetch-error]}])
        fxs (:uf/fxs result)
        dxs (:uf/dxs result)
        response (response-payload fxs)]
    (-> (expect (execute-plan-effect fxs))
        (.toBeFalsy))
    (-> (expect (some #(= :storage/fx.persist-ext-dep-cache! (first %)) fxs))
        (.toBeFalsy))
    (-> (expect (:success response))
        (.toBe false))
    (-> (expect (:error response))
        (.toContain "Failed to fetch"))
    (-> (expect (:errors response))
        (.toEqual [(:error/message fetch-error)]))
    (-> (expect (count dxs))
        (.toBe 2))
    (-> (expect (first (first dxs)))
        (.toBe :banner/ax.broadcast-resolution-errors))
    (-> (expect (count (second (first dxs))))
        (.toBe 1))
    (-> (expect (-> dxs second first))
        (.toBe :msg/ax.log-resolution-error))))

(describe "manual ext-dep ready actions"
          (fn []
            (test "inject-libs-ready success persists cache and executes deps-only plan"
                  test-inject-libs-ready-success-persists-cache-executes-plan-and-sends-success)
            (test "inject-libs-ready merges fetched entries with existing state cache"
                  test-inject-libs-ready-merges-fetched-cache-with-existing-state-cache)
            (test "load-manifest-ready fetch failure sends failure and broadcasts resolution errors"
                  test-load-manifest-ready-fetch-failure-sends-failure-and-broadcasts-errors)))
