(ns workspace-activate
  (:require [joyride.core :as joyride]
            [promesa.core :as p]
            scittle-repl
            ["vscode" :as vscode]))

(defonce !db (atom {:disposables []}))

;; To make the activation script re-runnable we dispose of
;; event handlers and such that we might have registered
;; in previous runs.
(defn- clear-disposables! []
  (run! (fn [disposable]
          (.dispose disposable))
        (:disposables @!db))
  (swap! !db assoc :disposables []))

;; Pushing the disposables on the extension context's
;; subscriptions will make VS Code dispose of them when the
;; Joyride extension is deactivated.
(defn- push-disposable [disposable]
  (swap! !db update :disposables conj disposable)
  (-> (joyride/extension-context)
      .-subscriptions
      (.push disposable)))

(defn- my-main []
  (println "Hello World, from my-main workspace_activate.cljs script")
  (clear-disposables!)
  #_(push-disposable
     ;; This is just an example. Remove it when it starts to annoy you.
     (vscode/workspace.onDidOpenTextDocument
      (fn [doc]
        (println "[Joyride example]"
                 (.-languageId doc)
                 "document opened:"
                 (.-fileName doc)))))

  (p/let [tasks (vscode/tasks.fetchTasks)
          task (->> tasks
                    (filter #(= "Start Dev Environment" (.-name %)))
                    (filter #(-> % .-scope .-uri .-path (.endsWith "/epupp")))
                    first)]
    (when task
      (vscode/tasks.executeTask task))
    (p/delay 1500)
    (vscode/commands.executeCommand "calva.connect" #js {:connectSequence "Babashka REPL"})
    (vscode/commands.executeCommand "calva.connect" #js {:connectSequence "Squint REPL"}))
  (scittle-repl/start!+)
  (vscode/commands.executeCommand "calva.connect" #js {:connectSequence "Scittle Dev REPL"}))

(when (= (joyride/invoked-script) joyride/*file*)
  (my-main))
