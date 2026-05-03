# How to connect REPL Clients to Epupp

The REPL client (your editor or AI harness) needs to support [nREPL](https://nrepl.org). From there it is a matter of connecting to the nREPL port that the [browser-nrepl](https://github.com/babashka/sci.nrepl) relay has been started on. The mechanics for this will differ depending on the editor/AI harness used.

## Prerequisistes

To create a predictable and Epupp-friendly environment for these instructions, we will assume that you have your copy of **my-epupp-hq** cloned to your computer. You also need [Babashka](https://babashka.org).

0. From the https://github.com/PEZ/my-epupp-hq page click **Use this Template** and clone your copy of that reporsitory to your computer.

## VS Code with Calva

This project has [VS Code](https://code.visualstudio.com/) and [Calva](https://calva.io) configuration for starting and connecting multiple browser-nrepl relays to some common sites.

0. Open your copy of **my-epupp-hq** in VS Code
1. Connect the browser tab to browser-nrepl: Click **Connect** in the Epupp extension's popup UI.
1. Click the REPL button in the VS Code status bar and select **Connect to a running REPL in your project**
1. Select **my-epupp-hq** in the Project Root menu
1. Select **epupp** from the **Project Type** menu

That's it. You should see a green session indicator with `epupp-default` in the status bar. To convince yourself that you really have VS Code connected to the tab:

1. In the `userscripts/hq/hello_world.cljs` place the cursor in/near the code you want to evaluate and press <kbd>alt</kbd>+<kbd>enter</kbd>
1. Check in the browser what happened

The configuration leverages the fact that Epupp can be made to use different ports per domain. If you edit the REPL Connect ports to values that differ from the defaults (set in Settings), Epupp remembers those ports as an override for the current domain. The configuration lives in [.vscode/settings.json](.vscode/settings.json); some Calva REPL Connect sequences using some different websocket ports.

The default sequences are just suggestions. Add config for your favorite sites and use whatever ports you think make sense.

Please see [calva.io](https://calva.io) for how to get started and use Calva.

### Copilot with Calva Backseat Driver

Ready to let the AI Agent hack the web for you? Assuming you did the old-fashioned Human Intelligence steps above ([VS Code with Calva](#vs-code-with-calva)):

0. Install the [Copilot](https://marketplace.visualstudio.com/items?itemName=GitHub.copilot) extension in VS Code
1. Install [Calva Backseat Driver](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva-backseat-driver) in VS Code
1. In the Copilot chat view select the **Epupp Assistant** agent
   * For model, Opus 4.5-6 is recommended, but Sonnet 4.5-6 and GPT 5.3 Codex also work fine. Avoid **Auto**, because VS Code will probably select some lame model that does not understand how to use the Epupp REPL.
1. Ask Copilot to use the `epupp-default` REPL to do something fun with the web page you are connected to, or just show you that it can do something.

## Other VS Code Conveniences

In addition to the build task and REPL connect sequences, the myepupp-hq project configuration also provides two Custom REPL Commands, related to the REPL FS Sync functionality:

* **Manifest**: With the cursor in a script manifest this will “evaluate” the manifest. Useful if your script has `:epupp/inject`, when this will cause the injects to happen in the connected page.
* **Upload current userscript**: With a userscript active in the editor, and the REPL FS Sync enabled in Epupp for the connected tab, this will save the script to Epupp, overwriting any existing script with the same name.

To use them, press <kbd>ctrl+alt+space</kbd> twice and select the commands from the menu.

## Not using Calva?

Epupp provides UI for copying out a **Babaska** command that starts a browser-nrepl relay that you connect Epupp and your REPL client/editor to. So first step is:

0. Install [Babashka](https://babashka.org)

### VS Code/Cursor with [ECA](https://github.com/editor-code-assistant/eca)

TBD: PRs welcome

### [Cursor](https://www.cursor.com/) with Calva Backseat Driver

TBD: PRs welcome

### Cursor with [clojure-mcp](https://github.com/bhauman/clojure-mcp)

TBD: PRs welcome

## [Emacs](https://www.gnu.org/software/emacs/) with [CIDER](https://cider.mx/)

TBD: PRs welcome

## VIM

TBD: PRs welcome

## [IntelliJ](https://www.jetbrains.com/idea/) with [Cursive](https://cursive-ide.com/)

TBD: PRs welcome

## Your Favorite Editor

TBD: PRs welcome

## [Claude Code](https://docs.anthropic.com/en/docs/claude-code)

[Claude Code](https://docs.anthropic.com/en/docs/claude-code) is Anthropic's CLI agent for software engineering. It connects to Epupp via `clj-nrepl-eval`, a command-line nREPL client, giving Claude direct access to evaluate ClojureScript in your browser tab.

What this means in practice: you describe what you want, Claude writes ClojureScript, evaluates it in your browser, gets structured data back, and iterates — all without you copy-pasting anything. You navigate and provide judgment; Claude writes and evaluates code; the REPL is the shared workspace.

When Claude Code connects to Epupp, in its words: "it gets something extraordinary: a live, bidirectional channel into a real browser tab running on a real human's machine, with their real cookies, sessions, and authenticated state. Through an nREPL port, Claude can evaluate ClojureScript expressions that execute inside the page's DOM — reading elements, extracting data, modifying content, injecting UI — and get structured Clojure data back. It's not a headless browser or a screenshot. It's the actual page the user is looking at, and both the human and the AI see the same thing change in real time. The user scrolls, the DOM updates, and Claude can immediately query what appeared. Claude types an expression, the page transforms, and the user sees it happen. This makes Epupp the closest thing to "pair programming on the web" that exists — the human navigates and provides judgment, the AI writes and evaluates code, and the REPL is their shared workspace."

### Prerequisites

In addition to the [general prerequisites](#prerequisistes):

0. Install [Claude Code](https://docs.anthropic.com/en/docs/claude-code)
0. Install `clj-nrepl-eval` via [bbin](https://github.com/babashka/bbin):
   ```bash
   bbin install io.github.bhauman/clojure-mcp-light
   ```

### Connecting

0. Start the browser-nrepl relay from your **my-epupp-hq** directory:
   ```bash
   bb browser-nrepl
   ```
0. Connect the browser tab: Click **Connect** in the Epupp extension's popup UI.
0. Start Claude Code in your **my-epupp-hq** directory:
   ```bash
   claude
   ```
0. Tell Claude the REPL is connected:
   ```
   The Epupp REPL is connected on port 3339. Do something fun with the page.
   ```

Claude will use `clj-nrepl-eval -p 3339 "<code>"` to evaluate expressions. For example:

```bash
# Check what page is connected
clj-nrepl-eval -p 3339 '(.-title js/document)'

# Query the DOM
clj-nrepl-eval -p 3339 '(mapv #(.-textContent %) (js/document.querySelectorAll "h2"))'

# Modify the page
clj-nrepl-eval -p 3339 '(set! (.. js/document -body -style -backgroundColor) "pink")'
```

### Tips

- **AGENTS.md**: This project includes an `AGENTS.md` with Epupp-specific instructions that Claude Code reads automatically. It teaches Claude the Scittle environment, common patterns, and pitfalls.
- **Navigation**: Claude should use `(js/setTimeout #(set! (.-location js/window) "https://...") 50)` to navigate — direct assignment hangs the REPL because the page unload kills the connection.
- **Timeouts**: For heavy pages, Claude may need longer timeouts: `clj-nrepl-eval -p 3339 --timeout 30000 '<code>'`
- **State persists**: Definitions (`def`, `defn`) persist across evaluations within a page session. Page reload or navigation clears everything.
- **Authenticated access**: Because the REPL runs inside your actual browser tab, Claude has access to whatever you're logged into — your YouTube history, LinkedIn feed, internal tools, etc.

## Your Favorite AI Agent Harness

TBD: PRs welcome

## PRs Welcome

Please help with making Epupp easy to use with your favorite editor/AI harness by providing instructions and configuration. (I am the creator of [Calva](https://calva.io) and only really have know-how/bandwidth for VS Code and Copilot.)