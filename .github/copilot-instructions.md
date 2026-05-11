# Epupp - AI Coding Agent Instructions

λ engage(nucleus).
[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h signal/noise order/entropy truth/provability self/other] | OODA
Human ⊗ AI ⊗ REPL

## Identity

λ epupp.
  browser_extension ≡ bridge(editor, browser_page) via scittle(SCI_in_browser)
  | editor/AI → nREPL → bb_relay(12345/12346) → background_worker → content_bridge → ws_bridge → scittle_REPL → DOM
  | language: squint ≡ ClojureScript_variant | compiles_to_ESM_JS
  | logic_source: src/*.cljs
  | ui_source: extension/*.css ∧ extension/*.html ∧ extension/manifest.json ∧ extension/trigger-scittle.js ∧ extension/disable-scittle-auto-eval.js
  | generated: extension/*.mjs ∧ build/*
  | ¬edit(.mjs) | ¬edit(build/*) | treat_generated_as_derived
  | read(.mjs) ∧ read(build/*) only_when debugging_compilation ∨ packaging

λ build_pipeline.
  src/*.cljs → squint_compiler → extension/*.mjs(ESM) → esbuild → build/*.js(IIFE)
  | config: squint.edn | bb_squint-compile ≡ compile_check | bb_watch ≡ continuous

λ build_outputs.
  build/*.js(bundle_entries) derived_from extension/*.mjs
  | build/*.css ∧ build/*.html copied_from extension/*.css ∧ extension/*.html
  | build/trigger-scittle.js ∧ build/disable-scittle-auto-eval.js copied_from extension/*
  | edit_owning_source_in(src/* ∨ extension/*) | ¬edit(build/*)

λ investigation_entrypoints.
  | behavior ∧ state ∧ messaging → start_in src/*.cljs
  | popup ∧ panel ∧ styling ∧ manifest ∧ static_assets → start_in extension/*(except_.mjs)
  | build_pipeline ∨ packaging_issue → inspect(squint.edn ∧ scripts/tasks.clj ∧ dev/docs/architecture/build-pipeline.md)
  | ¬start_in build/* ∧ ¬start_in extension/*.mjs

## Principles

λ epistemology.
  assumptions ≡ enemy | benchmark > estimate | measure > guess
  | verify_before_stating_fix_locations | mark("needs investigation") when_uncertain
  | failure_to_read_docs ≡ #1_cause_of_mistakes

λ ground_truth.
  complex_tasks: research → clarify → confirm → plan → execute
  | simple_tasks: execute_immediately
  | scale: validate_small_first → scale_only_the_parameter

λ data_oriented.
  what_would_rich_hickey_do | data > objects | fn > class
  | immutable_core > mutable_shell | transform(data) > mutate(state)
  | REPL_first: test_functions ∧ explore_data ∧ validate_assumptions_before_coding

λ clojure.
  definition_order_matters | ¬forward_declares | almost_always ≡ poor_structure
  | plan_test_strategy: unit(structural ∧ contracts ∧ invariants) ∧ e2e(integration ∧ real_flows)
  | unit ∧ e2e ≡ complementary_tools | ¬sequential_phases

λ uniflow_sap.
  event_loop ≡ SINGLE_ACCESS_POINT for !state
  | actions: receive(state) as pure_data → return({:uf/db :uf/fxs :uf/dxs}) | ¬atom_access
  | effects: receive(params_from_action) | ¬read(@!state) | ¬transitive_atom_access
  | message_handlers ∧ event_listeners → dispatch_actions | ¬read(@!state)
  | ¬swap! ∧ ¬reset! outside_event_loop
  | guard/utility_fns ≡ pure | receive_data_as_params
  | ref: dev/docs/architecture/uniflow.md

λ api_stability.
  user_facing_API ≡ commitment: manifest_keys ∧ epupp.fs ∧ REPL_behaviors
  | preserve_existing | break_only_when(cost_compat > cost_users)
  | before_breaking: explore_alternatives → assess_impact → consider_deprecation
  | prefer_clean_solutions | ¬contorted_shims | ¬special_case_branching

λ learning.
  record_significant_learnings → update_instructions | self_improving_loop

## Style

λ style.
  ¬emojis | ¬em_dashes | use_hyphens ∨ colons
  | ¬sed | ¬write_capable_shell_commands | use_edit_tools
  | tell_Clojure_editor_subagent: ¬shell_approach
  | shell_reading: prefer(cat/head/tail) | ¬sed(stuck_in_approval)
  | agent/prompt_files: ¬fenced | start_with(---) | read_file_adds_display_fences_only

## Commands

λ bb_first.
  ∀commands: prefer(bb <task>) > npx/npm | bb.edn encodes_project_decisions
  | bb_squint-compile > npx_squint_compile
  | check(bb tasks) before_direct_tool_invocation
  | ¬redirect_bb_output(> 2>&1 | tee) → triggers_approval_dialogs

λ commands.
  bb_test              → unit_tests(fast, always_run_after_changes)
  bb_test:e2e          → e2e_in_docker | output_is_brief(~20_lines) | run_without_pipes
  | e2e_terminal_output ≡ short_and_important | read_in_full | ¬tail | ¬head | ¬grep
  | e2e_output_file: .tmp/e2e-output.txt | read_with(read_file)
  bb_squint-compile    → compilation_check
  bb_build:dev         → dev_build(handoff_to_human)
  | e2e_options: bb_test:e2e(parallel) | bb_test:e2e_--_--grep_"popup"(filter)
  | separator: -- between_task_options_and_playwright_args
  | after_changes: wait_for_user_confirmation_before_committing

λ bb_e2e.
  use(bb test:e2e) exclusively | ¬direct(docker build) | ¬direct(docker run)
  | docker_caching ≡ NOT_a_problem | COPY_._. invalidates_on_source_change
  | test_failure_after_code_change → bug_in_your_code | ¬blame_docker | ¬--no-cache

λ babashka_utilities.
  prefer_babashka_builtins > python ∧ shell ∧ external_tools
  | http_server: bb_test:server ∨ babashka.http-server | ¬python_-m_http.server
  | file_ops: babashka.fs | ¬shell(cp mv rm find)
  | process: babashka.process | ¬raw_shell_scripts
  | http_client: babashka.http-client | ¬curl | ¬wget
  | shell_bang(!): history_expansion_char | avoid_in_shell_commands

## REPL and Watchers

λ available_repls.
  bb             → babashka_REPL | scripting ∧ automation
  squint         → squint_REPL | test_pure_functions_in_Node.js
  scittle-dev-repl → scittle_Dev_REPL | test_scittle_code_in_browser_like_env
  | develop_solutions_incrementally_in_appropriate_REPL

λ dev_workflow.
  before_work:
    1_verify_watchers: get_task_output with task_IDs | MANDATORY
      - "shell: Squint Watch" → compilation
      - "shell: Unit Test Watch" → tests
      - "shell: Scittle Dev REPL" → relay
    2_check_problem_report: review_existing_lint_errors
    3_verify_REPLs: clojure_list_sessions → bb ∧ squint ∧ scittle-dev-repl
  | watcher_not_found → STOP | tell_user:
    "I cannot find the watcher task outputs. Please restart the default build task
    (Cmd/Ctrl+Shift+B) to restore the watchers, then ask me to continue."
  | ¬proceed_without_watcher_feedback
  while_working:
    squint_session → pure_functions | scittle-dev-repl → scittle_code
    | incremental_development_in_REPL
  after_editing:
    1_check_watcher_output: compilation ∧ tests
    2_check_problem_report: fix_new_lint_errors
    3_address_issues_before_proceeding

λ quality_gates.
  ∀completed_work:
    - [ ] no_new_lint_errors
    - [ ] unit_tests_pass
    - [ ] e2e_tests_pass
    - [ ] zero_warnings_baseline_maintained
    - [ ] docs_updated(when_API_or_behavior_changes)

## Documentation Index

λ docs_critical.
  auto_loaded | applies_to_all_code_changes:
  | squint.instructions.md              → squint_gotchas(keywords ∧ mutability)
  | dev/docs/architecture.md            → system_architecture
  | dev/docs/testing.md                 → testing_philosophy
  | dev/docs/testing-e2e.md             → e2e_patterns(polling ∧ assertions)

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
  | ¬edit(synced_copies) | ¬resync(synced_copies) | treat_as_generated
  | ∀doc_updates: edit_here(epupp/) | human_runs(bb_docs-sync)_when_ready

## Delegation

λ subagents.
  commit        → summary_of_task(bigger_picture) | expert_git_agent
  research      → context ∧ what_to_know ∧ report_structure
  edit          → Clojure_editor | files ∧ linenumbers ∧ code ∧ instructions
  epupp-elaborator → user_prompt ∧ file_context ∧ session_context → refined_prompt
  | delegation ≡ intelligent | protect_context_window ∧ ensure_quality

## Pitfalls

λ pitfalls.
  squint ≠ ClojureScript: see_squint.instructions.md | keywords_are_strings ∧ mutable_data
  | scittle_update → run(bb bundle-scittle) after_version_change
  | CSP_strict: test_on(GitHub ∧ YouTube) → verify_scittle_works
  | ws_readyState: set(3/CLOSED) in_ws-close_handler → ¬reconnection_loops
  | firefox_CSP: content_security_policy must_allow(ws://localhost:*)
  | prefer_tools: Problem_Report > compiler | watchers > manual_commands | bb > npx | babashka > shell
