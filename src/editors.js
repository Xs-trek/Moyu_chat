// Provider and Model Profile editors: full-screen panels in the WebView (they used to be native dialogs).
// The API Key is the one exception: it is typed into a native password field opened through
// `provider.credential.edit`, so the key never enters the page. These forms only ever send names,
// endpoints and declared deployment capabilities, and close only after native validation succeeds.
import { postIntent, requestIntent } from './bridge.js';
import { h, icon, iconButton, syncSegment } from './dom.js';
import { swapIn } from './morph.js';
import { closePanel, openChoice, openPanel, panelOpen } from './overlays.js';
import { getState, setNotice, subscribe } from './state.js';

let editor = null; // { kind, providerId, modelProfileId, node }

// Titles for the declared deployment fields. Their values come from native (`model.editor.options`):
// the validators own that vocabulary, so the page never hard-codes it.
const CHOICES = {
  protocol: { title: '协议 (protocol)' },
  authMode: { title: '鉴权方式 (authMode)', note: '这个模型路线实际使用的请求头。同一个 Provider 下的不同模型可以不同。' },
  reasoningWire: { title: '推理参数映射 (reasoningWire)', note: '必须与本模型的协议匹配；只有 endpoint 明确支持时才开启。' },
  thinkingMode: { title: 'Messages thinking mode', note: '仅 anthropic-messages。adaptive / disabled / manual 必须由该 endpoint 明确支持。' },
  clientToolCalling: { title: 'Client tool calling', note: '仅协议兼容并不代表支持工具调用。' },
};
let editorOptions = null;
function loadOptions() {
  if (editorOptions) return Promise.resolve(editorOptions);
  return requestIntent('model.editor.options', {}).then((data) => { editorOptions = data || {}; return editorOptions; });
}


// ---------- Field builders ----------

function block(label, help, control, extra = null) {
  return h('div', { class: 'form-field' }, h('span', { class: 'form-label', text: label }), control, extra, help ? h('p', { class: 'form-help', text: help }) : null);
}

function textField(name, label, help, value, { placeholder = '', inputmode = '', type = 'text' } = {}) {
  return block(label, help, h('input', { class: 'text-input', name, type, value: value ?? '', placeholder, inputmode: inputmode || undefined, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', 'aria-label': label }));
}

function choiceField(name, value) {
  const spec = CHOICES[name];
  return block(spec.title, spec.note || '', h('button', { type: 'button', class: 'choice-field', dataset: { action: 'ed-choice', field: name, value } },
    h('span', { class: 'grow truncate', dataset: { swap: '' }, text: value }), icon('chevronDown', 'ico ico-xs')));
}

function segField(name, label, help, options, value) {
  return block(label, help, syncSegment(h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': label, dataset: { field: name, value } }, options.map(([v, text]) => h('button', {
    type: 'button', role: 'radio', 'aria-checked': v === value ? 'true' : 'false', class: v === value ? 'on' : '', dataset: { action: 'ed-seg', value: v }, text })))));
}

function chipsField(name, label, help, value) {
  // Filled once the native option list arrives (fillChips); until then the stored value stands.
  return block(label, help, h('div', { class: 'chip-set', dataset: { field: name, value: value || '' } }));
}

function fillChips(node, levels) {
  const chosen = new Set(String(node.dataset.value || '').split(',').map((x) => x.trim()).filter(Boolean));
  node.replaceChildren(...levels.map((level) => h('button', {
    type: 'button', class: 'chip-toggle fx-fade', 'aria-pressed': chosen.has(level) ? 'true' : 'false', dataset: { action: 'ed-chip', value: level }, text: level })));
}

function fold(name, open, ...children) {
  return h('div', { class: `fold${open ? '' : ' is-closed'}`, dataset: { fold: name } }, h('div', { class: 'fold-body' }, children));
}

function panelHead(title) {
  return h('header', { class: 'panel-head' },
    iconButton('back', '返回', 'ed-close'),
    h('strong', { class: 'grow truncate', text: title }),
    h('button', { type: 'button', class: 'btn small primary', dataset: { action: 'ed-save' }, text: '保存' }));
}

function errorBanner() {
  return fold('error', false, h('p', { class: 'form-error', role: 'alert' }));
}

// ---------- Provider ----------

function providerOf(id) { return (getState().view?.providers || []).find((p) => p.providerId === id) || null; }

