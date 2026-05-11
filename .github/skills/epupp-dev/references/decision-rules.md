# Decision Rules

When the system chooses path A vs B: injection, connection, port resolution, auto-connect, FS sync, userscripts, manifest parsing, script storage, and eval routing.

## When to Inject

```
λ when_to_inject.
  user_clicks_connect_in_popup → inject_scittle ∧ inject_bridges
  | auto_connect_level("all-pages") → inject_on_every_navigation
  | auto_connect_level("current") → inject_on_current_tab_navigation
  | auto_connect_level("off") → inject_only_on_explicit_connect
  | ¬inject_if_already_injected | idempotent_injection
```

## Injection Path Selection

```
λ injection_path_selection.
  popup_connect → on_demand_injection(executeScript)
  | auto_connect → webNavigation_listener → content_script_registration
  | userscript_document_start → registerContentScripts → runs_before_DOM
  | userscript_document_end → registerContentScripts → runs_after_DOM_parse
  | userscript_document_idle → registerContentScripts → runs_after_load(default)
```

## Connection Lifecycle Decisions

```
λ connection_lifecycle_decisions.
  connect_button_pressed:
    1_ensure_scittle_loaded: check_scittle_fn → hasScittle ∧ hasWsBridge
    2_if_missing: inject_vendor/scittle.js → inject_bridges
    3_create_ws: background_opens_WebSocket(ws://localhost:PORT/_nrepl)
    4_register: tab_id → {:ws/socket :ws/port :ws/tab-title :ws/tab-url}
    5_notify_popup: broadcast_connections_changed!
  | disconnect_button_pressed:
    1_close_ws: WebSocket.close()
    2_unregister: remove_from_:ws/connections
    3_update_icon: set_disconnected
    4_notify_popup: broadcast_connections_changed!
```

## Port Resolution

```
λ port_resolution.
  per_hostname_ports: storage["ports_HOSTNAME"] → {:nreplPort :wsPort}
  | global_defaults: storage["defaultNreplPort"] ∧ storage["defaultWsPort"]
  | resolution_order: hostname_override > global_default > hardcoded(12345/12346)
  | source_tracking: {:nrepl :default|:override :ws :default|:override}
  | popup_displays_effective_ports | user_can_override_per_hostname
```

## Auto-Connect Decisions

```
λ auto_connect_decisions.
  level("off") → ¬auto_inject | ¬auto_connect
  level("all-pages") → every_navigation → check_connected_tabs_history → auto_connect
  level("current") → current_tab_navigation → check_connected_tabs_history → auto_connect
  | connected_tabs_history ≡ {tab-id port} | tracks_previously_connected_tabs
  | auto_reconnect_uses_stored_port | ¬prompt_user
```

## FS Sync Decisions

```
λ fs_sync_decisions.
  single_tab_constraint: only_one_tab_can_have_fs_sync_enabled
  | enable: if(:fs/sync-tab-id nil) → set_tab_id | if(other_tab) → reject
  | disable: clear_:fs/sync-tab-id
  | guard_pattern: every_fs_write_checks_:fs/sync-tab-id ≡ requesting_tab_id
  | read_ops_allowed_without_sync | write_ops_require_sync_enabled
```

## Userscript Matching

```
λ userscript_matching.
  script_has :script/match [patterns] → minimatch(page_url, pattern)
  | enabled_check: :script/enabled true
  | builtin_scripts: :script/always-enabled? true → ¬user_toggleable
  | document_start_scripts → injected_via_registerContentScripts(run_at: document_start)
  | document_idle_scripts → injected_via_registerContentScripts(run_at: document_idle)
```

## Manifest Parsing

```
λ manifest_parsing.
  first_form_in_code ≡ EDN_map | parsed_by_manifest_parser
  | required_key: :epupp/script-name → string
  | optional_keys: :epupp/auto-run-match :epupp/description :epupp/inject :epupp/run-at :epupp/library?
  | :epupp/inject URL_schemes: scittle:// ∧ epupp:// ∧ https://raw.githubusercontent.com/owner/repo/sha/path ∧ https://gist.githubusercontent.com/owner/id/raw/sha/file
  | unknown_keys → :manifest/unknown-keys warning | ¬error
  | name_normalization: trim ∧ lowercase_extension | validate_format
```

## Script Storage Decisions

```
λ script_storage_decisions.
  save_new: ¬existing → generate_id → store
  | save_existing: found_by_name → update_in_place | preserve(:script/created)
  | overwrite_guard: existing ∧ ¬:fs/force? → reject("already exists")
  | builtin_guard: :script/builtin? → reject("cannot modify built-in")
  | panel_save: always_overwrites_by_id | ¬overwrite_guard
```

## Eval Routing

```
λ eval_routing.
  panel_eval: code → ensure_scittle → inject_libs → devtools_inspectedWindow.eval
  | repl_eval: nREPL_client → bb_relay → background_ws → content_bridge → ws_bridge → scittle
  | panel_uses_devtools_API | repl_uses_websocket_relay
  | both_execute_in_page_scittle_context | same_runtime
```
