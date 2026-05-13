(ns e2e
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.http-server :as server]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [e2e-reporting :as reporting]))


;; ============================================================
;; Test Server Management
;; ============================================================

(def ^:private test-server-port 18080)

;; Two browser-nrepl servers for multi-tab testing
(def ^:private browser-nrepl-port-1 12345)
(def ^:private browser-ws-port-1 12346)
(def ^:private browser-nrepl-port-2 12347)
(def ^:private browser-ws-port-2 12348)

;; Default Playwright retries for flakiness tolerance
(def ^:private default-retries 2)

;; E2E output directory (project-local, gitignored)
;; Agents can read output with read_file instead of shell redirection.
(def ^:private e2e-tmp-dir ".tmp")
(def ^:private e2e-output-file (str e2e-tmp-dir "/e2e-output.txt"))
(def ^:private e2e-nrepl-log (str e2e-tmp-dir "/e2e-nrepl.log"))
(def ^:private e2e-history-dir (str e2e-tmp-dir "/e2e-history"))
(def ^:private default-history-count 10)

(defn- ensure-tmp-dir!
  "Ensure the .tmp directory exists for test output files."
  []
  (fs/create-dirs e2e-tmp-dir))

(defn- log-writer
  "Create an appending writer to the nREPL subprocess log file."
  []
  (ensure-tmp-dir!)
  (io/writer e2e-nrepl-log :append true))

(defn- wait-for-port
  "Wait for a port to become available, with timeout."
  [port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [timed-out? (> (System/currentTimeMillis) deadline)
            socket-open? (try
                           (with-open [_ (java.net.Socket. "localhost" port)]
                             true)
                           (catch Exception _ false))]
        (cond
          timed-out? false
          socket-open? true
          :else (do (Thread/sleep 100) (recur)))))))

(defn with-test-server
  "Execute f with an HTTP test server running on port 18080.
   Server is started before f and stopped after (even on exception)."
  [f]
  (let [stop-fn (server/serve {:port test-server-port :dir "test-data/pages"})]
    (try
      (println (format "Test server available at http://localhost:%d" test-server-port))
      (Thread/sleep 300) ; Give server time to fully start
      (f)
      (finally
        (stop-fn)
        (println "Test server stopped")))))

(defn- start-browser-nrepl-process
  "Start a browser-nrepl process on given ports.
   Output goes to e2e-log-file for clean console. Returns the process."
  [nrepl-port ws-port]
  (let [writer (log-writer)]
    (.write writer (str "\n=== browser-nrepl " nrepl-port "/" ws-port " ===\n"))
    (.flush writer)
    (p/process ["bb" "browser-nrepl"
                "--nrepl-port" (str nrepl-port)
                "--websocket-port" (str ws-port)]
               {:out writer :err writer})))

(defn with-browser-nrepls
  "Execute f with two browser-nrepl relay servers running.
   Enables multi-tab testing with different ports."
  [f]
  (println "Starting browser-nrepl servers...")
  (let [proc1 (start-browser-nrepl-process browser-nrepl-port-1 browser-ws-port-1)
        proc2 (start-browser-nrepl-process browser-nrepl-port-2 browser-ws-port-2)]
    (try
      (if (and (wait-for-port browser-nrepl-port-1 5000)
               (wait-for-port browser-nrepl-port-2 5000))
        (do
          (println (format "browser-nrepl #1 ready on ports %d / %d" browser-nrepl-port-1 browser-ws-port-1))
          (println (format "browser-nrepl #2 ready on ports %d / %d" browser-nrepl-port-2 browser-ws-port-2))
          (f))
        (throw (ex-info "browser-nrepl servers failed to start" {})))
      (finally
        (p/destroy-tree proc1)
        (p/destroy-tree proc2)
        (Thread/sleep 300)
        (println "browser-nrepl servers stopped")))))

(defn run-e2e-tests!
  "Run Playwright E2E tests with test server and two browser-nrepls.
   Subprocess output goes to .tmp/ for clean console output.
   Exits with Playwright's exit code without Babashka stack trace noise."
  [args]
  (println (str "nREPL log: " e2e-nrepl-log))
  (with-test-server
    #(with-browser-nrepls
       (fn []
         (let [args (into [(str "--retries=" default-retries)] args)
               result (apply p/shell {:continue true} "npx playwright test" args)]
           (System/exit (:exit result)))))))

;; ============================================================
;; ============================================================
;; E2E Testing
;; ============================================================

(def ^:private spinner-frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn- with-spinner
  "Run a function while displaying an animated spinner with message.
   Clears the spinner line when done."
  [message f]
  (let [stop? (atom false)
        spinner-thread (Thread.
                        (fn []
                          (loop [i 0]
                            (when-not @stop?
                              (print (str "\r" (nth spinner-frames (mod i (count spinner-frames))) " " message))
                              (flush)
                              (Thread/sleep 80)
                              (recur (inc i))))))]
    (.start spinner-thread)
    (try
      (f)
      (finally
        (reset! stop? true)
        (.join spinner-thread 200)
        ;; Clear the spinner line
        (print (str "\r" (apply str (repeat (+ 3 (count message)) " ")) "\r"))
        (flush)))))

