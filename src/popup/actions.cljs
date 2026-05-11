(ns popup.actions
  "Pure action handlers for the extension popup.
   No browser dependencies - testable without Chrome APIs."
  (:require [popup.actions.port-actions :as port-actions]
            [popup.actions.banner-actions :as banner-actions]
            [popup.actions.shadow-actions :as shadow-actions]
            [popup.actions.script-actions :as script-actions]
            [popup.actions.settings-actions :as settings-actions]
            [popup.actions.ui-actions :as ui-actions]
            [popup.actions.sponsor-actions :as sponsor-actions]
            [popup.actions.permission-actions :as permission-actions]
            [popup.actions.runtime-actions :as runtime-actions]
            [utils :as utils]))

(def normalize-domain-ports port-actions/normalize-domain-ports)

(defn handle-action
  "Pure action handler for popup state transitions.
   Routes to domain-specific handlers by action namespace."
  [state uf-data [action & _args :as action-vec]]
  (case (utils/kw-namespace action)
    "connection" (port-actions/handle-action state uf-data action-vec)
    "script" (script-actions/handle-action state uf-data action-vec)
    "settings" (settings-actions/handle-action state uf-data action-vec)
    "ui" (ui-actions/handle-action state uf-data action-vec)
    "sponsor" (sponsor-actions/handle-action state uf-data action-vec)
    "permission" (permission-actions/handle-action state uf-data action-vec)
    "banner" (banner-actions/handle-action state uf-data action-vec)
    "shadow-list" (shadow-actions/handle-action state uf-data action-vec)
    "runtime-status" (runtime-actions/handle-action state uf-data action-vec)
    :uf/unhandled-ax))