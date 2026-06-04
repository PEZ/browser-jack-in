---
name: babashka
description: 'Babashka scripting conventions for the Epupp project. Use when: writing or modifying bb.edn tasks, scripts in scripts/, build or test automation, choosing between bb and npx/npm/shell/Python, using babashka.fs, babashka.process, or babashka.http-client, or running any bb command in this repo. Prefer bb tasks over direct npm/npx; keep bb.edn minimal and put code in scripts/tasks.clj. IMPORTANT: Also load when planning automation or build pipeline changes.'
---

# Using Babashka in This Project

This project uses [Babashka](https://github.com/babashka/babashka), a fast-starting Clojure interpreter for scripting and automation.

## bb Tasks

Don't put anything but the minimum code snippets in bb.edn. Use scripts/tasks.clj for code and call them from bb tasks.

## Babashka utilities

Prefer Babashka built-in utilities over Python, shell scripts, or other external tools when functionality overlaps. The project uses Babashka extensively and has dependencies loaded.

Common replacements:
- HTTP server: Use `bb test:server` or `babashka.http-server` instead of `python -m http.server`
- File operations: Use `babashka.fs` instead of shell commands (cp, mv, rm, find, etc.)
- Process execution: Use `babashka.process` instead of raw shell scripts
- HTTP requests: Use `babashka.http-client` instead of curl or wget

Check bb.edn dependencies and existing tasks before reaching for external tools.