function keyRow(provider) {
  const has = Boolean(provider?.hasCredential);
  return h('div', { class: 'form-field' }, h('span', { class: 'form-label', text: 'API Key' }),
    h('div', { class: 'key-row' },
      h('span', { class: `chip ${has ? 'good' : 'warn'}`, dataset: { keyStatus: '1', swap: '' }, text: has ? '已配置' : '未配置' }),
      h('span', { class: 'grow' }),
      h('button', { type: 'button', class: 'btn small', dataset: { action: 'ed-key' }, text: has ? '更换' : '填写' })),
    h('p', { class: 'form-help', text: '在系统的密码输入框里填写，只保存在 Android 安全存储中，不经过这个页面，也不进入数据库或日志。同一 Provider 下的模型共享它。' }));
}

export function openProviderEditor(providerId = '') {
  const provider = providerId ? providerOf(providerId) : null;
  const node = openPanel('provider', [panelHead(provider ? '编辑 Provider' : '添加 Provider'),
    h('div', { class: 'panel-body' }, errorBanner(),
      textField('displayName', '显示名称', '仅用于本机识别这一组共享账号或 endpoint，不会发送给模型。', provider?.displayName || 'AI Provider', { placeholder: '例如 OpenCode Go' }),
      textField('baseUrl', '共享 Base URL', '同一 Provider 下所有 Model Profile 共享这个 API 根地址和 API Key；协议、鉴权方式和推理参数映射在各 Model Profile 单独配置。不要填写 /chat/completions、/responses 或 /messages。', provider?.baseUrl || '', { placeholder: 'https://example.com/v1', inputmode: 'url', type: 'url' }),
      keyRow(provider))]);
  editor = { kind: 'provider', providerId: provider?.providerId || '', node };
}

async function saveProvider({ thenKey = false } = {}) {
  const values = collect();
  const data = await submit('provider.save', { ...(editor.providerId ? { providerId: editor.providerId } : {}), displayName: values.displayName, baseUrl: values.baseUrl });
  if (!data) return;
  editor.providerId = data.providerId;
  if (thenKey || !data.hasCredential) {
    // A new Provider (or a key change) continues in the native secure field.
    postIntent('provider.credential.edit', { providerId: data.providerId });
    if (thenKey) { editor.node.querySelector('.panel-head strong').textContent = '编辑 Provider'; return; }
  }
  closePanel();
  setNotice({ level: 'info', text: '已保存 Provider' });
}

// ---------- Model Profile ----------

function modelOf(providerId, modelProfileId) {
  return (providerOf(providerId)?.models || []).find((m) => m.modelProfileId === modelProfileId) || null;
}

