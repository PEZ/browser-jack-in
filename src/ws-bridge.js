import * as squint_core from 'squint-cljs/core.js';
console.log("[Browser Jack-in] Installing WebSocket bridge");
var _BANG_bridge_ready = squint_core.atom(false);
window.addEventListener("message", (function (event) {
if (squint_core._EQ_(event.source, window)) {
const msg1 = event.data;
if (squint_core.truth_((() => {
const and__23248__auto__2 = msg1;
if (squint_core.truth_(and__23248__auto__2)) {
return (("browser-jack-in-bridge" === msg1.source) && ("bridge-ready" === msg1.type))} else {
return and__23248__auto__2};

})())) {
console.log("[WS Bridge] Bridge is ready");
return squint_core.reset_BANG_(_BANG_bridge_ready, true);
};
};

}));
var bridged_websocket = function (url) {
console.log("[WS Bridge] Creating bridged WebSocket for:", url);
const ws_obj1 = squint_core.js_obj();
const message_handler2 = squint_core.atom(null);
ws_obj1.url = url;
ws_obj1.readyState = 0;
ws_obj1.onopen = null;
ws_obj1.onmessage = null;
ws_obj1.onerror = null;
ws_obj1.onclose = null;
ws_obj1.CONNECTING = 0;
ws_obj1.OPEN = 1;
ws_obj1.CLOSING = 2;
ws_obj1.CLOSED = 3;
const port3 = (() => {
const temp__22775__auto__4 = url.match(/:(\d+)\//);
if (squint_core.truth_(temp__22775__auto__4)) {
const match5 = temp__22775__auto__4;
return match5[1];
} else {
return "1340"};

})();
squint_core.reset_BANG_(message_handler2, (function (event) {
if (squint_core._EQ_(event.source, window)) {
const msg6 = event.data;
if (squint_core.truth_((() => {
const and__23248__auto__7 = msg6;
if (squint_core.truth_(and__23248__auto__7)) {
return ("browser-jack-in-bridge" === msg6.source)} else {
return and__23248__auto__7};

})())) {
const G__18 = msg6.type;
switch (G__18) {case "ws-open":
console.log("[WS Bridge] WebSocket OPEN");
ws_obj1.readyState = 1;
const temp__22878__auto__10 = ws_obj1.onopen;
if (squint_core.truth_(temp__22878__auto__10)) {
const onopen11 = temp__22878__auto__10;
return onopen11();
};

break;
case "ws-message":
const temp__22878__auto__12 = ws_obj1.onmessage;
if (squint_core.truth_(temp__22878__auto__12)) {
const onmessage13 = temp__22878__auto__12;
return onmessage13(({"data": msg6.data}));
};

break;
case "ws-error":
console.log("[WS Bridge] WebSocket ERROR");
ws_obj1.readyState = 3;
const temp__22878__auto__14 = ws_obj1.onerror;
if (squint_core.truth_(temp__22878__auto__14)) {
const onerror15 = temp__22878__auto__14;
return onerror15((new Error((() => {
const or__23228__auto__16 = msg6.error;
if (squint_core.truth_(or__23228__auto__16)) {
return or__23228__auto__16} else {
return "WebSocket error"};

})())));
};

break;
case "ws-close":
console.log("[WS Bridge] WebSocket CLOSED");
ws_obj1.readyState = 3;
const temp__22878__auto__17 = ws_obj1.onclose;
if (squint_core.truth_(temp__22878__auto__17)) {
const onclose18 = temp__22878__auto__17;
return onclose18();
};

break;
};
};
};

}));
window.addEventListener("message", squint_core.deref(message_handler2));
ws_obj1.send = (function (data) {
if ((1 === ws_obj1.readyState)) {
return window.postMessage(({"source": "browser-jack-in-page", "type": "ws-send", "data": data}), "*");
};

});
ws_obj1.close = (function () {
ws_obj1.readyState = 3;
window.removeEventListener("message", squint_core.deref(message_handler2));
const temp__22878__auto__19 = ws_obj1.onclose;
if (squint_core.truth_(temp__22878__auto__19)) {
const onclose20 = temp__22878__auto__19;
return onclose20();
};

});
window.postMessage(({"source": "browser-jack-in-page", "type": "ws-connect", "port": port3}), "*");
return ws_obj1;

};
window._OriginalWebSocket = WebSocket;
WebSocket = (function (url, protocols) {
if (squint_core.truth_((() => {
const and__23248__auto__1 = squint_core.string_QMARK_(url);
if (squint_core.truth_(and__23248__auto__1)) {
return url.includes("/_nrepl")} else {
return and__23248__auto__1};

})())) {
console.log("[WS Bridge] Intercepting nREPL WebSocket:", url);
const ws2 = bridged_websocket(url);
window.ws_nrepl = ws2;
return ws2;
} else {
return (new window._OriginalWebSocket(url, protocols))};

});
WebSocket.CONNECTING = 0;
WebSocket.OPEN = 1;
WebSocket.CLOSING = 2;
WebSocket.CLOSED = 3;
console.log("[WS Bridge] WebSocket bridge installed");

export { _BANG_bridge_ready, bridged_websocket }
