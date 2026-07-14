(ns popup.script-views-categorize-test
  "Unit tests for popup script categorization."
  (:require ["vitest" :refer [describe test expect]]
            [popup.views.script-views :as popup-script-views]))

(defn- test-categorize-scripts-excludes-internal-from-all-categories []
  (let [internal {:script/name "epupp/internal/helpers.cljs"
                  :script/library? true
                  :script/builtin? true
                  :script/match []}
        manual {:script/name "manual.cljs" :script/match []}
        categories (popup-script-views/categorize-scripts [internal manual] "https://example.com/")
        all-scripts (mapcat val categories)
        names (set (map :script/name all-scripts))]
    (-> (expect names)
        (.not.toContain "epupp/internal/helpers.cljs"))
    (-> (expect names)
        (.toContain "manual.cljs"))))

(describe "categorize-scripts internal exclusion"
  (fn []
    (test "excludes epupp/internal/ scripts from all categories"
          test-categorize-scripts-excludes-internal-from-all-categories)))
