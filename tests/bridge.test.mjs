import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installDom } from './dom-shim.mjs';

installDom();
const listeners = new Map();
globalThis.addEventListener = (type, listener) => { if (!listeners.has(type)) listeners.set(type, []); listeners.get(type).push(listener); };
globalThis.dispatchEvent = (event) => { for (const listener of listeners.get(event.type) || []) listener(event); return true; };
const sent = [];
globalThis.MoyuHost = { postMessage: (raw) => sent.push(JSON.parse(raw)) };

const { bindHost, postIntent } = await import('../src/bridge.js');
bindHost(() => {});
const deliver = (envelope) => globalThis.dispatchEvent(new CustomEvent('moyu:view', { detail: { version: 1, ...envelope } }));

test('a rejected send hands its result data to the caller with the error', async () => {
  const outcome = new Promise((resolve, reject) => postIntent('chat.session.send', { localSessionId: 's', text: 'hi' }, { resolve, reject }));
  const { requestId } = sent.at(-1);
  deliver({ type: 'intent.result', requestId, ok: false, error: { summary: 'provider credential missing' }, data: { draftRestored: true } });
  await assert.rejects(outcome, (error) => error.message === 'provider credential missing' && error.data?.draftRestored === true);
});

test('a failure without a waiter still shows a notice', () => {
  const notices = [];
  globalThis.addEventListener('moyu:notice', (event) => notices.push(event.detail));
  postIntent('nav.open', { route: 'home' });
  const { requestId } = sent.at(-1);
  deliver({ type: 'intent.result', requestId, ok: false, error: { summary: '失败了' } });
  assert.deepEqual(notices.at(-1), { level: 'error', text: '失败了' });
});

test('network checks wait longer than Native does; other reads keep the short default', async () => {
  const { INTENT_TIMEOUTS } = await import('../src/bridge.js');
  assert.ok(INTENT_TIMEOUTS['model.probe'] > 60000, 'a connection test may take Native 60 s');
  for (const type of ['provider.key.verify', 'route.models.list', 'provider.balance.get']) assert.ok(INTENT_TIMEOUTS[type] > 30000, type);
  assert.equal(INTENT_TIMEOUTS['turn.trace.get'], undefined);
});
