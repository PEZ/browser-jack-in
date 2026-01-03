---
description: 'Uniflow event handling system - unidirectional data flow for Squint/ClojureScript'
applyTo: '**'
---

# Uniflow Event Handler

Uniflow is a minimal, Re-frame-inspired data flow system. Unlike Re-frame, it's unidirectional, intentionally small, and meant to be adapted per project. Recreate it to fit like a glove.

## Core Concepts

```
UI event → dispatch!([actions]) → handle-actions → state update → dxs dispatch → effects
```

### Actions (ax) — Pure State Transitions

```clojure
(defn handle-action [state uf-data [action & args]]
  (case action
    :editor/ax.set-code
    (let [[code] args]
      {:uf/db (assoc state :panel/code code)})

    :uf/unhandled-ax))  ; Fall back to generic handler
```

**Return map keys:**
- `:uf/db` — New state
- `:uf/fxs` — Effects to perform (accumulated across batch)
- `:uf/dxs` — Actions to dispatch after state update, before effects (last wins)

### Effects (fx) — Side Effects

```clojure
(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :editor/fx.save-script
    (let [[script] args]
      (storage/save-script! script))

    :uf/unhandled-fx))  ; Fall back to generic handler
```

Effects receive `dispatch` for async callbacks that need to trigger more actions.

### Dispatching

```clojure
;; Dispatch batched actions (processed in order, each sees updated state)
(dispatch! [[:editor/ax.set-code "hello"]
            [:editor/ax.eval]])

;; Single action works too
(dispatch! [[:editor/ax.clear-results]])
```

## Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Actions | `:domain/ax.verb` | `:editor/ax.set-code`, `:db/ax.assoc` |
| Effects | `:domain/fx.verb` | `:editor/fx.eval-in-page`, `:uf/fx.defer-dispatch` |
| Framework | `:uf/...` | `:uf/db`, `:uf/fxs`, `:uf/dxs`, `:uf/unhandled-ax` |

## Framework Context (`uf-data`)

Actions receive `uf-data` with framework-provided context:

```clojure
{:system/now 1735920000000}  ; Current timestamp
```

Extend with project-specific data as needed.

## Subsystem Setup Pattern

Each module defines its own handlers and creates a local `dispatch!`:

```clojure
(ns my-module
  (:require [event-handler :as event-handler]))

(defonce !state (atom {:my/value ""}))

(defn perform-effect! [dispatch [effect & args]]
  (case effect
    :my/fx.do-thing (do-thing! (first args))
    :uf/unhandled-fx))

(defn handle-action [state uf-data [action & args]]
  (case action
    :my/ax.set-value {:uf/db (assoc state :my/value (first args))}
    :uf/unhandled-ax))

(defn dispatch! [actions]
  (event-handler/dispatch! !state handle-action perform-effect! actions))
```

## Generic Handlers

Return `:uf/unhandled-ax` or `:uf/unhandled-fx` to delegate to generic handlers in `event-handler.cljs`:

**Built-in actions:**
- `:db/ax.assoc` — `(dispatch! [[:db/ax.assoc :key value :key2 value2]])`

**Built-in effects:**
- `:uf/fx.defer-dispatch` — `[:uf/fx.defer-dispatch [actions] timeout-ms]`
- `:log/fx.log` — `[:log/fx.log :error "message" data]`

## When to Use What

| Scenario | Use |
|----------|-----|
| Pure state change | Action returning `:uf/db` |
| Trigger side effect | Action returning `:uf/fxs` |
| Sync follow-up actions | Action returning `:uf/dxs` |
| Async callback needs dispatch | Effect with `dispatch` parameter |
| Delayed dispatch | `:uf/fx.defer-dispatch` effect |

## Key Principles

1. **Actions are pure** — Given same state + uf-data + action, same result
2. **Effects are impure** — Side effects, async ops, external APIs
3. **Batch for composition** — Small actions, combine in dispatch calls
4. **Fallback for reuse** — Generic handlers via `:uf/unhandled-*`

## Current Limitations

**Cross-subsystem calls not supported.** Each module has its own handlers; one subsystem cannot dispatch actions handled by another. The generic fallback (`:uf/unhandled-*`) provides shared utilities, but not cross-module communication.

If the app grows to need this, consider adding a registry pattern where subsystems register their handlers. Adapt Uniflow to fit—that's the point.
