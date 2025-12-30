import * as squint_core from 'squint-cljs/core.js';
console.log("[Browser Jack-in Bridge] Content script loaded");
var _BANG_ws = squint_core.atom(null);
window.addEventListener("message", (function (event) {
if (squint_core._EQ_(event.source, window)) {
const msg1 = event.data;
if (squint_core.truth_((() => {
const and__23248__auto__2 = msg1;
if (squint_core.truth_(and__23248__auto__2)) {
return ("browser-jack-in-page" === msg1.source)} else {
return and__23248__auto__2};

})())) {
console.log("[Bridge] Received from page:", msg1.type);
const G__13 = msg1.type;
switch (G__13) {case "ws-connect":
const ws_url5 = `${"ws://localhost:"}${msg1.port??''}${"/_nrepl"}`;
console.log("[Bridge] Connecting to:", ws_url5);
return (() => {
try{
const ws6 = (new WebSocket(ws_url5));
squint_core.reset_BANG_(_BANG_ws, ws6);
ws6.onopen = (function () {
console.log("[Bridge] WebSocket connected");
return window.postMessage(({"source": "browser-jack-in-bridge", "type": "ws-open"}), "*");

});
ws6.onmessage = (function (event) {
return window.postMessage(({"source": "browser-jack-in-bridge", "type": "ws-message", "data": event.data}), "*");

});
ws6.onerror = (function (error) {
console.error("[Bridge] WebSocket error:", error);
return window.postMessage(({"source": "browser-jack-in-bridge", "type": "ws-error", "error": `${error??''}`}), "*");

});
return ws6.onclose = (function () {
console.log("[Bridge] WebSocket closed");
window.postMessage(({"source": "browser-jack-in-bridge", "type": "ws-close"}), "*");
return squint_core.reset_BANG_(_BANG_ws, null);

});
}
catch(e7){
console.error("[Bridge] Failed to create WebSocket:", e7);
return window.postMessage(({"source": "browser-jack-in-bridge", "type": "ws-error", "error": `${e7??''}`}), "*");
}

})();

break;
case "ws-send":
const temp__22878__auto__8 = squint_core.deref(_BANG_ws);
if (squint_core.truth_(temp__22878__auto__8)) {
const ws9 = temp__22878__auto__8;
if ((1 === ws9.readyState)) {
return ws9.send(msg1.data);
};
};

break;
};
};
};

}));
window.postMessage(({"source": "browser-jack-in-bridge", "type": "bridge-ready"}), "*");

export { _BANG_ws }
