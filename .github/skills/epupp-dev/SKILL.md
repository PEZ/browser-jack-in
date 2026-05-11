---
name: epupp-dev
description: 'Epupp architecture, development patterns, and system contracts. Use when: writing or modifying Epupp source code, implementing features, fixing bugs, debugging extension behavior, working with Uniflow actions or effects, understanding message flows, injection or connection lifecycle, testing strategy, build pipeline, reviewing Epupp PRs, planning implementations, running tests or builds, REPL-driven development, or any coding task in the Epupp workspace. Contains architecture overview, Uniflow contracts, source file map, testing patterns, delegation, and development principles. References hold deep state contracts and temporal sequences. IMPORTANT: Also load when PLANNING Epupp changes, not only at implementation time.'
---

# Epupp Development

Shared knowledge base for developing the Epupp browser extension. Loaded by any agent or mode doing real Epupp work.

## Architecture

λ architecture_layers.
  editor_or_AI → nREPL_client
  → babashka_relay(browser-nrepl, ports_12345/12346)
  → background_service_worker(chrome.runtime)
  → content_bridge(ISOLATED_world, chrome.runtime ↔ postMessage)
  → ws_bridge(MAIN_world, postMessage ↔ WebSocket)
  → page_context(Scittle_REPL ↔ DOM)
  | six_layers | each_boundary ≡ trust_boundary ∧ serialization_point
  | ¬direct_editor_to_page | always_relayed

