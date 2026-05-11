# Temporal Sequences

Lifecycle and ordering rules for Epupp extension activation, connection, evaluation, injection, and FS sync.

## Extension Activation

```
λ extension_activation.
  1_service_worker_starts: background.cljs → init!
  2_initialize_storage: load_scripts ∧ load_settings ∧ load_ext-dep-cache ∧ schema_migration
  3_register_message_listeners: chrome.runtime.onMessage
  4_register_navigation_listeners: chrome.webNavigation.onCompleted(if_auto_connect)
  5_register_tab_listeners: chrome.tabs.onRemoved → cleanup_connections
  6_register_storage_listeners: onChanged(scripts → sync_registrations, extDepCache → re-resolve)
  7_set_initial_icon_state: all_tabs → :disconnected
  | then: awaiting_popup_or_auto_connect | reactor_pattern_for_messages
```

## Popup Lifecycle

```
λ popup_lifecycle.
  1_popup_opens: popup.html → popup.js → init!
  2_load_state: query_active_tab → load_ports → load_scripts → load_settings
  3_render_ui: reagami/render → hiccup_components → DOM
  4_check_connection: send("check-status") → response → update_icon_state
  5_user_interacts: connect ∨ disconnect ∨ toggle_script ∨ change_settings
  6_popup_closes: state_lost | ¬persistent | re_init_on_next_open
```

## Connection Sequence

```
λ connection_sequence.
  1_user_clicks_connect: popup → dispatch!(:popup/ax.connect port)
  2_effect_sends_message: {:type "connect-tab" :tabId tab-id :wsPort port}
  3_background_receives: handle_connect_tab_message
  4_ensure_scittle: check_if_loaded → inject_if_missing
  5_ensure_bridges: inject_content_bridge.js(ISOLATED) ∧ ws_bridge.js(MAIN)
  6_create_websocket: background → new WebSocket("ws://localhost:PORT/_nrepl")
  7_register_connection: :ws/ax.register [tab-id connection-info]
  8_update_icon: set_connected_state
  9_broadcast: notify_popup_and_panel → connections_changed
  | skip(4) → scittle_not_available → eval_impossible
  | skip(5) → messages_cant_relay → ws_bridge_missing
  | skip(6) → no_nrepl_connection → eval_hangs
```

## Eval via REPL Sequence

```
λ eval_via_repl_sequence.
  1_editor_sends_nrepl_eval: code → nREPL_client
  2_bb_relay_receives: browser-nrepl → WebSocket_relay
  3_background_ws_receives: onmessage → nREPL_data
  4_background_sends_to_bridge: chrome.tabs.sendMessage(tab-id, data)
  5_content_bridge_receives: chrome.runtime.onMessage → validate_source
  6_bridge_posts_to_page: window.postMessage({source: "epupp-bridge", type: "ws-message", data})
  7_ws_bridge_receives: window.addEventListener("message") → route_to_scittle
  8_scittle_evaluates: SCI_eval(code) → result
  9_result_returns: ws_bridge → postMessage → content_bridge → chrome.runtime → background_ws
  10_relay_returns: background_ws → bb_relay → nREPL → editor
  | skip(5) → auth_rejected | message_dropped_silently
  | skip(8) → scittle_error → error_response_relayed_back
```

## Eval via Panel Sequence

```
λ eval_via_panel_sequence.
  1_user_types_code: panel_editor → dispatch!(:editor/ax.set-code code)
  2_manifest_parsed: extract_inject_libs ∧ script_name ∧ hints
  3_user_clicks_eval: dispatch!(:editor/ax.eval)
  4_ensure_scittle: send("ensure-scittle") → background_injects_if_needed
  5_inject_libs: send("inject-libs") → background_injects_scittle_plugins
  6_eval_in_page: chrome.devtools.inspectedWindow.eval(wrapper_code)
  7_scittle_evaluates: SCI_eval(code) → result
  8_result_displayed: dispatch!(:editor/ax.handle-eval-result result)
  9_ui_updated: :panel/results appended | :panel/evaluating? false
```

## Userscript Injection Sequence

```
λ userscript_injection_sequence.
  1_navigation_detected: chrome.webNavigation.onCompleted ∨ explicit_connect
  2_find_matching_scripts: filter(scripts, url_matches ∧ enabled)
  3_group_by_run_at: document_start ∧ document_end ∧ document_idle
  4_resolve_dependencies: dep_resolver → topological_sort(epupp:// ∧ HTTPS_ext_dep_refs) → inject_plan
  | https://_ext_deps: resolved_from_ext-dep-cache(storage) | cache_miss → :ext-dep/cache-miss_error
  | step_types: :vendor-file ∧ :library-script ∧ :ext-dep-script ∧ :root-script
  5_inject_required_libs: execute_plan! → inject_scittle_plugins ∧ library_scripts ∧ ext-dep_scripts
  6_inject_scripts: execute_in_page(script_code) | per_script | ordered_by_run_at
  | userscript_loader.cljs ≡ Squint_compiled_content_script | reads_storage(scripts ∧ extDepCache) ∧ resolves_deps
  | resolution_errors → "loader-resolution-errors" → background → broadcast
  | page_context_execution → full_DOM_access
```

## FS Sync Sequence

```
λ fs_sync_sequence.
  1_page_sends_fs_request: epupp.fs/ls ∨ epupp.fs/save! ∨ epupp.fs/mv! ∨ epupp.fs/rm!
  2_ws_bridge_relays: postMessage → content_bridge → chrome.runtime → background
  3_guard_checks_permission: :fs/ax.guard-* → verify(:fs/sync-tab-id ≡ requesting_tab)
  4_execute_operation: read_storage ∨ modify_storage
  5_send_response: {:success boolean :error string :data any}
  6_response_relays_back: background → content_bridge → ws_bridge → page → REPL
  | write_ops_require_sync_enabled | read_ops_always_allowed
  | single_tab_constraint_prevents_concurrent_writes
```

## Storage Persistence

```
λ storage_persistence.
  1_action_modifies_state: :uf/db updated
  2_effect_triggered: :storage/fx.persist!
  3_chrome_storage_written: chrome.storage.local.set(data)
  4_storage_mirror_updated: storage/!db reflects_new_state
  | fire_and_forget: ¬await_on_chrome.storage.set
  | verify_persistence: reload_popup → check_UI_state
```

## Icon State Management

```
λ icon_state_management.
  per_tab_icon_state: :icon/states {tab-id :connected|:disconnected}
  | connected → green_icon | disconnected → grey_icon
  | set_on_connect → :connected | set_on_disconnect → :disconnected
  | tab_removed → cleanup_icon_state
  | popup_queries_on_open → check-status message → response
```
