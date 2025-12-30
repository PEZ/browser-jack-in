// WebSocket bridge wrapper for page context
// This runs in MAIN world and communicates with the content script bridge via postMessage

(function() {
  'use strict';

  console.log('[Browser Jack-in] Installing WebSocket bridge');

  let bridgeReady = false;
  const messageQueue = [];

  // Wait for bridge ready signal
  window.addEventListener('message', (event) => {
    if (event.source !== window) return;
    const msg = event.data;
    if (msg && msg.source === 'browser-jack-in-bridge' && msg.type === 'bridge-ready') {
      console.log('[WS Bridge] Bridge is ready');
      bridgeReady = true;
    }
  });

  class BridgedWebSocket {
    constructor(url) {
      console.log('[WS Bridge] Creating bridged WebSocket for:', url);

      this.url = url;
      this.readyState = 0; // CONNECTING
      this.onopen = null;
      this.onmessage = null;
      this.onerror = null;
      this.onclose = null;

      // Expose WebSocket constants on instance
      this.CONNECTING = 0;
      this.OPEN = 1;
      this.CLOSING = 2;
      this.CLOSED = 3;

      // Parse port from URL
      const match = url.match(/:(\d+)\//);
      const port = match ? match[1] : '1340';

      // Set up message listener before requesting connection
      this._messageHandler = (event) => {
        if (event.source !== window) return;
        const msg = event.data;
        if (!msg || msg.source !== 'browser-jack-in-bridge') return;

        if (msg.type === 'ws-open') {
          console.log('[WS Bridge] WebSocket OPEN');
          this.readyState = 1; // OPEN
          if (this.onopen) this.onopen();
        } else if (msg.type === 'ws-message') {
          if (this.onmessage) this.onmessage({ data: msg.data });
        } else if (msg.type === 'ws-error') {
          console.log('[WS Bridge] WebSocket ERROR');
          this.readyState = 3; // CLOSED
          if (this.onerror) this.onerror(new Error(msg.error || 'WebSocket error'));
        } else if (msg.type === 'ws-close') {
          console.log('[WS Bridge] WebSocket CLOSED');
          this.readyState = 3; // CLOSED
          if (this.onclose) this.onclose();
        }
      };

      window.addEventListener('message', this._messageHandler);

      // Request connection through bridge
      window.postMessage({
        source: 'browser-jack-in-page',
        type: 'ws-connect',
        port: port
      }, '*');
    }

    send(data) {
      if (this.readyState === WebSocket.OPEN) {
        window.postMessage({
          source: 'browser-jack-in-page',
          type: 'ws-send',
          data: data
        }, '*');
      }
    }

    close() {
      this.readyState = 3; // CLOSED
      window.removeEventListener('message', this._messageHandler);
      if (this.onclose) this.onclose();
    }
  }

  // Copy WebSocket constants to class
  BridgedWebSocket.CONNECTING = 0;
  BridgedWebSocket.OPEN = 1;
  BridgedWebSocket.CLOSING = 2;
  BridgedWebSocket.CLOSED = 3;

  // Store original WebSocket
  window._OriginalWebSocket = window.WebSocket;

  // Override WebSocket for nREPL URLs only
  window.WebSocket = function(url, protocols) {
    if (typeof url === 'string' && url.includes('/_nrepl')) {
      console.log('[WS Bridge] Intercepting nREPL WebSocket:', url);
      const ws = new BridgedWebSocket(url);
      // Store reference for Scittle's usage
      window.ws_nrepl = ws;
      return ws;
    }
    return new window._OriginalWebSocket(url, protocols);
  };

  // Copy static properties
  window.WebSocket.CONNECTING = WebSocket.CONNECTING;
  window.WebSocket.OPEN = WebSocket.OPEN;
  window.WebSocket.CLOSING = WebSocket.CLOSING;
  window.WebSocket.CLOSED = WebSocket.CLOSED;

  console.log('[WS Bridge] WebSocket bridge installed');
})();