(defn- run-docker-shard
  "Run Docker container with Playwright's native sharding.
   Returns map with process and writer for cleanup."
  [shard-idx n-shards log-file extra-args]
  (let [writer (io/writer log-file)
        cmd (into ["docker" "run" "--rm" "epupp-e2e"
                   (str "--shard=" (inc shard-idx) "/" n-shards)]
                  extra-args)]
    (.write writer (str "\n=== Shard " (inc shard-idx) "/" n-shards " ===\n\n"))
    (.flush writer)
    {:process (p/process cmd {:out writer :err writer})
     :writer writer}))

(defn- run-build-step!
  "Run a build command, capturing output. Returns result map.
   Throws with output on failure."
  [cmd]
  (let [result (p/shell {:out :string :err :string :continue true} cmd)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Build failed: " cmd)
                      {:cmd cmd
                       :exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))
    result))

(defn- build-e2e!
  "Build E2E tests and Docker image.
   Suppresses build output unless failure. Exits on failure."
  []
  (try
    (with-spinner "Building tests and Docker image..."
      (fn []
        (run-build-step! "bb build:test")
        (run-build-step! "bb test:e2e:compile")
        (run-build-step! "docker build --platform linux/arm64 -f Dockerfile.e2e -t epupp-e2e .")))
    (catch clojure.lang.ExceptionInfo e
      (println "\n\n❌ Build failed!")
      (let [{:keys [cmd out err]} (ex-data e)]
        (println (str "Command: " cmd))
        (when (seq out)
          (println "\nStdout:")
          (println out))
        (when (seq err)
          (println "\nStderr:")
          (println err)))
      (System/exit 1))))

(defn- delete-if-exists! [path]
  (when (fs/exists? path) (fs/delete path)))

(defn- delete-tree-if-exists! [path]
  (when (fs/exists? path) (fs/delete-tree path)))

(defn- move-if-exists! [src dst]
  (when (fs/exists? src) (fs/move src dst)))

(defn- rotate-e2e-artifacts!
  "Rotate E2E output artifacts into .tmp/e2e-history/ before a new run.
   Shifts existing backups (1 -> 2, 2 -> 3, etc.) and moves current
   e2e-output.txt and e2e-shards/ into slot 1. Deletes anything beyond
   history-count."
  [history-count]
  (let [output-file e2e-output-file
        shard-dir (str e2e-tmp-dir "/e2e-shards")
        has-output? (fs/exists? output-file)
        has-shards? (fs/exists? shard-dir)]
    (when (or has-output? has-shards?)
      (fs/create-dirs e2e-history-dir)
      ;; Delete oldest slot if at capacity
      (delete-if-exists! (str e2e-history-dir "/e2e-output-" history-count ".txt"))
      (delete-tree-if-exists! (str e2e-history-dir "/e2e-shards-" history-count))
      ;; Shift existing backups: N-1 -> N, ..., 2 -> 3, 1 -> 2
      (doseq [i (range history-count 1 -1)]
        (move-if-exists! (str e2e-history-dir "/e2e-output-" (dec i) ".txt")
                         (str e2e-history-dir "/e2e-output-" i ".txt"))
        (move-if-exists! (str e2e-history-dir "/e2e-shards-" (dec i))
                         (str e2e-history-dir "/e2e-shards-" i)))
      ;; Move current artifacts into slot 1
      (when has-output?
        (fs/move output-file (str e2e-history-dir "/e2e-output-1.txt")))
      (when has-shards?
        (fs/move shard-dir (str e2e-history-dir "/e2e-shards-1"))))))

(defn- run-e2e-serial!
  "Run E2E tests sequentially in a single Docker container."
  [extra-args {:keys [build?] :or {build? true}}]
  (when build?
    (build-e2e!))
  (println "Running tests (serial)...")
  (when (seq extra-args)
    (println (str "  Extra Playwright args: " (str/join " " extra-args))))
  (let [result (apply p/shell {:continue true} "docker" "run" "--rm" "epupp-e2e" extra-args)]
    (:exit result)))

(defn- finalize-shard! [{:keys [idx process writer done? exit-code]} start-time n-shards]
  (let [proc (:proc process)]
    (when-not (.isAlive proc)
      (let [exit (.exitValue proc)
            elapsed-s (/ (- (System/currentTimeMillis) start-time) 1000.0)]
        (.close writer)
        (reset! exit-code exit)
        (reset! done? true)
        (println (format "  Shard %d/%d finished at %.1fs (exit %d)"
                         (inc idx) n-shards elapsed-s exit))))))

