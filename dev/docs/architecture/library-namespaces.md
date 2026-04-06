# Library Namespaces Architecture

Any userscript can serve as a shared library by being referenced via `epupp://script-name.cljs` in another script's `:epupp/inject` manifest key. This document describes the contracts and design decisions that govern the feature.

## Dependency Protocols

Two URL protocols are supported in `:epupp/inject`:

| Protocol | Example | Resolves to |
|---|---|---|
| `scittle://` | `scittle://reagent.js` | Bundled vendor library from `scittle_libs.cljs` catalog |
| `epupp://` | `epupp://utils/dom.cljs` | Stored userscript, looked up by normalized name |

Unknown protocols are passed through without resolution (future-proofing).

## Name Resolution

`epupp://` lookup uses normalized script names - the same rules as `script_utils/normalize-script-name`:

- Lowercase
- Spaces and dashes become underscores
- Dots become path separators
- `.cljs` extension appended if missing
- Invalid characters stripped

So `epupp://My Utils.cljs`, `epupp://my-utils`, and `epupp://my_utils.cljs` all resolve to the same script (`my_utils.cljs`).

## Library Identity

Library-ness is **emergent, not stored**. Any script becomes a library when another script references it. There is no `:script/library?` flag, no special storage field, no distinction in the script data contract. This keeps the storage model simple and avoids synchronization issues.

### Disabled scripts are valid library targets

The `:script/enabled` flag controls whether a script auto-runs on matching pages. It does not affect library availability. A disabled script can be referenced and injected as a dependency.

### Built-in scripts are valid library targets

Scripts with `:script/builtin? true` (like `epupp/sponsor.cljs`) can be referenced via `epupp://`. Their immutability and always-enabled status are orthogonal to their use as libraries.

Built-in library scripts live under the reserved `epupp/` path. For example:

- `epupp://epupp/internal/helpers.cljs`
- `epupp://epupp/ui.cljs`

Built-ins also dogfood the same mechanism. `epupp/web_userscript_installer.cljs` and `epupp/sponsor.cljs` declare `:epupp/inject` dependencies on these built-in libraries via `epupp://`.

## Contracts

### Dependency Node

Represents a single parsed dependency reference from `:epupp/inject`:

```clojure
{:dep/kind    :scittle|:epupp       ; protocol classification
 :dep/raw     "epupp://utils/dom.cljs" ; original manifest string
 :dep/name    "utils/dom.cljs"      ; normalized lookup key
 :dep/script  {... script data ...}} ; resolved script record, or nil
```

### Resolved Execution Plan

The resolver produces an ordered plan of injection steps:

```clojure
{:plan/steps
 [{:step/type :vendor-file           ; :vendor-file | :library-script | :root-script
   :step/id   "scittle.reagent.js"
   :step/file "scittle.reagent.js"}
  {:step/type :library-script
   :step/id   "script-abc-123"
   :step/name "utils/dom.cljs"
   :step/code "..."}
  {:step/type :root-script
   :step/id   "script-xyz-789"
   :step/name "my/tweaks.cljs"
   :step/code "..."}]
 :plan/roots ["script-xyz-789"]
 :plan/vendor-namespaces ["reagent.core"]
 :plan/errors []}
```

Steps are ordered: vendor files first, then library scripts in dependency order, then root scripts. Each file and script appears at most once (deduplicated).

### Runtime Error Envelope

Resolution failures produce structured error envelopes:

```clojure
{:error/type   :library/not-found|:library/cycle|:library/self-reference
 :error/phase  :resolve|:inject|:verify
 :error/surface :idle|:panel|:load-manifest|:early-loader
 :script/id    "script-xyz-789"
 :script/name  "my/tweaks.cljs"
 :dep/raw      "epupp://missing.cljs"
 :dep/chain    ["my/tweaks.cljs" "utils/dom.cljs" "missing.cljs"]
 :tab/id       42
 :error/message "Library not found: missing.cljs (required by utils/dom.cljs, required by my/tweaks.cljs)"}
```

### Failure Status

Runtime failure status is **ephemeral and tab-scoped**. It lives in the background worker's in-memory state, keyed by `[tab-id script-id]`. It is:

- **Not persisted** to `chrome.storage` - cleared on service worker restart
- **Cleared on navigation** - each page load gets a fresh resolution attempt
- **Cleared on successful resolution** - transient errors don't stick

```clojure
{:status/error   error-envelope
 :status/tab-id  42
 :status/script-id "script-xyz-789"
 :status/cleared? false}
```

This keeps the storage model clean and avoids stale error indicators across sessions.

## Implementation

### Dependency Resolver (`dep_resolver.cljs`)

The resolver is a pure function that takes a set of root scripts and a script-lookup function, and produces an ordered execution plan. It handles:

- Mixed `scittle://` + `epupp://` graphs
- Topological ordering (libraries before consumers)
- Deduplication (each script and vendor file appears at most once)
- Cycle detection with chain reporting
- Self-reference detection

### Execution Paths

All four execution paths use the resolver:

| Path | Trigger | Resolution |
|------|---------|------------|
| Auto-run (`document-idle`) | `webNavigation.onCompleted` | Background resolves before injection |
| Early timing (`document-start`/`end`) | Content script registration | Loader resolves from storage at injection time |
| Panel "Run" button | Popup sends `evaluate-script` | Background resolves before injection |
| REPL `load-manifest` | nREPL eval of `epupp.repl/manifest!` | Background resolves before injection |

### Error Surfacing

Resolution errors appear in three places:

1. **Console** - full error details logged
2. **System banner** - visible notification in popup/panel
3. **Per-script warning indicator** - ⚠ icon next to affected scripts in popup and panel

### Early Loader

The userscript loader (`src/userscript_loader.cljs`) was rewritten in Squint and compiles through the standard build pipeline (`Squint -> esbuild`), replacing the previously hand-maintained JavaScript file.

### Double Injection Caveat (v1)

When a script both auto-runs on a page and is referenced as an `epupp://` library by another auto-run script on that same page, it may execute twice. This is a known v1 limitation.
