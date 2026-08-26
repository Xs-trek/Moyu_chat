function isEditable(node) {
  if (!(node instanceof Element)) return false;
  return node.matches('input, textarea, select, [contenteditable="true"]');
}

function hasTextSelection() {
  const selection = window.getSelection?.();
  return Boolean(selection && !selection.isCollapsed && String(selection).trim().length > 0);
}

/**
 * Keeps destructive full-page renders away from text-selection / edit / IME interactions.
 * Ordinary taps never acquire the pointer lease, so buttons/navigation have no selection grace delay.
 */
export function createRenderGate(renderer) {
  let pending = false;
  let scheduled = false;
  let pointerActive = false;
  let composing = false;
  let pointerReleaseTimer = 0;

  const blocked = () => pointerActive || composing || isEditable(document.activeElement) || hasTextSelection();

  const flush = () => {
    scheduled = false;
    if (!pending || blocked()) return;
    pending = false;
    renderer();
  };

  const schedule = () => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(flush);
  };

  return {
    request() { pending = true; schedule(); },
    pointerDown(selectable = true) {
      clearTimeout(pointerReleaseTimer);
      pointerActive = Boolean(selectable);
    },
    pointerUp(graceMs = 160) {
      clearTimeout(pointerReleaseTimer);
      if (!pointerActive || graceMs <= 0) {
        pointerActive = false;
        schedule();
        return;
      }
      pointerReleaseTimer = setTimeout(() => { pointerActive = false; schedule(); }, graceMs);
    },
    compositionStart() { composing = true; },
    compositionEnd() { composing = false; schedule(); },
    interactionEnded() { schedule(); },
    flushNow() { if (!blocked()) { pending = false; renderer(); } else { pending = true; schedule(); } },
  };
}
