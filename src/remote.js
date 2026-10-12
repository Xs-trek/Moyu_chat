import { requestIntent } from './bridge.js';

/**
 * Data read on demand with a read-only intent (never pushed with the view): turn traces, answer
 * comparisons, statistics. Entries are kept in a small bounded cache; a finished turn's trace never
 * changes, while statistics are forgotten whenever their page is opened again.
 */
const MAX_CACHED = 60;
const cache = new Map();
let listener = () => {};

/** Called whenever a requested entry arrives or fails, so the caller can re-render. */
export function onRemoteChange(fn) { listener = typeof fn === 'function' ? fn : () => {}; }

/** Current entry without starting a request: null, or { state: 'loading' | 'ready' | 'error', data?, error? }. */
export function peekRemote(key) { return key ? cache.get(key) || null : null; }

/** Entry for the key, starting the request on first use. */
export function loadRemote(key, type, payload) {
  if (!key) return null;
  const known = cache.get(key);
  if (known) return known;
  const entry = { state: 'loading' };
  cache.set(key, entry);
  if (cache.size > MAX_CACHED) cache.delete(cache.keys().next().value);
  requestIntent(type, payload)
    .then((data) => { if (cache.get(key) === entry) { cache.set(key, { state: 'ready', data }); listener(); } })
    .catch((error) => { if (cache.get(key) === entry) { cache.set(key, { state: 'error', error: String(error?.message || error || '读取失败') }); listener(); } });
  return entry;
}

export function forgetRemote(key) { cache.delete(key); }

export const traceKey = (turnId) => (turnId ? `trace:${turnId}` : '');
export function loadTrace(sessionId, turnId) { return loadRemote(traceKey(turnId), 'turn.trace.get', { localSessionId: sessionId, turnId }); }
export function peekTrace(turnId) { return peekRemote(traceKey(turnId)); }
