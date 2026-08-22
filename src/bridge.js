const pending = new Map();
let sequence = 0;

export function postIntent(type, payload = {}) {
  const requestId = `chat-${Date.now()}-${++sequence}`;
  const message = { version: 1, type, requestId, payload };
  const host = window.MoyuHost;
  if (!host || typeof host.postMessage !== 'function') {
    window.dispatchEvent(new CustomEvent('moyu:notice', { detail: { level: 'error', text: 'Native Host 不可用' } }));
    return null;
  }
  pending.set(requestId, type);
  host.postMessage(JSON.stringify(message));
  return requestId;
}

export function bindHost(onView) {
  window.addEventListener('moyu:view', (event) => {
    const envelope = event.detail;
    if (!envelope || envelope.version !== 1) return;
    if (envelope.type === 'view.full' && envelope.view) onView(envelope.view, envelope.revision || 0);
    if (envelope.type === 'intent.result' && envelope.requestId) {
      pending.delete(envelope.requestId);
      if (!envelope.ok) window.dispatchEvent(new CustomEvent('moyu:notice', { detail: { level: 'error', text: envelope.error?.summary || '操作失败' } }));
    }
  });
}
