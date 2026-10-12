import { requestIntent } from './bridge.js';

/**
 * Turn traces are read on demand (never pushed with the view). A finished turn's trace never changes,
 * so each is fetched once and kept in a small bounded cache.
 */
const MAX_CACHED = 40;
const cache = new Map();
let listener = () => {};

/** Called whenever a requested trace arrives or fails, so the caller can re-render the expanded footer. */
export function onTraceChange(fn) { listener = typeof fn === 'function' ? fn : () => {}; }

/** Current cache entry without starting a request: null, or { state: 'loading' | 'ready' | 'error', data?, error? }. */
export function peekTrace(turnId) { return turnId ? cache.get(turnId) || null : null; }

/** Cache entry for the turn, starting the request on first use. */
export function loadTrace(sessionId, turnId) {
  if (!turnId) return null;
  const known = cache.get(turnId);
  if (known) return known;
  const entry = { state: 'loading' };
  cache.set(turnId, entry);
  if (cache.size > MAX_CACHED) cache.delete(cache.keys().next().value);
  requestIntent('turn.trace.get', { localSessionId: sessionId, turnId })
    .then((data) => { cache.set(turnId, { state: 'ready', data }); listener(); })
    .catch((error) => { cache.set(turnId, { state: 'error', error: String(error?.message || error || '读取失败') }); listener(); });
  return entry;
}

export function forgetTrace(turnId) { cache.delete(turnId); }
