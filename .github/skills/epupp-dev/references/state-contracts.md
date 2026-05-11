# State Contracts

Uniflow contracts and state shapes for all Epupp contexts.

## Uniflow Action Contract

```
λ uniflow_action_contract.
  handle-action(state, uf-data, [action-keyword & args])
  | input: state ≡ immutable_snapshot(current_app_state)
  |        uf-data ≡ {:system/now epoch_ms ...enrichment}
  |        action ≡ keyword(:domain/ax.verb)
  | return: {:uf/db new_state                            ; optional: updated state
  |          :uf/fxs [[effect-keyword & args] ...]       ; optional: side effects
  |          :uf/dxs [[action-keyword & args] ...]}      ; optional: deferred actions
  | constraints:
    - pure_function | ¬side_effects | ¬IO | ¬atom_access
    - :uf/db omitted → state_unchanged
    - :uf/fxs executed_in_order | sequential
    - :uf/dxs dispatched_after_effects_complete
    - :uf/await sentinel → async_effect → :uf/prev-result substituted_in_subsequent
```

## Uniflow Effect Contract

```
λ uniflow_effect_contract.
  execute-effects!(state, ex-handler, [[effect-keyword & args] ...])
  | effects_receive_args_from_action | ¬receive_state | ¬deref_!state
  | effects_perform_IO: chrome.storage ∧ chrome.runtime ∧ WebSocket ∧ DOM
  | :uf/prev-result → result_of_previous_:uf/await_effect → substituted_into_next
  | helpers_called_by_effects → same_constraint: ¬deref_!state
```

## Uniflow Dispatch Contract

```
λ uniflow_dispatch_contract.
  dispatch!(actions)
  | actions ≡ [[action-keyword & args] ...]
  | sequential_processing: action₁ → effects₁ → deferred₁ → action₂ → ...
  | event_enrichment: {:system/now (.now js/Date)} merged_into_uf-data
  | gather_then_decide: action_returns_:uf/fxs(gather) ∧ :uf/dxs(decide_with_:uf/prev-result)
```

## Uniflow List Watcher Contract

```
λ uniflow_list_watcher_contract.
  :uf/list-watchers {:key {:id-fn fn :shadow-path keyword :on-change action-keyword}}
  | watches_list_membership_and_content_changes
  | shadow_items: {:item original :ui/entering? boolean :ui/leaving? boolean}
  | fires_on-change_action_when_list_differs_from_shadow
  | enables_enter/leave_animations_in_UI
```

## Storage Contract

```
λ storage_contract.
  chrome.storage.local ≡ persistent_store
  | keys: "scripts" "defaultNreplPort" "defaultWsPort"
  |        "ports_HOSTNAME" "panelState:HOSTNAME"
  |        "schemaVersion" "sponsorStatus" "sponsorCheckedAt"
  |        "grantedOrigins" "autoConnectLevel" "autoReconnectRepl"
  |        "extDepCache"
  | storage/!db ≡ in_memory_mirror | loaded_on_init | updated_on_persist
  | mirror_includes: :storage/ext-dep-cache {} | synced_via_onChanged_listener
  | schema_migration: version_checked_on_init → migrate_if_needed
```

## Storage Mirror Pattern

```
λ storage_mirror_pattern.
  chrome.storage.local ≡ persistent_source_of_truth
  | storage/!db ≡ in_memory_mirror | fast_reads | ¬cross_origin
  | load_on_init: storage → !db | one_time_sync
  | persist_on_write: action → :storage/fx.persist! → chrome.storage.set ∧ !db_update
  | ¬direct_chrome.storage_reads_after_init | use_!db_mirror
```

## Script Data Contract

