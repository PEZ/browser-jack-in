// Content script bridge for WebSocket connections
// Runs in ISOLATED world with extension CSP, bridges to MAIN world via postMessage

console.log('[Browser Jack-in Bridge] Content script loaded');

let ws = null;

// Listen for messages from page
window.addEventListener('message', (event) => {
  // Only accept messages from same origin
  if (event.source !== window) return;

  const msg = event.data;
  if (!msg || msg.source !== 'browser-jack-in-page') return;

  console.log('[Bridge] Received from page:', msg.type);

  if (msg.type === 'ws-connect') {
    const wsUrl = `ws://localhost:${msg.port}/_nrepl`;
    console.log('[Bridge] Connecting to:', wsUrl);

    try {
      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        console.log('[Bridge] WebSocket connected');
        window.postMessage({
          source: 'browser-jack-in-bridge',
          type: 'ws-open'
        }, '*');
      };

      ws.onmessage = (event) => {
        window.postMessage({
          source: 'browser-jack-in-bridge',
          type: 'ws-message',
          data: event.data
        }, '*');
      };

      ws.onerror = (error) => {
        console.error('[Bridge] WebSocket error:', error);
        window.postMessage({
          source: 'browser-jack-in-bridge',
          type: 'ws-error',
          error: String(error)
        }, '*');
      };

      ws.onclose = () => {
        console.log('[Bridge] WebSocket closed');
        window.postMessage({
          source: 'browser-jack-in-bridge',
          type: 'ws-close'
        }, '*');
        ws = null;
      };
    } catch (error) {
      console.error('[Bridge] Failed to create WebSocket:', error);
      window.postMessage({
        source: 'browser-jack-in-bridge',
        type: 'ws-error',
        error: String(error)
      }, '*');
    }
  } else if (msg.type === 'ws-send') {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(msg.data);
    }
  }
});

// Notify page that bridge is ready
window.postMessage({
  source: 'browser-jack-in-bridge',
  type: 'bridge-ready'
}, '*');