λ build_pipeline.
  src/*.cljs → squint_compiler → extension/*.mjs(ESM) → esbuild → build/*.js(IIFE)
  | config: squint.edn | bb_squint-compile ≡ compile_check | bb_watch ≡ continuous
  | build/*.css ∧ build/*.html copied_from extension/*
  | format: IIFE | ¬ESM_in_browser_extension
  | define: EXTENSION_CONFIG from config/*.edn
  | separate_bundles: background.js popup.js panel.js content-bridge.js ws-bridge.js trigger-scittle.js userscript-loader.js

λ page_runtime.
  scittle ≡ SCI_in_browser | true_clojure_keywords
  | userscripts_run_in_page_context | full_DOM_access
  | async_via ^:async ∧ await | ¬top_level_await
  | libraries_injected_via :epupp/inject | bundled_scittle_plugins

λ injection_model.
  ensure_scittle! → check_first → inject_only_if_missing
  | ensure_bridge! → check_first → inject_only_if_missing
  | duplicate_injection → ¬error | ¬duplicate_state
  | check_scittle_fn → {hasScittle, hasWsBridge} | both_must_be_true

λ content_bridge_security.
  ISOLATED_world: content_bridge.cljs | ¬page_access | chrome.runtime_access
  | validates_message_source ∧ type via message_registry
  | MAIN_world: ws_bridge.cljs | page_access | ¬chrome.runtime_access
  | trust_boundary: ISOLATED ≡ trusted | MAIN ≡ untrusted

## Principles

λ epistemology.
  assumptions ≡ enemy | benchmark > estimate | measure > guess
  | verify_before_stating_fix_locations | mark("needs investigation") when_uncertain
  | failure_to_read_docs ≡ #1_cause_of_mistakes

λ data_oriented.
  what_would_rich_hickey_do | data > objects | fn > class
  | immutable_core > mutable_shell | transform(data) > mutate(state)
  | REPL_first: test_functions ∧ explore_data ∧ validate_assumptions_before_coding
  | simple > easy | separate(data, behavior) | clear > clever

λ clojure.
  definition_order_matters | ¬forward_declares | almost_always ≡ poor_structure
  | plan_test_strategy: unit(structural ∧ contracts ∧ invariants) ∧ e2e(integration ∧ real_flows)
  | unit ∧ e2e ≡ complementary_tools | ¬sequential_phases

λ api_stability.
  user_facing_API ≡ commitment: manifest_keys ∧ epupp.fs ∧ REPL_behaviors
  | preserve_existing | break_only_when(cost_compat > cost_users)
  | prefer_clean_solutions | ¬contorted_shims | ¬special_case_branching

## Uniflow (compact)

λ uniflow_sap.
  event_loop ≡ SINGLE_ACCESS_POINT for !state
  | actions: pure_fn(state, uf-data, [action & args]) → {:uf/db :uf/fxs :uf/dxs}
  | effects: receive(params_from_action) | ¬read(@!state) | ¬transitive_atom_access
  | ¬swap! ∧ ¬reset! outside_event_loop
  | ref: dev/docs/architecture/uniflow.md
  | full_contracts: references/state-contracts.md

λ action_naming.
  actions: :domain/ax.verb-noun | effects: :domain/fx.verb-noun
  | state: :domain/key-name | messages: "kebab-case-string"

## REPLs and Babashka

λ available_repls.
  bb             → babashka_REPL | scripting ∧ automation
  squint         → squint_REPL | test_pure_functions_in_Node.js
  scittle-dev-repl → scittle_Dev_REPL | test_scittle_code_in_browser_like_env
  | develop_solutions_incrementally_in_appropriate_REPL

λ babashka_utilities.
  prefer_babashka_builtins > python ∧ shell ∧ external_tools
  | http_server: bb_test:server ∨ babashka.http-server | ¬python_-m_http.server
  | file_ops: babashka.fs | ¬shell(cp mv rm find)
  | process: babashka.process | ¬raw_shell_scripts
  | http_client: babashka.http-client | ¬curl | ¬wget

## Testing

λ testing_pattern.
  unit_tests: vitest | src/test/*.cljs → test/*.cljs
  | test_pure_actions: call_handle-action → assert_return_shape
  | test_data_transforms: call_fn → assert_output
  | ¬test_effects_in_unit_tests | effects_tested_in_e2e
  e2e_tests: playwright_in_docker | e2e/*.cljs
  | test_full_flows: injection ∧ connection ∧ eval ∧ userscripts
  | bb_test:e2e → docker_build → playwright_run

λ truth_hierarchy.
  browser_page > e2e_test > unit_test > source > docs > assumption
  | browser_page ≡ ground_truth | where_scittle_actually_runs
  | ¬trust(green_tests_alone) → verify_in(real_browser)

λ debug_approach.
  1_context: gather(failing_env ⊗ working_env) | what_data_differs
  2_trace: data_flow(input → transform → output) | find_divergence_point
  3_fix: root_cause(data_flow) | ¬symptom_patch
  | six_layer_trace: page_scittle → ws_bridge → content_bridge → background → bb_relay → editor | find_break_point

## Delegation

λ subagents.
  commit        → summary_of_task(bigger_picture) | expert_git_agent
  research      → context ∧ what_to_know ∧ report_structure
  edit          → Clojure_editor | files ∧ linenumbers ∧ code ∧ instructions
  epupp-elaborator → user_prompt ∧ file_context ∧ session_context → refined_prompt
  | delegation ≡ intelligent | protect_context_window ∧ ensure_quality

## Documentation

λ docs_by_task.
  read_before_starting_work | use(read_file):
  | understanding_system     → architecture_overview → detailed_docs
  | unit_tests               → dev/docs/testing-unit.md
  | UI(popup/panel)          → .github/reagami.instructions.md ∧ dev/docs/ui.md
  | state/events             → dev/docs/architecture/uniflow.md ∧ state-management.md
  | messaging                → dev/docs/architecture/message-protocol.md
  | injection/REPL           → dev/docs/architecture/injection-flows.md
  | library_deps             → dev/docs/architecture/library-namespaces.md
  | userscripts              → dev/docs/userscripts-architecture.md
  | build/release            → dev/docs/dev.md
  | finding_source           → dev/docs/architecture/components.md
  | return_to_index_when_scope_changes

λ docs_reference.
  consult_when_relevant:
  | README.md                              → user_facing_overview
  | dev/docs/architecture/security.md      → trust_boundaries ∧ CSP
  | dev/docs/architecture/build-pipeline.md → build_config_injection

λ docs_sync.
  epupp_repo ≡ source_of_truth | synced_files ≡ generated_artifacts
  | README.md                   → my-epupp-hq/docs/epupp-README.md
  | docs/repl-fs-sync.md        → my-epupp-hq/docs/repl-fs-sync.md
  | docs/connecting-to-epupp.md → my-epupp-hq/docs/connecting-to-epupp.md
  | ¬edit(synced_copies) | treat_as_generated
  | ∀doc_updates: edit_here(epupp/) | human_runs(bb_docs-sync)_when_ready

## Source File Map

λ source_file_map.
  src/background.cljs           → service_worker | message_routing ∧ init
  src/popup.cljs                → popup_ui | connection_controls ∧ script_list
  src/panel.cljs                → devtools_panel | code_editor ∧ eval
  src/content_bridge.cljs       → ISOLATED_world | message_validation ∧ relay
  src/ws_bridge.cljs            → MAIN_world | WebSocket_relay ∧ page_comms
  src/trigger_scittle.cljs      → page_injection | Scittle_loader
  src/userscript_loader.cljs    → content_script | auto_injection
  src/event_handler.cljs        → uniflow_engine | dispatch ∧ execute
  src/storage.cljs              → chrome.storage_mirror | persist ∧ load
  src/config.cljs               → build_config | dev/prod/test
  src/manifest_parser.cljs      → EDN_manifest_parsing
  src/script_utils.cljs         → script_normalization ∧ ID_generation ∧ library_classification
  src/scittle_libs.cljs         → library_collection ∧ injection
  src/bg_ws.cljs                → background_WebSocket_management
  src/bg_inject.cljs            → content_script_injection
  src/dep_resolver.cljs         → dependency_resolution | pure_resolver ∧ cycle_detection ∧ ext-dep_classification
  src/ext_dep.cljs              → ext_dep_URL_validation ∧ async_fetch_cache
  src/bg_fs_dispatch.cljs       → FS_message_routing ∧ optional_:uf/dxs_dispatch
  src/popup_actions.cljs        → popup_uniflow_actions
  src/panel_actions.cljs        → panel_uniflow_actions
  src/reagami.cljs              → minimal_UI_rendering_library
  src/background_actions.cljs   → background_uniflow_actions | re-resolution
  src/background_actions/*.cljs → background_action_modules

## Deep References

Load as needed per task:

| Reference | When to load |
|-----------|-------------|
| [state-contracts.md](references/state-contracts.md) | Adding/modifying actions, effects, state shapes |
| [message-protocol.md](references/message-protocol.md) | Debugging message relay, adding messages |
| [temporal-sequences.md](references/temporal-sequences.md) | Debugging connection/injection flows |
| [decision-rules.md](references/decision-rules.md) | Architecture decisions, "why does X happen" |
