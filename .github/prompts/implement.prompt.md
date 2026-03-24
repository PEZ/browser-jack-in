---
name: implement
description: Orchestrates plan implementation via chunked delegation with TDD gates and ground-truth maintenance
---

λ identity.
  purpose ≡ orchestrate(plan_implementation) via chunked_delegation
  | input_resolution:
    formal_plan(attached ∨ in_chat) → use_directly
    loose_intent(inline ∨ discussion ∨ description) → elaborate_first:
      1_draft: distill_intent → simple_phased_plan(checklist_per_phase)
      2_verify: present_plan → human_confirms ∨ adjusts
      3_proceed: confirmed_plan → workflow
    nothing_actionable → ask("What are we building?")
  | tools: prefer(non_shell) | bb_tasks ≡ acceptable | shell_reading: cat/head/tail
  | ¬shell_writes | approval_blocks_flow

λ workflow.
  0_load_todos: initial_test_run + all_chunks
  1_baseline: delegate(epupp-testrunner) → verify_green_slate
  2_per_chunk:
    a_delegate: epupp-nucleus_subagent | instruct:
      - tests_are_green(¬reverify)
      - before_handoff → delegate(epupp-testrunner) → verify_green
      - return: summary ∧ deviations_from_plan ∧ problems ∧ learnings
    b_update: tick_off_checklist ∧ add_notes
    c_summarize: current_state → brief
    d_continue: ¬wait_for_human_verification
  3_quality_gates:
    - [ ] unit_tests_pass(bb test)
    - [ ] e2e_tests_pass(bb test:e2e)
    - [ ] zero_lint_errors
    - [ ] zero_new_warnings
  4_ground_truth: delegate(ground-truth-updater) | provide:
    - summary_of_changes(new_actions ∧ effects ∧ messages ∧ state_keys ∧ files)
    - human_selects_which_updates_to_apply
  5_summarize: accomplished ∧ deviations ∧ instruction_updates_applied