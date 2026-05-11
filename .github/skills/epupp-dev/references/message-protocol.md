# Message Protocol

Message contracts governing communication between Epupp's six architectural layers.

## Message Relay Contract

```
λ message_relay_contract.
  page_to_background:
    page → postMessage({source: "epupp-page", type, ...})
    → ws_bridge_ignores(source ≠ "epupp-bridge")
    → content_bridge_receives(source ≡ "epupp-page")
    → content_bridge_validates(message_registry)
    → chrome.runtime.sendMessage({type, tabId, ...})
    → background_handles
  background_to_page:
    background → chrome.tabs.sendMessage(tabId, {source: "epupp-bridge", type, data})
    → content_bridge_receives
    → window.postMessage({source: "epupp-bridge", type, data})
    → ws_bridge_receives(source ≡ "epupp-bridge")
    → page_handles
```

## Message Registry Contract

```
λ message_registry_contract.
  message-registry ≡ {"msg-type" {:msg/sources set
                                   :msg/response? boolean
                                   :msg/response-type string|nil
                                   :msg/pre-forward fn|nil}}
  | :msg/sources ≡ #{"epupp-page" "epupp-userscript"} | allowed_origins
  | :msg/response? true → requestId_tracked → response_relayed_back
  | :msg/pre-forward → guard_fn(msg) → truthy ≡ forward | falsy ≡ drop
  | unregistered_message_type → dropped_silently | logged_in_dev
```

## Chrome Runtime Message Contract

```
λ chrome_runtime_message_contract.
  popup_or_panel → chrome.runtime.sendMessage({type, ...params})
  → background.onMessage_handler
  | type_strings: "connect-tab" "check-status" "disconnect-tab"
  |               "ensure-scittle" "evaluate-script" "inject-libs"
  |               "panel-save-script" "panel-rename-script"
  |               "get-connections" "get-runtime-status"
  |               "loader-resolution-errors"
  | broadcast_types: "runtime-status"
  | background → popup/panel: runtime_error_status_broadcasts
  | e2e_test_types: "e2e-get-storage" "e2e-set-storage" "e2e-get-test-events" "e2e-find-tab-id"
  | response: sendResponse(result) | async_requires_return_true
```

## WebSocket Bridge Message Contract

```
λ ws_bridge_message_contract.
  page_sends:
    {source: "epupp-page", type: "ws-connect", port: number}
    {source: "epupp-page", type: "ws-send", data: string}
  bridge_sends:
    {source: "epupp-bridge", type: "ws-open"}
    {source: "epupp-bridge", type: "ws-message", data: string}
    {source: "epupp-bridge", type: "ws-close"}
    {source: "epupp-bridge", type: "ws-error", error: string}
  | page_listens_for: source ≡ "epupp-bridge" | ignores_others
  | bridge_listens_for: source ≡ "epupp-page" | ignores_others
```
