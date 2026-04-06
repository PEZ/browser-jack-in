// Disable Scittle's built-in DOMContentLoaded auto-eval.
// Scittle registers a DOMContentLoaded listener that calls eval_script_tags().
// When Scittle is loaded at document-start, this listener fires later and
// re-evaluates all script tags, causing double execution.
// Must be injected immediately after Scittle loads, before DOMContentLoaded.
if (window.scittle && window.scittle.core && window.scittle.core.disable_auto_eval) {
  scittle.core.disable_auto_eval();
}
