(ns panel-actions
  "Routes panel actions to domain-specific handler modules."
  (:require [panel.actions.eval-actions :as eval-actions]
            [panel.actions.script-actions :as script-actions]
            [panel.actions.banner-actions :as banner-actions]))

(def default-script script-actions/default-script)

(def ^:private eval-action-set
  {:editor/ax.eval true
   :editor/ax.eval-selection true
   :editor/ax.do-eval true
   :editor/ax.handle-eval-result true
   :editor/ax.check-scittle true
   :editor/ax.update-scittle-status true})

(def ^:private script-action-set
  {:editor/ax.save-script true
   :editor/ax.save-script-overwrite true
   :editor/ax.handle-save-response true
   :editor/ax.rename-script true
   :editor/ax.handle-rename-response true
   :editor/ax.load-script-for-editing true
   :editor/ax.reload-script-from-storage true
   :editor/ax.new-script true
   :editor/ax.check-editing-script true
   :editor/ax.set-code true
   :editor/ax.initialize-editor true})

(def ^:private banner-action-set
  {:editor/ax.show-system-banner true
   :editor/ax.clear-system-banner true
   :editor/ax.track-bulk-name true
   :editor/ax.clear-bulk-names true
   :panel/ax.handle-system-banner true})

;; Actions that simply assoc first arg to a state key
(def ^:private simple-assoc-actions
  {:editor/ax.set-script-name :panel/script-name
   :editor/ax.set-script-match :panel/script-match
   :editor/ax.set-script-description :panel/script-description
   :editor/ax.set-selection :panel/selection
   :editor/ax.set-tab-connected :panel/tab-connected?
   :editor/ax.set-init-version :panel/init-version})

;; Actions that dispatch a single effect with no args
(def ^:private effect-only-actions
  {:editor/ax.use-current-url [[:editor/fx.use-current-url [:db/ax.assoc :panel/script-match]]]
   :editor/ax.check-sponsor [[:editor/fx.check-sponsor]]
   :editor/ax.load-sponsor-status [[:editor/fx.load-sponsor-status]]})

(defn- handle-check-version [state args]
  (let [[current-version] args
        init-version (:panel/init-version state)
        version-mismatch? (or (nil? current-version)
                              (and init-version (not= current-version init-version)))]
    (when version-mismatch?
      {:uf/fxs [[:log/fx.log :debug "Panel" "Extension updated or context invalidated"]]
       :uf/dxs [[:editor/ax.set-needs-refresh]]})))

(defn- handle-misc-action [state action args]
  (case action
    :editor/ax.toggle-creator-menu
    {:uf/db (update state :ui/creator-menu-open? not)}

    :editor/ax.close-creator-menu
    {:uf/db (assoc state :ui/creator-menu-open? false)}

    :editor/ax.clear-results
    {:uf/db (assoc state :panel/results [])}

    :editor/ax.clear-code
    {:uf/db (assoc state :panel/code "")}

    :editor/ax.set-needs-refresh
    {:uf/db (assoc state :panel/needs-refresh? true)}

    :editor/ax.reset-for-navigation
    {:uf/db (assoc state :panel/evaluating? false :panel/scittle-status :unknown)}

    :editor/ax.handle-ws-close
    {:uf/db (assoc state :panel/scittle-status :unknown :panel/tab-connected? false)}

    :editor/ax.update-scripts-list
    {:uf/db (assoc state :panel/scripts-list (first args))}

    :panel/ax.check-version
    (handle-check-version state args)

    :panel/ax.handle-runtime-status
    (let [[{:keys [errors]}] args]
      {:uf/db (assoc state :runtime/errors errors)})
    :uf/unhandled-ax))

(defn handle-action
  "Routes panel actions to domain-specific handler modules."
  [state uf-data [action & args :as action-vec]]
  (cond
    (get eval-action-set action)
    (eval-actions/handle-action state uf-data action-vec)

    (get script-action-set action)
    (script-actions/handle-action state uf-data action-vec)

    (get banner-action-set action)
    (banner-actions/handle-action state uf-data action-vec)

    (get simple-assoc-actions action)
    {:uf/db (assoc state (get simple-assoc-actions action) (first args))}

    (get effect-only-actions action)
    {:uf/fxs (get effect-only-actions action)}

    :else
    (handle-misc-action state action args)))
