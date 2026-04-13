# Epupp Security Model

## Trust Boundaries and Execution Contexts

```mermaid
graph TB
    subgraph EXT["Extension Context (outside page CSP)"]
        direction TB
        BG["Background Service Worker<br/><i>chrome.runtime, WebSocket, storage</i>"]
        POPUP["Popup / Panel<br/><i>chrome.runtime.sendMessage</i>"]
    end

    subgraph PAGE["Page Context (subject to page CSP)"]
        direction TB
        subgraph ISOLATED["ISOLATED World"]
            CB["Content Bridge<br/><i>Message registry gate</i><br/><i>Source + type validation</i>"]
        end
        subgraph MAIN["MAIN World"]
            WS["WS Bridge<br/><i>postMessage relay</i>"]
            SCI["Scittle / SCI<br/><i>Pure interpreter</i><br/><i>Extension-origin script</i>"]
            US["Userscripts<br/><i>&lt;script type=application/x-scittle&gt;</i>"]
        end
    end

    subgraph LOCAL["Developer Machine"]
        RELAY["bb browser-nrepl relay"]
        EDITOR["Editor / AI<br/><i>nREPL client</i>"]
    end

    EDITOR <-->|nREPL| RELAY
    RELAY <-->|WebSocket| BG
    POPUP <-->|chrome.runtime| BG
    BG <-->|chrome.tabs.sendMessage| CB
    CB <-->|postMessage| WS
    WS <--> SCI
    SCI --> US

    style EXT fill:#1a3a1a,stroke:#4a4,color:#fff
    style ISOLATED fill:#1a2a3a,stroke:#48f,color:#fff
    style MAIN fill:#3a2a1a,stroke:#f84,color:#fff
    style LOCAL fill:#2a2a2a,stroke:#888,color:#fff
    style CB fill:#1a2a3a,stroke:#48f,color:#fff
    style BG fill:#1a3a1a,stroke:#4a4,color:#fff
    style POPUP fill:#1a3a1a,stroke:#4a4,color:#fff
    style WS fill:#3a2a1a,stroke:#f84,color:#fff
    style SCI fill:#3a2a1a,stroke:#f84,color:#fff
    style US fill:#3a2a1a,stroke:#f84,color:#fff
    style RELAY fill:#2a2a2a,stroke:#888,color:#fff
    style EDITOR fill:#2a2a2a,stroke:#888,color:#fff
```

**Key boundaries:**
- **Green** (Extension context) - completely outside page CSP. WebSocket connections, storage access, and cross-tab messaging happen here.
- **Blue** (ISOLATED world) - has `chrome.runtime` access but no page DOM. The content bridge validates every message against the registry before forwarding.
- **Orange** (MAIN world) - runs in the page's JS context with full DOM access. Page CSP applies here, but extension-origin scripts (Scittle) bypass `script-src` restrictions.
- **Grey** (Developer machine) - the nREPL relay and editor, connected via localhost WebSocket through the background worker.

## How Epupp Runs Code in Pages Despite CSP

A natural first question: how does Epupp evaluate arbitrary code in the page context when sites like GitHub and YouTube have strict Content Security Policies? The answer has several layers.

### SCI is a pure interpreter

[Scittle](https://github.com/babashka/scittle) embeds SCI (Small Clojure Interpreter), which walks the ClojureScript AST directly. It never calls `eval()` or `new Function()`. CSP's `script-src` restrictions target those native JS evaluation mechanisms - a pure interpreter sidesteps the problem entirely.

### Extension-origin scripts bypass page CSP

Scittle's JS files are loaded as `<script src="chrome-extension://EXTENSION_ID/vendor/scittle.js">` tags, listed in `web_accessible_resources` in the manifest. The browser treats these as extension-origin code, not page-origin code. Page CSP policies (like GitHub's `script-src github.githubassets.com`) apply to the page's own origin - they don't govern what extension-origin scripts can do.

This means that even `js/eval` works from within Scittle on strict-CSP sites - not because Epupp does anything special, but because the browser considers the calling script's origin, not the page's CSP. We don't rely on this (SCI doesn't need `eval()`), but it's good to understand why it happens.

### Userscript code uses non-executable script types

Userscript ClojureScript source is injected as `<script type="application/x-scittle">` inline tags. The browser ignores unknown script types entirely - no CSP inline-script violation. SCI reads those tags' `textContent` and interprets them.

### WebSocket connections relay through the background

CSP can block WebSocket connections to `localhost` from page context. Epupp's architecture avoids this: the background service worker holds the actual WebSocket to the `bb browser-nrepl` relay. Messages relay through the content bridge via `postMessage`. The extension's service worker context is completely outside page CSP.

