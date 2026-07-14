# Library Namespaces Architecture

Any userscript can serve as a shared library by being referenced via `epupp://script-name.cljs` in another script's `:epupp/inject` manifest key. This document describes the contracts and design decisions that govern the feature.

## Dependency Protocols

Four dependency styles are supported in `:epupp/inject`:

| Protocol | Example | Resolves to |
|---|---|---|
| `scittle://` | `scittle://reagent.js` | Bundled vendor library from `scittle_libs.cljs` catalog |
| `epupp://` | `epupp://utils/dom.cljs` | Stored userscript, looked up by normalized name |
| `epupp://` | `epupp://epupp/web_installer.css` | CSS file from the extension's `userscripts/` directory |
| `https://` | `https://raw.githubusercontent.com/user/repo/SHA/path.cljs` | External dependency from a trusted GitHub raw host, resolved from `extDepCache` |
| `https://` | `https://gist.githubusercontent.com/user/ID/raw/SHA/file.cljs` | GitHub gist file from a trusted raw host, resolved from `extDepCache` |

Any URL ending in `.css` (regardless of protocol) is classified as `:css` and injected as a `<link rel="stylesheet">` tag rather than a script tag.

Only supported protocols participate in dependency resolution. Unsupported or unknown inject URLs are classified as `:unknown` and ignored - they do not become execution-plan steps.

External dependencies require a full 40-character SHA (no branch/tag references). Saved scripts still prefetch uncached external dependencies. Manual library-loading entry points are cache-first: they reuse cached URLs immediately and fetch only missing supported HTTPS URLs before continuing. Auto-run and page-load injection resolve from `extDepCache` only. See [injection-flows.md](injection-flows.md#external-dependency-resolution) for the full flow.

## Name Resolution

`epupp://` lookup uses normalized script names - the same rules as `script_utils/normalize-script-name`:

- Lowercase
- Spaces and dashes become underscores
- Dots become path separators
- `.cljs` extension appended if missing
- Invalid characters stripped

So `epupp://My Utils.cljs`, `epupp://my-utils`, and `epupp://my_utils.cljs` all resolve to the same script (`my_utils.cljs`).

## Library Identity

Any script can be resolved as an `epupp://` dependency if its normalized name exists in the script catalog. The resolver does not require a dedicated library-only storage class.

Separately, parsed script maps do carry derived library metadata. `script_utils.cljs` derives `:script/library?` from the manifest's `:epupp/library?` key, and UI helpers such as `script-utils/library-script?` and the popup Libraries section use that flag for grouping and presentation.

That flag is runtime metadata on parsed script maps, not a separate storage column written by `script->js`. It is re-derived from the manifest when scripts are loaded.

### Disabled scripts are valid library targets

The `:script/enabled` flag controls whether a script auto-runs on matching pages. It does not affect library availability. A disabled script can be referenced and injected as a dependency.

### Built-in scripts are valid library targets

Scripts with `:script/builtin? true` (like `epupp/sponsor.cljs`) can be referenced via `epupp://`. Their immutability and always-enabled status are orthogonal to their use as libraries.

Built-in library scripts live under the reserved `epupp/` path. For example:

- `epupp://epupp/internal/helpers.cljs`
- `epupp://epupp/storage.cljs`
- `epupp://epupp/tools.cljs`
- `epupp://epupp/ui.cljs`

`epupp.tools` and `epupp.storage` are dual-delivery APIs: injected automatically at REPL connect time and available as built-in libraries via `epupp://` in `:epupp/inject`. Both live under `extension/userscripts/epupp/` as a single source — there is no separate `bundled/` twin. REPL-only APIs (`epupp.fs`, `epupp.repl`) remain under `extension/bundled/epupp/`. See [connected-repl.md](connected-repl.md) for the full list of bootstrap namespaces.

Built-ins also dogfood the same mechanism. `epupp/web_userscript_installer.cljs` and `epupp/sponsor.cljs` declare `:epupp/inject` dependencies on these built-in libraries via `epupp://`.

## Contracts

### Dependency Node

Represents a single parsed dependency reference from `:epupp/inject`:

```clojure
{:dep/kind    :css|:scittle|:epupp|:ext-dep  ; protocol classification
 :dep/raw     "epupp://utils/dom.cljs" ; original manifest string
 :dep/name    "utils/dom.cljs"      ; normalized lookup key
 :dep/script  {... script data ...}} ; resolved script record, or nil
```

### Resolved Execution Plan

The resolver produces an ordered plan of injection steps:

```clojure
{:plan/steps
 [{:step/type :css-file              ; :css-file | :vendor-file | :library-script | :ext-dep-script | :root-script
   :step/path "userscripts/epupp/web_installer.css"
   :step/source :epupp}              ; or :external for non-epupp:// CSS URLs
  {:step/type :vendor-file
   :step/path "vendor/scittle.reagent.js"
   :step/source :scittle}
  {:step/type :library-script
   :step/id   "script-abc-123"
   :step/name "utils/dom.cljs"
   :step/code "..."
   :step/source :epupp}
  {:step/type :ext-dep-script
   :step/url  "https://raw.githubusercontent.com/user/repo/SHA/helpers.cljs"
   :step/code "..."
   :step/source :ext}
  {:step/type :root-script
   :step/id   "script-xyz-789"
   :step/name "my/tweaks.cljs"
   :step/code "..."
   :step/source :epupp}]
 :plan/vendor-namespaces ["reagent.core"]
 :plan/errors []}
```

Steps are ordered: CSS files first, then vendor files, then library scripts and external dependency scripts in dependency order, then root scripts. CSS steps carry `:step/path` (for `epupp://`) or `:step/url` (for external CSS); vendor steps carry `:step/path`; external dependency steps carry `:step/url` and `:step/code`; script steps carry `:step/id`, `:step/name`, and `:step/code`. Each file, URL, and script appears at most once (deduplicated). CSS dedup is also tracked at injection time via `window.__epuppInjectedStyles`. Roots are represented by `:step/type :root-script` entries inside `:plan/steps`; there is no separate `:plan/roots` collection.

### Resolution Error Envelope

Resolution failures produce structured error maps. Resolver-produced errors use this envelope:

```clojure
{:error/type   :library/not-found|:library/cycle|:library/self-reference|:ext-dep/cache-miss|:ext-dep/fetch-failed|:ext-dep/cycle
 :error/phase  :resolve
 :error/script-name "my/tweaks.cljs"
 :error/dep-raw "epupp://missing.cljs"
 :error/dep-chain ["my/tweaks.cljs" "utils/dom.cljs" "missing.cljs"]
 :error/message "Library not found: missing.cljs (required by utils/dom.cljs, required by my/tweaks.cljs)"}
```

Fetch-stage external dependency errors follow the same `:error/*` naming pattern, but may omit script-specific keys such as `:error/script-name` and `:error/dep-chain` when the failing URL has not yet been attached to a consumer script.

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

Repeated re-resolution with the same error envelope is treated as unchanged, so runtime banners track new failures instead of replaying identical ones.

## Implementation

### Dependency Resolver (`dep_resolver.cljs`)

The resolver is a pure function that takes a set of root scripts and a script-lookup function, and produces an ordered execution plan. It accepts an optional `ext-dep-cache` map for resolving supported HTTPS external dependencies. It handles:

- Mixed `scittle://` + `epupp://` + HTTPS ext-dep graphs
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
| Panel evaluation | DevTools panel sends `inject-libs` and `ensure-scittle` as needed | Background resolves and executes a synthetic deps-only plan; the panel then evaluates code via `chrome.devtools.inspectedWindow.eval` |
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