export function openModelEditor(providerId, modelProfileId = '') {
  const provider = providerOf(providerId);
  if (!provider) return;
  const m = modelProfileId ? modelOf(providerId, modelProfileId) : null;
  const auto = !m || m.configMode === 'auto';
  const node = openPanel('model', [panelHead(`${m ? '编辑模型' : '新增模型'} · ${provider.displayName}`),
    h('div', { class: 'panel-body' }, errorBanner(),
      textField('displayName', '模型显示名称', '仅用于本机界面，可以和实际 Model ID 不同。', m?.displayName || '', { placeholder: '例如 DeepSeek V4 Flash Vision' }),
      textField('modelId', 'Model ID', '实际写入 API 请求 body 的 model 字符串。不要填写 OpenCode 客户端自己的 provider/model 前缀。', m?.modelId || '', { placeholder: 'deepseek-v4-flash-vision-exp' }),
      segField('configMode', '配置方式', '自动：由 Model Catalog 按 endpoint 和 Model ID 解析协议、能力和 Harness。高级：每一项都由你显式声明。', [['auto', '自动配置'], ['manual', '高级配置']], auto ? 'auto' : 'manual'),
      fold('catalog', auto, h('div', { class: 'catalog-card', 'aria-live': 'polite' }, h('span', { class: 'form-label', text: 'Model Catalog 将解析为' }), h('dl', { class: 'facts catalog-facts' }))),
      h('h3', { class: 'form-section', text: '上下文与输出上限' }),
      textField('contextTokens', 'Context tokens', '这个 endpoint 上该模型的总上下文窗口。同名模型在不同平台也可能不同，Catalog 不猜这个值。', String(m?.contextTokens || 32000), { inputmode: 'numeric' }),
      textField('inputTokens', 'Max input tokens', '0 = 自动使用 context − output − 安全余量；平台明确限制输入时填实际值。', String(Math.max(0, m?.inputTokens || 0)), { inputmode: 'numeric' }),
      textField('maxOutputTokens', 'Max output tokens', '本地预留的最大输出，同时映射到 max_tokens 或 max_output_tokens。', String(m?.maxOutputTokens || 4096), { inputmode: 'numeric' }),
      fold('advanced', !auto,
        h('h3', { class: 'form-section', text: '部署声明（高级配置）' }),
        choiceField('protocol', m?.protocol || 'openai-chat-completions'),
        choiceField('authMode', m?.authMode || 'bearer'),
        choiceField('reasoningWire', m?.reasoningWire || 'none'),
        chipsField('reasoningLevels', '支持的推理档位 (reasoningLevels)', '只声明这个部署支持的范围，不选择当前会话的深度。Anthropic Messages effort 只能选 low 到 max。', m?.reasoningLevels || ''),
        segField('inputModalities', '输入模态 (inputModalities)', '只有确认 endpoint 支持图片时才选“文本 + 图片”。', [['text', '仅文本'], ['text,image', '文本 + 图片']], m?.inputModalities === 'text,image' ? 'text,image' : 'text'),
        segField('outputModalities', '输出模态 (outputModalities)', '“文本 + 图片”允许保存并显示返回的图片，不会自动启用图片生成工具。', [['text', '仅文本'], ['text,image', '文本 + 图片']], m?.outputModalities === 'text,image' ? 'text,image' : 'text'),
        textField('toolCapabilities', 'Provider-native Web capabilities', '只声明由 Provider 原生托管的 Web 能力。Responses 可填 web_search；Messages 必须填 endpoint 明确支持的实际版本（如 web_search_20260209）。留空时走 Moyu 自己的联网。', m?.toolCapabilities || '', { placeholder: 'web_search' }),
        choiceField('thinkingMode', m?.thinkingMode || 'provider-default'),
        textField('thinkingBudgetTokens', 'Manual thinking budget', '仅 thinking mode = manual 时使用：至少 1024，且小于 Max output tokens。其它模式填 0。', String(Math.max(0, m?.thinkingBudgetTokens || 0)), { inputmode: 'numeric' }),
        segField('continuationMode', 'Provider-private continuation', 'none：只用与 Provider 无关的可见历史。native：允许回放 Provider 私有状态，只在 endpoint 明确支持时选。', [['none', 'none'], ['native', 'native']], m?.continuationMode === 'native' ? 'native' : 'none'),
        segField('harnessPreset', 'Harness preset', '模型行为档位。自动配置时由 Model Catalog 决定。', [['lean', 'lean'], ['balanced', 'balanced'], ['reinforced', 'reinforced']], m?.harnessPreset || 'balanced'),
        choiceField('clientToolCalling', m?.clientToolCalling || 'none')))]);
  editor = { kind: 'model', providerId, modelProfileId: m?.modelProfileId || '', node, previewTimer: 0, previewFor: null };
  const opened = editor;
  loadOptions().then((options) => {
    if (editor !== opened) return;
    const chips = node.querySelector('.chip-set[data-field="reasoningLevels"]');
    if (chips) fillChips(chips, Array.isArray(options.reasoningLevels) ? options.reasoningLevels : []);
  }).catch(() => {});
  refreshCatalog();
}

let previewSeq = 0;
function refreshCatalog() {
  if (editor?.kind !== 'model') return;
  const modelId = editor.node.querySelector('input[name="modelId"]')?.value.trim() || '';
  if (editor.previewFor === modelId) return;
  editor.previewFor = modelId;
  const seq = ++previewSeq;
  const providerId = editor.providerId;
  requestIntent('model.catalog.preview', { providerId, modelId }).then((data) => {
    if (seq !== previewSeq || editor?.kind !== 'model') return;
    const list = editor.node.querySelector('.catalog-facts');
    if (!list) return;
    const rows = Array.isArray(data?.rows) ? data.rows : [];
    const existing = list.querySelectorAll('dd');
    if (existing.length === rows.length) rows.forEach(([, v], i) => { if (existing[i].textContent !== String(v)) { existing[i].textContent = String(v); swapIn(existing[i]); } });
    else list.replaceChildren(...rows.flatMap(([k, v]) => [h('dt', { text: String(k) }), h('dd', { text: String(v) })]));
  }).catch(() => {});
}

async function saveModel() {
  const v = collect();
  const data = await submit('model.save', { providerId: editor.providerId, ...(editor.modelProfileId ? { modelProfileId: editor.modelProfileId } : {}), ...v });
  if (!data) return;
  closePanel();
  setNotice({ level: 'info', text: '已保存模型' });
}

