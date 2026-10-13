import { getState, patchView } from './state.js';

const MAX_MESSAGE_BYTES = 1024 * 1024;
const pending = new Map();
/** requestId → { resolve, reject, timer } for intents whose result carries data. */
const waiters = new Map();
let sequence = 0;

function byteLength(value) {
  try { return new TextEncoder().encode(value).byteLength; }
  catch { return value.length * 2; }
}

function notice(level, text) {
  window.dispatchEvent(new CustomEvent('moyu:notice', { detail: { level, text } }));
}

export function postIntent(type, payload = {}, waiter = null) {
  const requestId = `chat-${Date.now()}-${++sequence}`;
  const raw = JSON.stringify({ version: 1, type, requestId, payload });
  if (byteLength(raw) > MAX_MESSAGE_BYTES) { notice('error', '操作数据超过 Host 消息限制'); return null; }
  const host = window.MoyuHost;
  if (!host || typeof host.postMessage !== 'function') { notice('error', 'Native Host 不可用'); return null; }
  pending.set(requestId, type);
  if (waiter) waiters.set(requestId, waiter);
  host.postMessage(raw);
  return requestId;
}

/** Read-style intent: resolves with the result's data, rejects with its error. Failures are the caller's to show. */
export function requestIntent(type, payload = {}, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const waiter = { resolve, reject, timer: 0 };
    const requestId = postIntent(type, payload, waiter);
    if (!requestId) { reject(new Error('Native Host 不可用')); return; }
    waiter.timer = setTimeout(() => { if (waiters.delete(requestId)) reject(new Error('请求超时')); }, timeoutMs);
  });
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
    // Native messages use the page's own toast, so the app has one notification style.
    if (envelope.type === 'notice' && envelope.text) { notice(envelope.level === 'error' ? 'error' : envelope.level === 'warning' ? 'warning' : 'info', String(envelope.text)); return; }
    if (envelope.type === 'intent.result' && envelope.requestId) {
      pending.delete(envelope.requestId);
      const waiter = waiters.get(envelope.requestId);
      if (waiter) {
        waiters.delete(envelope.requestId);
        clearTimeout(waiter.timer);
        if (envelope.ok) waiter.resolve(envelope.data ?? null);
        else waiter.reject(new Error(envelope.error?.summary || '操作失败'));
        return;
      }
      if (!envelope.ok) notice('error', envelope.error?.summary || '操作失败');
    }
  });
}