```
λ script_data_contract.
  script ≡ {
    :script/id string                         ; UUID or "epupp-builtin-*"
    :script/name string                       ; normalized filename
    :script/code string                       ; full source with manifest
    :script/match [string]                    ; URL patterns or []
    :script/enabled boolean
    :script/created iso-string
    :script/modified iso-string
    :script/description string                ; optional
    :script/inject [string]                   ; "scittle://..." "epupp://..." "https://raw.githubusercontent.com/..." "https://gist.githubusercontent.com/..."
    :script/run-at string                     ; "document-start"|"document-end"|"document-idle"
    :script/builtin? boolean
    :script/always-enabled? boolean
    :script/special? boolean
    :script/library? boolean                  ; optional, from :epupp/library? manifest key
    :script/source keyword                    ; :source/repl :source/panel :source/web
  }
  | :script/id → immutable_after_creation
  | :script/name → unique_across_all_scripts
  | builtin_prefix: "epupp-builtin-*" → ¬modifiable ¬deletable
```

## Connection State Contract

```
λ connection_state_contract.
  :ws/connections {tab-id {:ws/socket WebSocket
                           :ws/port number
                           :ws/tab-title string
                           :ws/tab-favicon string
                           :ws/tab-url string}}
  | one_socket_per_tab | one_tab_per_port
  | connecting_tab_to_occupied_port → evicts_previous_tab
  | tab_closed → auto_cleanup_connection
  | :icon/states {tab-id :connected|:disconnected} | parallel_tracking
```

## Popup State Contract

```
λ popup_state_contract.
  popup_state ≡ {
    :ports/nrepl string                       ; effective port for hostname
    :ports/ws string                          ; effective port for hostname
    :ports/source {:nrepl :default|:override  ; track provenance
                   :ws :default|:override}
    :settings/default-nrepl-port string       ; global default
    :settings/default-ws-port string          ; global default
    :settings/auto-connect-level string       ; "off"|"all-pages"|"current"
    :settings/auto-reconnect-repl boolean
    :fs/sync-tab-id number|nil                ; FS sync status
    :scripts/list [script]                    ; all scripts
    :scripts/current-url string               ; active tab URL
    :scripts/current-tab-id number            ; active tab ID
    :runtime/errors {}                        ; {script-name -> error-envelope} for current tab
    :ui/scripts-shadow [shadow-item]          ; animation shadow
  }
  | loaded_fresh_on_every_popup_open | ¬persistent_in_memory
```

## Panel State Contract

```
λ panel_state_contract.
  panel_state ≡ {
    :panel/code string                        ; editor content
    :panel/evaluating? boolean                ; eval in progress
    :panel/scittle-status keyword             ; :unknown :checking :loading :loaded "error"
    :panel/script-name string                 ; from manifest or user input
    :panel/original-name string|nil           ; track rename detection
    :panel/script-id string|nil               ; for updates
    :panel/script-match string                ; URL pattern
    :panel/script-description string
    :panel/manifest-hints map                 ; parsed manifest metadata
    :panel/results [result-entry]             ; eval history
    :panel/current-hostname string
    :runtime/errors {}                        ; {script-name -> error-envelope} for inspected tab
    :ui/system-banners [banner]               ; system messages
    :sponsor/status boolean
  }
  | result-entry ≡ {:type :input|:output|:error :text string}
  | manifest-hints ≡ {:name-normalized? :raw-script-name :unknown-keys :run-at :inject}
```

## Config Injection Contract

```
λ config_injection_contract.
  js/EXTENSION_CONFIG ≡ esbuild_define_injected_at_bundle_time
  | shape: {:dev boolean
  |         :depsString string
  |         :sectionsCollapsed {:repl-connect boolean
  |                             :manual-scripts boolean
  |                             :matching-scripts boolean
  |                             :other-scripts boolean
  |                             :special boolean
  |                             :libraries boolean
  |                             :settings boolean
  |                             :dev-tools boolean}}
  | sources: config/dev.edn ∧ config/prod.edn ∧ config/test.edn
  | accessed_via: (.-dev config) (.-depsString config) (.-sectionsCollapsed config)
```