// ---------- Shared form plumbing ----------

function collect() {
  const values = {};
  for (const input of editor.node.querySelectorAll('input[name]')) values[input.name] = input.value.trim();
  for (const node of editor.node.querySelectorAll('[data-field]')) {
    if (node.classList.contains('chip-set')) values[node.dataset.field] = node.querySelector('.chip-toggle') ? [...node.querySelectorAll('[aria-pressed="true"]')].map((chip) => chip.dataset.value).join(',') : node.dataset.value || '';
    else values[node.dataset.field] = node.dataset.value || '';
  }
  for (const node of editor.node.querySelectorAll('.choice-field')) values[node.dataset.field] = node.dataset.value || '';
  return values;
}

function showError(message) {
  const banner = editor?.node.querySelector('[data-fold="error"]');
  if (!banner) return;
  const text = banner.querySelector('.form-error');
  text.textContent = message || '配置无效，请检查填写内容';
  if (banner.classList.contains('is-closed')) banner.classList.remove('is-closed'); else swapIn(text);
  editor.node.scrollTo({ top: 0, behavior: 'smooth' });
}

async function submit(type, payload) {
  const saveButton = editor.node.querySelector('[data-action="ed-save"]');
  saveButton.disabled = true;
  saveButton.textContent = '保存中…';
  try {
    return await requestIntent(type, payload);
  } catch (error) {
    showError(String(error?.message || error || ''));
    return null;
  } finally {
    if (saveButton.isConnected) { saveButton.disabled = false; saveButton.textContent = '保存'; }
  }
}

function setFold(name, open) { editor.node.querySelector(`[data-fold="${name}"]`)?.classList.toggle('is-closed', !open); }

// ---------- Events (scoped to the panel) ----------

document.addEventListener('click', (event) => {
  const target = event.target.closest?.('#panel-root [data-action]');
  if (!target || !editor || !panelOpen()) return;
  const action = target.dataset.action;
  if (action === 'ed-close') closePanel();
  else if (action === 'ed-save') { if (editor.kind === 'provider') saveProvider(); else saveModel(); }
  else if (action === 'ed-key') saveProvider({ thenKey: true });
  else if (action === 'ed-seg') {
    const group = target.parentElement;
    for (const item of group.children) { const on = item === target; item.classList.toggle('on', on); item.setAttribute('aria-checked', on ? 'true' : 'false'); }
    group.dataset.value = target.dataset.value;
    syncSegment(group);
    if (group.dataset.field === 'configMode') { const auto = target.dataset.value === 'auto'; setFold('catalog', auto); setFold('advanced', !auto); }
  }
  else if (action === 'ed-chip') target.setAttribute('aria-pressed', target.getAttribute('aria-pressed') === 'true' ? 'false' : 'true');
  else if (action === 'ed-choice') {
    const field = target.dataset.field;
    const spec = CHOICES[field];
    loadOptions().then((options) => {
      const list = (Array.isArray(options[field]) ? options[field] : []).map((option) => ({ value: option.value, label: option.value, hint: option.hint || '' }));
      openChoice({ title: spec.title, note: spec.note || '', options: list, value: target.dataset.value, onPick: (value) => {
        target.dataset.value = value;
        const label = target.querySelector('[data-swap]');
        if (label && label.textContent !== value) { label.textContent = value; swapIn(label); }
      } });
    }).catch((error) => setNotice({ level: 'error', text: `无法读取可选项：${error?.message || error}` }));
  }
});

document.addEventListener('input', (event) => {
  if (!editor || editor.kind !== 'model' || event.target?.name !== 'modelId' || !event.target.closest('#panel-root')) return;
  clearTimeout(editor.previewTimer);
  editor.previewTimer = setTimeout(refreshCatalog, 300);
});

// The native key dialog reports back through the view: keep the key status current.
subscribe(() => {
  if (!editor || editor.kind !== 'provider' || !editor.providerId || !panelOpen()) return;
  const has = Boolean(providerOf(editor.providerId)?.hasCredential);
  const status = editor.node.querySelector('[data-key-status]');
  if (!status || status.classList.contains(has ? 'good' : 'warn')) return;
  status.classList.toggle('good', has); status.classList.toggle('warn', !has);
  status.textContent = has ? '已配置' : '未配置';
  swapIn(status);
  const action = editor.node.querySelector('[data-action="ed-key"]');
  if (action) action.textContent = has ? '更换' : '填写';
});
