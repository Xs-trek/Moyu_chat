import { getState, patchView } from './state.js';

const MAX_MESSAGE_BYTES = 1024 * 1024;
const pending = new Map();
let sequence = 0;

function byteLength(value) {
  try { return new TextEncoder().encode(value).byteLength; }
  catch { return value.length * 2; }
}

function notice(level, text) {
  window.dispatchEvent(new CustomEvent('moyu:notice', { detail: { level, text } }));
}

export function postIntent(type, payload = {}) {
  const requestId = `chat-${Date.now()}-${++sequence}`;
  const raw = JSON.stringify({ version: 1, type, requestId, payload });
  if (byteLength(raw) > MAX_MESSAGE_BYTES) { notice('error', '操作数据超过 Host 消息限制'); return null; }
  const host = window.MoyuHost;
  if (!host || typeof host.postMessage !== 'function') { notice('error', 'Native Host 不可用'); return null; }
  pending.set(requestId, type);
  host.postMessage(raw);
  return requestId;
}

export function bindHost(onView) {
  window.addEventListener('moyu:view', (event) => {
    const envelope = event.detail;
    if (!envelope || envelope.version !== 1) return;
    if (envelope.type === 'view.full' && envelope.view) {
      const revision = Number(envelope.revision) || 0;
      if (revision > 0 && revision <= getState().revision) return;
      onView(envelope.view, revision);
      return;
    }
    if (envelope.type === 'view.patch' && envelope.patch) {
      const revision = Number(envelope.revision) || 0;
      if (revision > 0 && revision <= getState().revision) return;
      if (!patchView(envelope.patch, revision)) postIntent('view.reload', {});
      return;
    }
    if (envelope.type === 'intent.result' && envelope.requestId) {
      pending.delete(envelope.requestId);
      if (!envelope.ok) notice('error', envelope.error?.summary || '操作失败');
    }
  });
}
