(ns e2e-reporting
  "Reporting and analysis utilities for E2E test output."
  (:require [clojure.string :as str]))

(defn- extract-specs-from-suite
  "Recursively extract test specs from a Playwright JSON suite structure.
   Each spec gets {:name :file :duration-ms}."
  [suite file]
  (let [file (or (:file suite) file)
        direct-specs (for [spec (:specs suite)
                           test (:tests spec)
                           result (:results test)]
                       {:name (:title spec)
                        :file (or (:file spec) file)
                        :duration-ms (:duration result)})
        nested-specs (mapcat #(extract-specs-from-suite % file)
                             (:suites suite))]
    (concat direct-specs nested-specs)))

(defn extract-test-timings
  "Extract test name and duration from Playwright JSON report structure.
   Handles nested suites created by .describe blocks.
   Returns seq of {:name string :duration-ms int :file string}"
  [json-data]
  (mapcat #(extract-specs-from-suite % nil) (:suites json-data)))

(defn- format-duration
  "Format milliseconds as human-readable string"
  [ms]
  (cond
    (>= ms 1000) (format "%.2fs" (/ ms 1000.0))
    :else (format "%dms" ms)))

(defn print-timing-report
  "Print formatted timing report to stdout"
  [timings]
  (let [sorted (sort-by :duration-ms timings)
        total-ms (reduce + (map :duration-ms timings))
        test-count (count timings)
        avg-ms (if (pos? test-count) (/ total-ms test-count) 0)]
    (println)
    (println "E2E Test Timing Report")
    (println "======================")
    (println (format "Tests: %d | Total: %s | Average: %s"
                     test-count
                     (format-duration total-ms)
                     (format-duration (long avg-ms))))
    (println)
    (println "Tests sorted by duration (fastest first):")
    (println (str (apply str (repeat 60 "-"))))
    (doseq [{:keys [name duration-ms]} sorted]
      (println (format "%-7s  %s"
                       (format-duration duration-ms)
                       name)))
    (println (str (apply str (repeat 60 "-"))))
    (println)
    (println "Slowest 10 tests:")
    (doseq [{:keys [name file duration-ms]} (take-last 10 sorted)]
      (println (format "  %-7s  %s (%s)"
                       (format-duration duration-ms)
                       name
                       file)))))

(defn- strip-ansi-codes [s]
  (str/replace s #"\x1b\[[0-9;]*m" ""))

(defn parse-playwright-summary
  "Parse Playwright summary from log output.
   Returns {:passed N :failed N :skipped N :flaky N} or nil if not found."
  [log-content]
  (let [clean-content (strip-ansi-codes log-content)
        passed-pattern #"(?m)^\s*(\d+)\s+passed\s*(?:\([^)]+\))?\s*$"
        failed-pattern #"(?m)^\s*(\d+)\s+failed\s*$"
        skipped-pattern #"(?m)^\s*(\d+)\s+skipped\s*$"
        flaky-pattern #"(?m)^\s*(\d+)\s+flaky\s*$"
        passed-match (re-find passed-pattern clean-content)
        failed-match (re-find failed-pattern clean-content)
        skipped-match (re-find skipped-pattern clean-content)
        flaky-match (re-find flaky-pattern clean-content)]
    (when passed-match
      {:passed (parse-long (nth passed-match 1))
       :failed (if failed-match (parse-long (nth failed-match 1)) 0)
       :skipped (if skipped-match (parse-long (nth skipped-match 1)) 0)
       :flaky (if flaky-match (parse-long (nth flaky-match 1)) 0)})))

(defn- count-test-files-in-log
  "Count unique test files mentioned in Playwright output."
  [log-content]
  (let [file-pattern #"build/e2e/([a-z_]+(?:_test|_spec)\.mjs):\d+:\d+"
        matches (re-seq file-pattern log-content)]
    (count (distinct (map second matches)))))

(defn aggregate-shard-results
  "Aggregate results from all shard log files.
   Returns {:files N :total N :passed N :failed N :flaky N :skipped N}."
  [log-files]
  (let [results (for [log-file log-files
                      :let [content (slurp log-file)
                            summary (parse-playwright-summary content)
                            files (count-test-files-in-log content)]
                      :when summary]
                  (assoc summary :files files))
        total-passed (reduce + (map :passed results))
        total-failed (reduce + (map :failed results))
        total-skipped (reduce + (map :skipped results))
        total-flaky (reduce + (map :flaky results))
        total-files (reduce + (map :files results))]
    {:files total-files
     :total (+ total-passed total-failed total-flaky total-skipped)
     :passed total-passed
     :failed total-failed
     :skipped total-skipped
     :flaky total-flaky}))

(defn print-test-summary
  "Print a summary of test results."
  [{:keys [files total passed failed skipped flaky]} & {:keys [failed-shards crashed-shards]
                                                        :or {failed-shards 0 crashed-shards 0}}]
  (println)
  (println (format "Files:     %3d" files))
  (println (format "Total:     %3d tests" total))
  (println (format "  Passed:  %3d" passed))
  (println (format "  Failed:  %3d%s" failed
                   (if (pos? failed-shards)
                     (str " (in " failed-shards " shard(s))")
                     "")))
  (println (format "  Flaky:   %3d" flaky))
  (println (format "  Skipped: %3d" skipped))
  (when (pos? crashed-shards)
    (println (format "Crashed: %3d shard(s) (test counts unavailable)" crashed-shards)))
  (if (and (zero? failed) (zero? crashed-shards))
    (println "Status:  ALL TESTS PASSED")
    (println "Status:  SOME TESTS FAILED")))

(defn extract-json-from-output
  "Extract JSON object from mixed output that may have log prefixes.
   Finds the first { and parses from there."
  [output]
  (when-let [json-start (str/index-of output "{")]
    (subs output json-start)))