```
Page (MAIN world) ──postMessage──> Content Bridge (ISOLATED) ──chrome.runtime──> Background Worker
                                                                                        │
                                                                                  WebSocket to
                                                                                  localhost:PORT
```

### Trusted Types

Some sites (YouTube, GitHub) enforce Trusted Types, which block raw string assignment to `.innerHTML`, `.src`, and similar sinks. Epupp creates a passthrough `default` Trusted Types policy before Scittle runs, in both [trigger-scittle.js](../../../extension/trigger-scittle.js) and the injection functions in [bg_inject.cljs](../../../src/bg_inject.cljs). This allows Scittle and Replicant to manipulate the DOM without Trusted Types violations.

### Summary

| CSP restriction | How Epupp handles it |
|-----------------|---------------------|
| `script-src` blocks inline scripts | Userscript code uses `application/x-scittle` type (not executed by browser) |
| `script-src` blocks `eval()` | SCI is a pure interpreter - never calls `eval()`. Extension-origin scripts also bypass page `script-src` |
| CSP blocks WebSocket to localhost | Background worker holds the WebSocket (extension context, outside page CSP) |
| Trusted Types | Passthrough `default` policy created before Scittle runs |

NB: This works on both Chrome and Firefox without browser-specific APIs like the Chrome User Script API. The interpreter approach avoids the CSP problem rather than requiring special browser APIs to bypass it.

## Message Origin Isolation

The extension uses Chrome's built-in isolation between execution contexts:

| Context | Can call `chrome.runtime.sendMessage`? | Examples |
|---------|---------------------------------------|----------|
| Extension pages | Yes | popup.html, panel.html |
| Content scripts (ISOLATED world) | Yes | content_bridge.js |
| Page scripts (MAIN world) | **No** | userscripts, ws_bridge.js |

Page scripts (including userscripts) **cannot** directly send messages to the background worker. They can only communicate via `window.postMessage` to the content bridge, which explicitly whitelists what to forward.

## Content Bridge as Security Boundary

The content bridge ([content_bridge.cljs](../../../src/content_bridge.cljs)) is the sole gateway from page context to background. It uses a declarative message registry that controls which messages are forwarded. Every registered message declares its allowed sources and auth model. Unregistered types are silently dropped.

Key message categories:

| Auth Model | Messages | Purpose |
|------------|----------|---------|
| `:auth/none` | `ws-connect`, `load-manifest`, `check-script-exists`, `get-sponsored-username` | Open access (read-only or low-risk) |
| `:auth/connected` | `ws-send` | Requires active REPL connection |
| `:auth/fs-sync+ws` | `list-scripts`, `get-script`, `save-script`, `rename-script`, `delete-script` | Requires FS REPL Sync enabled for the requesting tab AND active WebSocket connection |
| `:auth/domain-whitelist` | `web-installer-save-script` | Domain-gated save for web installer |
| `:auth/challenge-response` | `sponsor-status` | Background-initiated pre-authorization |

The registry in `content_bridge.cljs` is the authoritative whitelist - see it for the complete, always-current list.

**Domain whitelist for web installer**: The `web-installer-save-script` message only succeeds from whitelisted domains (github.com, gist.github.com, gitlab.com, codeberg.org, localhost, 127.0.0.1). Non-whitelisted domains trigger a copy-paste fallback in the installer UI.

When adding new forwarded message types, consider: "What if any page script could call this?" If the answer involves privilege escalation, don't forward it.

## Host Permissions (Firefox)

Firefox treats `host_permissions` as optional and revocable, unlike Chrome where they are granted at install. Epupp checks host permission before every `chrome.scripting.executeScript` call:

- **`permissions.cljs`** - `check-tab-permission` resolves the tab URL and checks `chrome.permissions.contains`. Internal URLs (`chrome://`, `moz-extension://`, etc.) are assumed permitted.
- **`bg_inject.cljs`** - `execute-in-page`, `execute-in-isolated`, and `inject-content-script` all check permission before proceeding, throwing if missing.
- **`background.cljs`** - `process-navigation!` and `maybe-inject-installer!` check permission early and skip the tab silently if not granted.
- **`popup.cljs`** - checks `<all_urls>` permission on init and shows a warning banner with a "Grant Permission" button when missing.

On Chrome, the permission check always returns `true` (granted at install), so the overhead is negligible.

## Injection Guards

Scripts guard against multiple injections using global window flags:

| Module | Flag | Purpose |
|--------|------|---------|
| `content_bridge.cljs` | `window.__browserJackInContentBridge` | Prevent duplicate content bridge |
| `ws_bridge.cljs` | `window.__browserJackInWSBridge` | Prevent duplicate WS bridge |
