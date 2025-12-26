// Popup script

// Inject a script tag and return immediately
async function injectScript(tabId, url) {
  console.log('[popup] Injecting:', url, 'into tab:', tabId);
  try {
    const result = await chrome.scripting.executeScript({
      target: { tabId },
      world: 'MAIN',
      func: (url) => {
        const script = document.createElement('script');
        script.src = url;
        document.head.appendChild(script);
        console.log('[DOM REPL] Injected:', url);
        return 'ok';
      },
      args: [url]
    });
    console.log('[popup] Injection result:', result);
    return result;
  } catch (err) {
    console.error('[popup] Injection error:', err);
    throw err;
  }
}

// Set the nREPL config (host + port)
function setNreplConfig(tabId, port) {
  return chrome.scripting.executeScript({
    target: { tabId },
    world: 'MAIN',
    func: (port) => {
      window.SCITTLE_NREPL_WEBSOCKET_HOST = 'localhost';
      window.SCITTLE_NREPL_WEBSOCKET_PORT = port;
      console.log('[DOM REPL] Set nREPL to ws://localhost:' + port);
    },
    args: [port]
  });
}

// Poll until Scittle is ready, also detect CSP errors
function waitForScittle(tabId, timeout = 5000) {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const poll = async () => {
      const [result] = await chrome.scripting.executeScript({
        target: { tabId },
        world: 'MAIN',
        func: () => {
          // Check for CSP error (Scittle sets this on failure)
          if (window.__scittle_csp_error) {
            return { error: 'csp' };
          }
          if (window.scittle && window.scittle.core) {
            return { ready: true };
          }
          // Try to detect if eval is blocked
          try {
            eval('1');
            return { ready: false };
          } catch (e) {
            return { error: 'csp' };
          }
        }
      });

      if (result.result.error === 'csp') {
        reject(new Error('Page blocks eval (CSP). Try a different page.'));
      } else if (result.result.ready) {
        resolve();
      } else if (Date.now() - start > timeout) {
        reject(new Error('Timeout - Scittle failed to load'));
      } else {
        setTimeout(poll, 100);
      }
    };
    poll();
  });
}

// Poll until WebSocket is connected
function waitForWebSocket(tabId, timeout = 5000) {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const poll = async () => {
      const [result] = await chrome.scripting.executeScript({
        target: { tabId },
        world: 'MAIN',
        func: () => {
          const ws = window.ws_nrepl;
          if (!ws) {
            return { status: 'no-ws' };
          }
          // WebSocket.CONNECTING = 0, OPEN = 1, CLOSING = 2, CLOSED = 3
          if (ws.readyState === 1) {
            return { status: 'connected' };
          } else if (ws.readyState === 3) {
            return { status: 'failed' };
          }
          return { status: 'connecting' };
        }
      });

      const status = result.result.status;
      if (status === 'connected') {
        resolve();
      } else if (status === 'failed') {
        reject(new Error('WebSocket connection failed. Is the server running?'));
      } else if (Date.now() - start > timeout) {
        reject(new Error('WebSocket connection timeout'));
      } else {
        setTimeout(poll, 100);
      }
    };
    poll();
  });
}

// Check current connection status in page
async function checkStatus(tabId) {
  try {
    const [result] = await chrome.scripting.executeScript({
      target: { tabId },
      world: 'MAIN',
      func: () => {
        const hasScittle = !!(window.scittle && window.scittle.core);
        const ws = window.ws_nrepl;
        const wsState = ws ? ws.readyState : -1;
        const wsPort = window.SCITTLE_NREPL_WEBSOCKET_PORT;
        return { hasScittle, wsState, wsPort };
      }
    });
    return result.result;
  } catch (e) {
    return { hasScittle: false, wsState: -1, wsPort: null };
  }
}

// Update status display on popup open
async function updateStatusOnLoad() {
  const statusEl = document.getElementById('status');
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const nreplPort = document.getElementById('nrepl-port').value;

  const { hasScittle, wsState, wsPort } = await checkStatus(tab.id);

  if (wsState === 1) {
    statusEl.textContent = 'Connected! Editor: localhost:' + nreplPort;
  } else if (wsState === 0) {
    statusEl.textContent = 'Connecting...';
  } else if (wsState === 3) {
    statusEl.textContent = 'Failed: WebSocket connection failed. Is the server running?';
  } else if (hasScittle) {
    statusEl.textContent = 'Scittle loaded, not connected';
  }
}

// Check status when popup opens
updateStatusOnLoad();

document.getElementById('start').addEventListener('click', async () => {
  const nreplPort = parseInt(document.getElementById('nrepl-port').value, 10);
  const wsPort = parseInt(document.getElementById('ws-port').value, 10);
  const statusEl = document.getElementById('status');

  if (isNaN(nreplPort) || nreplPort < 1 || nreplPort > 65535) {
    statusEl.textContent = 'Invalid nREPL port';
    return;
  }
  if (isNaN(wsPort) || wsPort < 1 || wsPort > 65535) {
    statusEl.textContent = 'Invalid WebSocket port';
    return;
  }

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });

  try {
    // Inject Scittle
    statusEl.textContent = 'Loading Scittle...';
    const scittleUrl = chrome.runtime.getURL('vendor/scittle.js');
    await injectScript(tab.id, scittleUrl);

    // Wait for Scittle to initialize
    await waitForScittle(tab.id);

    // Set port and inject nREPL
    statusEl.textContent = 'Connecting...';
    await setNreplConfig(tab.id, wsPort);
    const nreplUrl = chrome.runtime.getURL('vendor/scittle.nrepl.js');
    await injectScript(tab.id, nreplUrl);

    // Wait for WebSocket to actually connect
    await waitForWebSocket(tab.id);

    statusEl.textContent = 'Connected! Editor: localhost:' + nreplPort;
  } catch (err) {
    statusEl.textContent = 'Failed: ' + err.message;
    console.error(err);
  }
});