(defn- wait-for-shards-completion! [shards start-time n-shards]
  (loop []
    (let [still-running (filter #(not @(:done? %)) shards)]
      (when (seq still-running)
        (doseq [shard still-running]
          (finalize-shard! shard start-time n-shards))
        (Thread/sleep 100)
        (recur)))))

(defn- run-e2e-parallel!
  "Run E2E tests in parallel Docker containers using Playwright's native sharding."
  [n-shards extra-args {:keys [build?] :or {build? true}}]
  ;; Build phase (if requested)
  (when build?
    (build-e2e!))

  ;; Run shards in parallel using Playwright's native sharding
  (println (str "Running " n-shards " parallel shards..."))
  (when (seq extra-args)
    (println (str "  Extra Playwright args: " (str/join " " extra-args))))
  (ensure-tmp-dir!)
  (let [shard-dir (str e2e-tmp-dir "/e2e-shards")
        _ (do (fs/delete-tree shard-dir) (fs/create-dirs shard-dir))
        start-time (System/currentTimeMillis)
        shards (doall
                (for [idx (range n-shards)]
                  (let [log-file (str shard-dir "/shard-" idx ".log")
                        {:keys [process writer]} (run-docker-shard idx n-shards log-file extra-args)]
                    {:idx idx
                     :process process
                     :writer writer
                     :log-file log-file
                     :done? (atom false)
                     :exit-code (atom nil)})))
        _ (wait-for-shards-completion! shards start-time n-shards)
        results (map (fn [{:keys [idx exit-code log-file]}]
                       {:idx idx :exit @exit-code :log-file log-file})
                     shards)
        elapsed-ms (- (System/currentTimeMillis) start-time)
        failed-shards (filter #(not= 0 (:exit %)) results)
        log-files (map :log-file results)
        summary (reporting/aggregate-shard-results log-files)
        ;; Crashed = exited non-zero but no parseable test summary
        crashed-shards (count (filter (fn [{:keys [log-file]}]
                                        (nil? (reporting/parse-playwright-summary (slurp log-file))))
                                      failed-shards))
        shards-with-test-failures (- (count failed-shards) crashed-shards)]

    (println)
    (println (str "Completed " n-shards " shards in " (format "%.1fs" (/ elapsed-ms 1000.0))))

    (reporting/print-test-summary summary
                        :failed-shards shards-with-test-failures
                        :crashed-shards crashed-shards)

    ;; Write combined shard output for tool consumption
    (spit e2e-output-file (str/join "\n" (map slurp log-files)))

    (println "Full test output:" e2e-output-file)
    (println "Shards:" shard-dir)
    (println "Previous runs:" e2e-history-dir)
    (if (seq failed-shards) 1 0)))

; Playwright's stupid sharding will make it vary a lot what n-shards is the best
(def ^:private default-n-shards 10)

(defn ^:export run-e2e!
  "Run E2E tests in Docker. Parallel by default, --serial to disable this (but why would you?).

   Options:
     --shards N   Number of parallel shards (default: 13)
     --serial     Run sequentially (very seldom needed)
     --history N  Number of past runs to keep in .tmp/e2e-history/ (default: 10)

   Use -- to separate bb options from Playwright options:
     bb test:e2e -- --grep \"popup\""
  [args]
  (let [{:keys [args opts]} (cli/parse-args args {:coerce {:shards :int :history :int}
                                                  :alias {:s :serial}})
        args (into [(str "--retries=" default-retries)] args)
        serial? (:serial opts)
        n-shards (or (:shards opts) default-n-shards)
        history-count (or (:history opts) default-history-count)]
    (rotate-e2e-artifacts! history-count)
    (if serial?
      (let [exit-code (run-e2e-serial! args {:build? true})]
        (System/exit exit-code))
      (let [exit-code (run-e2e-parallel! n-shards args {:build? true})]
        (System/exit exit-code)))))

(defn e2e-timing-report!
  "Run E2E tests in Docker with JSON reporter and print timing report.
   Sorted fastest-first so you can tail for slowest tests."
  [_args]
  (build-e2e!)
  (let [result (atom nil)]
    (with-spinner "Running E2E tests (collecting timing data)..."
      #(reset! result (p/shell {:out :string :err :string :continue true}
                               "docker" "run" "--rm" "epupp-e2e"
                               "--reporter=json")))
    (let [{:keys [exit out]} @result
          json-str (reporting/extract-json-from-output out)]
      (if (and (zero? exit) json-str)
        (let [json-data (json/read-str json-str :key-fn keyword)
              timings (reporting/extract-test-timings json-data)]
          (reporting/print-timing-report timings))
        (do
          (println "Tests failed or no JSON output - cannot generate timing report")
          (when-not (str/blank? out)
            (println "Output preview:")
            (println (subs out 0 (min 500 (count out)))))
          (println "Run 'bb test:e2e' to see full test output")
          (System/exit (if (zero? exit) 1 exit)))))))
