import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installDom } from './dom-shim.mjs';

installDom();
for (const id of ['app', 'drawer-root', 'panel-root', 'sheet-root', 'toast-root']) {
  const node = document.createElement('div'); node.id = id; document.body.append(node);
}
const { getState, patchView, setView, subscribe } = await import('../src/state.js');
const { patchStreamItem, render } = await import('../src/render.js');

const at = (minute) => new Date(Date.UTC(2026, 9, 12, 1, minute)).toISOString();
const messages = [];
for (let i = 0; i < 12; i += 1) {
  const seq = messages.length;
  messages.push({ localSeq: seq + 1, kind: 'message', role: 'user', text: `问题 ${i + 1}`, createdAt: at(i * 2) });
  messages.push({ localSeq: seq + 2, turnId: `turn-${i}`, kind: 'message', role: 'assistant', text: `回答 ${i + 1}\n\n- 要点 a\n- 要点 b`, model: 'm', modelName: 'Model', createdAt: at(i * 2 + 1) });
  messages.push({ localSeq: seq + 3, kind: 'usage', inputTokens: 100, outputTokens: 20, createdAt: at(i * 2 + 1) });
}
const liveSeq = messages.length + 2;
messages.push({ localSeq: liveSeq - 1, kind: 'message', role: 'user', text: '新问题', createdAt: new Date().toISOString() });
const live = { localSeq: liveSeq, kind: 'message', role: 'assistant', text: '第一段', streaming: true, streamStage: 'output', createdAt: new Date().toISOString() };
messages.push(live);
const running = { state: 'running', canSend: false, canInterrupt: true, canConfigureTurn: false, contextPreparing: false, webPreparingStage: null, postProcessingStage: null };
const session = {
  localSessionId: 's-1', title: '长会话', providerId: 'p', providerName: 'Provider', modelProfileId: 'mp', model: 'm', modelName: 'Model',
  reasoningLevels: '', reasoningEffort: 'default', contextMode: 'standard', effectiveContextMode: 'standard', webMode: 'auto',
  inputModalities: 'text', outputModalities: 'text', updatedAt: at(40), messages, composerDraft: '', draftMedia: [], canAttachImage: false, ...running,
};
setView({
  route: 'conversation', now: new Date().toISOString(),
  appearance: { theme: 'light', resolvedTheme: 'light', tone: 'default', delight: false },
  providers: [], sessions: [{ ...session, messages: undefined }], activeLocalSessionId: 's-1', activeSession: session,
  ui: { showReasoning: false, defaultContextMode: 'standard', defaultWebMode: 'auto' },
}, 1);
render();

const changes = [];
subscribe((_state, change) => changes.push(change));
let revision = 1;
function stream(item, sessionPatch = running) {
  changes.length = 0;
  assert.ok(patchView({ scope: 'conversation.stream', localSessionId: 's-1', session: sessionPatch, item }, ++revision));
  return changes.at(-1);
}

const timelineNodes = () => [...document.querySelector('.timeline').children];

test('the conversation renders every turn and the live reply', () => {
  assert.equal(timelineNodes().filter((node) => node.classList.contains('assistant')).length, 13);
});

test('a streaming patch updates only the live reply and keeps every other node', () => {
  const before = timelineNodes();
  const change = stream({ ...live, text: '第一段\n\n第二段继续输出' });
  assert.equal(change.scope, 'stream');
  assert.ok(patchStreamItem(change), 'fast path taken');
  const after = timelineNodes();
  assert.equal(after.length, before.length);
  after.forEach((node, i) => assert.equal(node, before[i], `node ${i} kept`));
  const liveNode = after.at(-1);
  assert.match(liveNode.querySelector('.markdown-body').textContent, /第二段继续输出/);
  assert.equal(getState().view.activeSession.messages.at(-1).text, '第一段\n\n第二段继续输出');
});

test('a change in turn state falls back to the full conversation patch', () => {
  const change = stream({ ...live, text: '第一段\n\n第二段继续输出', streamStage: 'verifying' }, { ...running, postProcessingStage: 'verifying' });
  assert.equal(patchStreamItem(change), false);
});

test('a patch for a reply that is not on screen yet falls back', () => {
  const change = stream({ ...live, localSeq: liveSeq + 5, text: 'x' });
  assert.equal(patchStreamItem(change), false);
});
