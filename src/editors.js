// Account / route / model editors: full-screen panels in the WebView. The API Key is the one exception:
// it is typed into a native password field opened through `provider.credential.edit`, so the key never
// enters the page. Everything the forms offer (presets, protocols, dialect knobs, field vocabularies)
// comes from native `model.editor.options`; every value a model resolves to comes from native
// `model.preview`, with the source of each value. The page never decides a capability.
import { postIntent, requestIntent } from './bridge.js';
import { button, h, icon, iconButton, syncSegment } from './dom.js';
import { morph, removeNode, swapIn } from './morph.js';
import { closePanel, openChoice, openConfirmDialog, openPanel, openTextDialog, panelOpen } from './overlays.js';
import { getState, setNotice, subscribe } from './state.js';

let editor = null;
let editorOptions = null;
let tmpSeq = 0;

const PROTOCOL_SHORT = { 'openai-chat-completions': 'Chat', 'openai-responses': 'Responses', 'anthropic-messages': 'Messages' };
const RETENTION = { none: ['不保留数据', 'good'], '30d': ['保留 30 天', 'quiet'], training: ['可能用于训练', 'warn'] };

function loadOptions() {
  if (editorOptions) return Promise.resolve(editorOptions);
  return requestIntent('model.editor.options', {}).then((data) => { editorOptions = data || {}; return editorOptions; });
}

function optionsFor(name, protocol) {
  const list = Array.isArray(editorOptions?.[name]) ? editorOptions[name] : [];
  return list.filter((o) => !o.protocols || !protocol || o.protocols.includes(protocol));
}

function providerOf(id) { return (getState().view?.providers || []).find((p) => p.providerId === id) || null; }
function presetOf(id) { return (editorOptions?.presets || []).find((p) => p.id === id) || null; }

// ---------- Shared field builders ----------

function block(label, help, control, extra = null) {
  return h('div', { class: 'form-field' }, h('span', { class: 'form-label', text: label }), control, extra, help ? h('p', { class: 'form-help', text: help }) : null);
}

function textInput(name, value, { placeholder = '', inputmode = '', type = 'text', label = '' } = {}) {
  return h('input', { class: 'text-input', name, type, value: value ?? '', placeholder, inputmode: inputmode || undefined, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', 'aria-label': label || name });
}

function textField(name, label, help, value, opts = {}) { return block(label, help, textInput(name, value, { ...opts, label })); }

function choiceButton(action, data, text) {
  return h('button', { type: 'button', class: 'choice-field', dataset: { action, ...data } },
    h('span', { class: 'grow truncate', dataset: { swap: '' }, text }), icon('chevronDown', 'ico ico-xs'));
}

function segmented(name, options, value, label) {
  return syncSegment(h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': label, dataset: { field: name, value } }, options.map(([v, text]) => h('button', {
    type: 'button', role: 'radio', 'aria-checked': v === value ? 'true' : 'false', class: v === value ? 'on' : '', dataset: { action: 'ed-seg', value: v }, text }))));
}

function fold(name, open, ...children) {
  return h('div', { class: `fold${open ? '' : ' is-closed'}`, dataset: { fold: name } }, h('div', { class: 'fold-body' }, children));
}

function panelHead(title, saveLabel = '保存') {
  return h('header', { class: 'panel-head' },
    iconButton('back', '返回', 'ed-close'),
    h('strong', { class: 'grow truncate', text: title }),
    saveLabel ? h('button', { type: 'button', class: 'btn small primary', dataset: { action: 'ed-save' }, text: saveLabel }) : null);
}

function errorBanner() { return fold('error', false, h('p', { class: 'form-error', role: 'alert' })); }

function setText(node, text) {
  if (!node || node.textContent === text) return;
  node.textContent = text;
  swapIn(node);
}

// ---------- Accounts and routes ----------

export function openProviderEditor(providerId = '') {
  loadOptions().then(() => {
    const provider = providerId ? providerOf(providerId) : null;
    if (providerId && !provider) return;
    if (!provider) openPresetPicker(); else openAccountForm(provider, presetOf(provider.presetId));
  }).catch((error) => setNotice({ level: 'error', text: `无法读取服务商预设：${error?.message || error}` }));
}

function openPresetPicker() {
  const groups = [['vendor', '模型厂商'], ['gateway', '服务商'], ['custom', '自定义']];
  const node = openPanel('provider', [panelHead('添加账号', null),
    h('div', { class: 'panel-body' },
      h('p', { class: 'page-note', text: '选择服务商后会自动填好各协议的地址、鉴权方式和协议细节；之后仍可逐项修改。API Key 稍后在系统密码框里填写。' }),
      groups.map(([kind, title]) => {
        const presets = (editorOptions.presets || []).filter((p) => p.kind === kind);
        return presets.length ? [h('h3', { class: 'form-section', text: title }),
          h('div', { class: 'preset-list' }, presets.map((p) => h('button', { type: 'button', class: 'preset-row', dataset: { action: 'ed-preset', presetId: p.id } },
            h('span', { class: 'grow' }, h('strong', { text: p.name }), p.note ? h('small', { class: 'choice-hint', text: p.note }) : null),
            h('span', { class: 'chip quiet', text: p.routes.map((r) => PROTOCOL_SHORT[r.protocol] || r.protocol).join(' · ') }))))] : null;
      }))]);
  editor = { kind: 'preset', node };
}

function routeDraftsFromPreset(preset) {
  return (preset?.routes || []).map((r) => ({ key: `new-${++tmpSeq}`, presetRouteId: r.id, label: r.label, protocol: r.protocol,
    baseUrl: r.baseUrl, authMode: r.authMode, dialect: { ...(r.dialect || {}) }, headers: {}, presetBaseUrl: r.baseUrl }));
}

function openAccountForm(provider, preset) {
  const routes = provider
    ? provider.routes.map((r) => ({ ...r, key: r.routeId, headers: { ...(r.headers || {}) }, dialect: { ...(r.dialect || {}) },
      presetBaseUrl: preset?.routes.find((x) => x.id === r.presetRouteId)?.baseUrl || '' }))
    : routeDraftsFromPreset(preset);
  const node = openPanel('provider', [panelHead(provider ? '编辑账号' : `添加 · ${preset?.name || '账号'}`),
    h('div', { class: 'panel-body' }, errorBanner(),
      presetCard(preset),
      textField('displayName', '显示名称', '仅用于本机识别这个账号，不会发送给模型。', provider?.displayName || preset?.name || '', { placeholder: '例如 OpenCode Go' }),
      h('h3', { class: 'form-section', text: '路线' }),
      h('p', { class: 'form-help', text: '每条路线是一个协议 + 地址；同一账号的所有路线共用一把 API Key。OpenAI 格式的地址含 /v1，Anthropic 格式的地址不含 /v1。' }),
      h('div', { class: 'route-list', dataset: { routes: '' } }, routes.map((r) => routeCard(r, preset))),
      h('div', { class: 'row' }, h('span', { class: 'grow' }), button('添加路线', 'ed-route-add', {}, 'btn small')),
      h('h3', { class: 'form-section', text: 'API Key' }),
      keyRow(provider),
      provider ? h('div', { class: 'danger-zone' }, button('删除这个账号', 'ed-account-delete', {}, 'btn small danger')) : null)]);
  editor = { kind: 'provider', providerId: provider?.providerId || '', presetId: preset?.id || provider?.presetId || 'custom-openai', node, preset };
}

function presetCard(preset) {
  if (!preset) return null;
  return h('div', { class: 'catalog-card' },
    h('div', { class: 'row' }, h('strong', { class: 'grow', text: preset.name }), h('span', { class: 'chip quiet', text: { vendor: '模型厂商', gateway: '服务商', custom: '自定义' }[preset.kind] || preset.kind })),
    preset.note ? h('p', { class: 'form-help', text: preset.note }) : null,
    preset.console ? h('div', { class: 'row' }, h('span', { class: 'grow' }), button('打开控制台', 'ed-open-url', { url: preset.console }, 'btn small ghost')) : null);
}

function routeCard(r, preset) {
  const protocol = r.protocol;
  const reviewed = Boolean(r.presetRouteId) && r.baseUrl === r.presetBaseUrl;
  const dialect = r.dialect || {};
  const advanced = [];
  if (protocol === 'openai-chat-completions') {
    advanced.push(block('输出上限字段', '未声明输出上限的模型不会发送这个字段。', choiceButton('ed-dialect', { field: 'outputCap', value: dialect.outputCap || 'max_tokens' }, dialect.outputCap || 'max_tokens')));
    advanced.push(block('流式用量', '请求在最后一个分块里返回 token 用量（stream_options.include_usage）。', segmented('streamUsage', [['true', '请求'], ['false', '不请求']], String(dialect.streamUsage !== false), '流式用量')));
    advanced.push(block('思考开关', '这条路线怎样开关思考。', choiceButton('ed-dialect', { field: 'thinkingParam', value: dialect.thinkingParam || 'none' }, dialect.thinkingParam || 'none')));
    advanced.push(block('推理内容回传', 'DeepSeek 在带工具时、Kimi 思考模型在每轮都要求把推理内容传回去。', choiceButton('ed-dialect', { field: 'reasoningReplay', value: dialect.reasoningReplay || 'never' }, dialect.reasoningReplay || 'never')));
  } else if (protocol === 'openai-responses') {
    advanced.push(block('输出上限字段', '', choiceButton('ed-dialect', { field: 'outputCap', value: dialect.outputCap || 'max_output_tokens' }, dialect.outputCap || 'max_output_tokens')));
    advanced.push(block('服务端存储（store）', '默认不让服务端保存回答；续接用加密推理内容，不依赖服务端存储。', segmented('store', [['false', '不存储'], ['true', '存储']], String(Boolean(dialect.store)), '服务端存储')));
  } else if (protocol === 'anthropic-messages') {
    advanced.push(block('自动缓存', '在请求顶层加 cache_control，让服务端自动缓存对话前缀。只在端点支持时开启。', segmented('autoCache', [['true', '开启'], ['false', '关闭']], String(Boolean(dialect.autoCache)), '自动缓存')));
  }
  const headers = Object.entries(r.headers || {});
  const suggestions = (preset?.suggestedHeaders || []).filter((name) => !(name in (r.headers || {})));
  return h('article', { class: 'route-card', dataset: { routeKey: r.key, routeId: r.routeId || '', protocol, presetRouteId: r.presetRouteId || '', presetBaseUrl: r.presetBaseUrl || '' } },
    h('div', { class: 'row' },
      h('span', { class: 'chip', text: PROTOCOL_SHORT[protocol] || protocol }),
      h('span', { class: `chip ${reviewed ? 'good' : 'quiet'}`, dataset: { reviewChip: '1', swap: '' }, text: reviewed ? '预设地址' : '自定义地址' }),
      h('span', { class: 'grow' }),
      iconButton('trash', '删除路线', 'ed-route-delete', { routeKey: r.key })),
    r.routeId && r.hostConfirmed === false ? h('div', { class: 'notice-row warn' },
      h('span', { class: 'grow', text: `API Key 还没有确认发往 ${r.host}，确认前不会发送请求。` }),
      button('确认', 'route-host-confirm', { routeId: r.routeId, host: r.host }, 'btn small')) : null,
    textField('label', '名称', '', r.label || '', { placeholder: PROTOCOL_SHORT[protocol] || protocol }),
    textField('baseUrl', 'Base URL', protocol === 'anthropic-messages' ? '不含 /v1；请求发往 …/v1/messages。' : '含版本段（如 /v1）；请求发往 …/chat/completions 或 …/responses。',
      r.baseUrl || '', { placeholder: protocol === 'anthropic-messages' ? 'https://example.com' : 'https://example.com/v1', inputmode: 'url', type: 'url' }),
    block('鉴权方式', '', choiceButton('ed-auth', { value: r.authMode || '' }, r.authMode || '')),
    h('button', { type: 'button', class: 'fold-toggle', dataset: { action: 'ed-fold', target: `adv-${r.key}` } }, '协议细节与附加请求头', icon('chevronDown', 'ico ico-xs')),
    fold(`adv-${r.key}`, false, advanced,
      block('附加请求头', '只放非机密信息（组织、项目、beta 功能）。含 key、token、auth 等字样的请求头会被拒绝。',
        h('div', { class: 'header-list', dataset: { headers: '' } }, headers.map(([name, value]) => headerRow(name, value))),
        h('div', { class: 'row wrap' }, suggestions.map((name) => button(`+ ${name}`, 'ed-header-add', { name }, 'btn small ghost')),
          h('span', { class: 'grow' }), button('添加', 'ed-header-add', {}, 'btn small')))));
}

function headerRow(name, value) {
  return h('div', { class: 'header-row' },
    textInput('headerName', name, { placeholder: 'Header-Name', label: '请求头名称' }),
    textInput('headerValue', value, { placeholder: 'value', label: '请求头的值' }),
    iconButton('close', '删除请求头', 'ed-header-remove'));
}

function keyRow(provider) {
  const has = Boolean(provider?.hasCredential);
  return h('div', { class: 'form-field' },
    h('div', { class: 'key-row' },
      h('span', { class: `chip ${has ? 'good' : 'warn'}`, dataset: { keyStatus: '1', swap: '' }, text: has ? '已配置' : '未配置' }),
      h('span', { class: 'grow' }),
      h('button', { type: 'button', class: 'btn small', dataset: { action: 'ed-key' }, text: has ? '更换' : '填写' })),
    h('p', { class: 'form-help', text: '在系统密码框里填写，只保存在 Android 安全存储中，不经过这个页面，也不进入数据库或日志。只会发往这个账号已确认的域名。' }));
}

function collectRoutes() {
  return [...editor.node.querySelectorAll('.route-card:not([data-leaving])')].map((card) => {
    const field = (name) => card.querySelector(`input[name="${name}"]`)?.value.trim() || '';
    const dialect = {};
    for (const node of card.querySelectorAll('[data-action="ed-dialect"]')) dialect[node.dataset.field] = node.dataset.value;
    for (const node of card.querySelectorAll('.segmented[data-field]')) dialect[node.dataset.field] = node.dataset.value === 'true';
    const headers = {};
    for (const row of card.querySelectorAll('.header-row:not([data-leaving])')) {
      const name = row.querySelector('input[name="headerName"]').value.trim();
      const value = row.querySelector('input[name="headerValue"]').value.trim();
      if (name || value) headers[name] = value;
    }
    const out = { label: field('label'), protocol: card.dataset.protocol, baseUrl: field('baseUrl'),
      authMode: card.querySelector('[data-action="ed-auth"]')?.dataset.value || '', dialect, headers };
    if (card.dataset.routeId) out.routeId = card.dataset.routeId;
    else if (card.dataset.presetRouteId) out.presetRouteId = card.dataset.presetRouteId;
    return out;
  });
}

async function saveAccount({ thenKey = false } = {}) {
  const payload = { displayName: editor.node.querySelector('input[name="displayName"]')?.value.trim() || '', routes: collectRoutes() };
  if (editor.providerId) payload.providerId = editor.providerId; else payload.presetId = editor.presetId;
  const data = await submit('provider.save', payload);
  if (!data) return;
  const created = !editor.providerId;
  editor.providerId = data.providerId;
  if (thenKey || !data.hasCredential) {
    postIntent('provider.credential.edit', { providerId: data.providerId });
    if (thenKey || created) {
      // Stay in the form so the key status updates in place; saved routes now have ids.
      const provider = providerOf(data.providerId);
      if (provider) openAccountForm(provider, presetOf(provider.presetId));
      return;
    }
  }
  closePanel();
  setNotice({ level: 'info', text: '已保存账号' });
}

function addRoute(protocol) {
  const list = editor.node.querySelector('[data-routes]');
  const authMode = optionsFor('authMode')[protocol === 'anthropic-messages' ? 1 : 0]?.value || '';
  const card = routeCard({ key: `new-${++tmpSeq}`, label: PROTOCOL_SHORT[protocol] || protocol, protocol, baseUrl: '', authMode, dialect: {}, headers: {} }, editor.preset);
  card.classList.add('fx-rise');
  list.append(card);
  card.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function deleteRoute(card) {
  const routeId = card.dataset.routeId;
  const models = routeId ? (providerOf(editor.providerId)?.models || []).filter((m) => m.routeId === routeId) : [];
  const remove = () => { card.dataset.exit = 'collapse'; removeNode(card); };
  if (editor.node.querySelectorAll('.route-card:not([data-leaving])').length <= 1) { showError('账号至少需要一条路线'); return; }
  if (!models.length) { remove(); return; }
  openConfirmDialog({ title: '删除路线', danger: true, confirmText: '删除',
    message: `这条路线下的 ${models.length} 个模型会在保存时一起删除：${models.map((m) => m.displayName).join('、')}。`, onConfirm: remove });
}

// ---------- Models ----------

export function openModelEditor(providerId, modelProfileId = '') {
  loadOptions().then(() => {
    const provider = providerOf(providerId);
    if (!provider) return;
    if (!provider.routes?.length) { setNotice({ level: 'warning', text: '这个账号还没有路线' }); return; }
    const m = modelProfileId ? (provider.models || []).find((x) => x.modelProfileId === modelProfileId) : null;
    const route = provider.routes.find((r) => r.routeId === m?.routeId) || provider.routes[0];
    const node = openPanel('model', [panelHead(`${m ? '编辑模型' : '添加模型'} · ${provider.displayName}`),
      h('div', { class: 'panel-body' }, errorBanner(),
        block('路线', '这个模型通过哪条路线（协议 + 地址）访问。', choiceButton('ed-route', { value: route.routeId }, routeLabel(route))),
        block('Model ID', '写入请求 body 的 model 字符串。', h('div', { class: 'row' },
          h('div', { class: 'grow' }, textInput('modelId', m?.modelId || '', { placeholder: 'model-id', label: 'Model ID' })),
          h('button', { type: 'button', class: 'btn small', dataset: { action: 'ed-catalog' }, text: '从目录选' }))),
        textField('displayName', '显示名称', '留空时使用目录名称或 Model ID。', m?.displayName || '', { placeholder: '可选' }),
        m ? block('启用', '停用的模型保留配置，但不会出现在模型选择里。', segmented('enabled', [['true', '启用'], ['false', '停用']], String(m.enabled !== false), '启用')) : null,
        h('h3', { class: 'form-section', text: '能力与参数' }),
        h('p', { class: 'form-help', text: '每一项都标明来源：默认（保守值）、目录（已审核的官方或服务商资料）、手动（你填写的值）。没有声明的能力不会被猜测。点一项可以修改。' }),
        h('div', { class: 'catalog-note', dataset: { catalogNote: '' } }),
        h('div', { class: 'cap-list', dataset: { caps: '' } }),
        m ? h('div', { class: 'danger-zone' }, button('删除这个模型', 'ed-model-delete', {}, 'btn small danger')) : null)]);
    editor = { kind: 'model', providerId, modelProfileId: m?.modelProfileId || '', routeId: route.routeId, protocol: route.protocol,
      overrides: { ...(m?.overrides || {}) }, preview: null, previewTimer: 0, node };
    refreshPreview(0);
  }).catch((error) => setNotice({ level: 'error', text: `无法读取可选项：${error?.message || error}` }));
}

function routeLabel(route) { return `${route.label} · ${PROTOCOL_SHORT[route.protocol] || route.protocol} · ${route.host}`; }

let previewSeq = 0;
function refreshPreview(delay = 250) {
  if (editor?.kind !== 'model') return;
  clearTimeout(editor.previewTimer);
  editor.previewTimer = setTimeout(() => {
    const opened = editor;
    const seq = ++previewSeq;
    const payload = { providerId: opened.providerId, routeId: opened.routeId, modelId: modelIdValue(), overrides: opened.overrides };
    if (opened.modelProfileId) payload.modelProfileId = opened.modelProfileId;
    requestIntent('model.preview', payload).then((data) => {
      if (seq !== previewSeq || editor !== opened) return;
      opened.preview = data || null;
      paintCapabilities();
    }).catch(() => {});
  }, delay);
}

function modelIdValue() { return editor?.node.querySelector('input[name="modelId"]')?.value.trim() || ''; }

function sourceChip(source, verified) {
  const names = editorOptions?.sources || {};
  if (source === 'catalog') return h('span', { class: `chip src ${verified ? 'good' : 'quiet'}`, text: verified ? (names.catalog || '目录') : `${names.catalog || '目录'} · 未实测` });
  if (source === 'manual') return h('span', { class: 'chip src accent', text: names.manual || '手动' });
  return h('span', { class: 'chip src quiet', text: names[source] || source });
}

function formatValue(field, value) {
  if (field === 'serviceTier' && !value) return '不发送';
  if (value === undefined || value === null || value === '') return '—';
  if (['contextTokens', 'inputTokens', 'maxOutputTokens', 'thinkingBudgetTokens'].includes(field)) {
    const n = Number(value);
    if (field === 'inputTokens' && !n) return '自动';
    if (field === 'thinkingBudgetTokens' && !n) return '—';
    return n.toLocaleString('en-US');
  }
  if (field === 'reasoningLevels') return String(value).split(',').filter(Boolean).join(' / ') || '—';
  if (field === 'pricing') return pricingText(value);
  return String(value);
}

export function pricingText(p) {
  if (!p || p.input === undefined) return '未知';
  const cur = p.currency === 'CNY' ? '¥' : '$';
  let text = `${cur}${p.input} / ${cur}${p.output}`;
  if (p.cacheRead !== undefined) text += ` · 缓存 ${cur}${p.cacheRead}`;
  if (p.tiers?.length) text += ' · 分档';
  if (p.offPeak) text += ' · 闲时折扣';
  return `${text}（每百万 token）`;
}

function paintCapabilities() {
  const preview = editor?.preview;
  if (!preview) return;
  const verified = Boolean(preview.catalog?.verified);
  const rows = (preview.fields || []).map((f) => h('button', { type: 'button', class: `cap-row${f.source === 'manual' ? ' manual' : ''}`, dataset: { key: f.field, action: 'ed-cap', field: f.field } },
    h('span', { class: 'cap-label', text: f.label }),
    h('span', { class: 'cap-value grow truncate', dataset: { swap: '' }, text: formatValue(f.field, f.value) }),
    sourceChip(f.source, verified)));
  morph(editor.node.querySelector('[data-caps]'), h('div', { class: 'cap-list', dataset: { caps: '' } }, rows));
  const note = editor.node.querySelector('[data-catalog-note]');
  const c = preview.catalog;
  const retention = c?.retention ? RETENTION[c.retention] : null;
  morph(note, h('div', { class: 'catalog-note', dataset: { catalogNote: '' } }, c
    ? h('div', { class: 'row wrap' }, h('span', { class: 'chip good', text: `目录：${c.name}` }), c.verified ? null : h('span', { class: 'chip quiet', text: '未实测' }),
      retention ? h('span', { class: `chip ${retention[1]}`, text: retention[0] }) : null)
    : h('p', { class: 'form-help', text: modelIdValue() ? '目录里没有这条路线上的这个模型：能力从保守默认值开始，需要的项请手动声明。' : '' })));
  const banner = editor.node.querySelector('[data-fold="error"]');
  const text = banner?.querySelector('.form-error');
  if (preview.error && modelIdValue()) { setText(text, preview.error); banner.classList.remove('is-closed'); }
  else banner?.classList.add('is-closed');
}

const INT_FIELDS = new Set(['contextTokens', 'inputTokens', 'maxOutputTokens', 'thinkingBudgetTokens']);
const CHOICE_FIELDS = new Set(['reasoningWire', 'thinkingMode', 'clientToolCalling', 'continuationMode', 'harnessPreset', 'serviceTier']);

function editCapability(field) {
  const row = (editor.preview?.fields || []).find((f) => f.field === field);
  if (!row) return;
  const set = (value) => { editor.overrides[field] = value; refreshPreview(0); };
  const reset = editor.overrides[field] !== undefined ? () => { delete editor.overrides[field]; refreshPreview(0); } : null;
  if (CHOICE_FIELDS.has(field)) {
    const options = optionsFor(field, editor.protocol).map((o) => ({ value: o.value, label: o.value || '不发送', hint: o.hint || '' }));
    if (reset) options.unshift({ value: '__reset__', label: '恢复推荐值', hint: '删除手动值，回到目录或默认值' });
    openChoice({ title: row.label, options, value: String(row.value ?? ''), onPick: (value) => (value === '__reset__' ? reset() : set(value)) });
  } else if (field === 'inputModalities' || field === 'outputModalities') {
    const options = [{ value: 'text', label: '仅文本', hint: '' }, { value: 'text,image', label: '文本 + 图片', hint: field === 'inputModalities' ? '只在确认端点接受图片时选' : '允许保存并显示返回的图片' }];
    if (reset) options.unshift({ value: '__reset__', label: '恢复推荐值', hint: '' });
    openChoice({ title: row.label, options, value: String(row.value), onPick: (value) => (value === '__reset__' ? reset() : set(value)) });
  } else if (INT_FIELDS.has(field)) {
    textOverride(row, String(row.value ?? ''), (value) => set(value), reset, 'numeric', field === 'inputTokens' ? '0 = 自动（上下文 − 输出 − 安全余量）' : '');
  } else if (field === 'reasoningLevels') {
    textOverride(row, String(row.value ?? ''), (value) => set(value), reset, '', `用逗号分隔，可选：${(editorOptions.reasoningLevels || []).join(' / ')}。需要先设置推理参数。`);
  } else if (field === 'toolCapabilities') {
    textOverride(row, String(row.value ?? ''), (value) => set(value), reset, '', 'Responses 可填 web_search；Messages 填具体版本，如 web_search_20260318,web_fetch_20260318。');
  } else if (field === 'pricing') {
    const p = row.value || {};
    const text = p.input === undefined ? 'currency=USD, input=, output=, cacheRead=, cacheWrite=' : `currency=${p.currency || 'USD'}, input=${p.input}, output=${p.output}${p.cacheRead !== undefined ? `, cacheRead=${p.cacheRead}` : ''}${p.cacheWrite !== undefined ? `, cacheWrite=${p.cacheWrite}` : ''}`;
    textOverride(row, text, (value) => {
      const out = {};
      for (const part of value.split(',')) {
        const [k, v] = part.split('=').map((x) => x.trim());
        if (!k || v === undefined || v === '') continue;
        out[k] = k === 'currency' ? v.toUpperCase() : Number(v);
      }
      set(out);
    }, reset, '', '每百万 token 的价格，币种 USD 或 CNY。手动价格不分档、不含闲时折扣。');
  }
}

function textOverride(row, value, onSet, reset, inputmode, note) {
  openTextDialog({ title: row.label, value, inputmode, note: `${note}${reset ? '（清除手动值请用下方“恢复推荐值”）' : ''}`.trim(), onConfirm: onSet });
  if (reset) {
    const card = document.querySelector('.modal-card .modal-actions');
    card?.prepend(h('button', { type: 'button', class: 'btn ghost', dataset: { action: 'ed-cap-reset', field: row.field }, text: '恢复推荐值' }));
  }
}

async function saveModel() {
  const payload = { providerId: editor.providerId, routeId: editor.routeId, modelId: modelIdValue(),
    displayName: editor.node.querySelector('input[name="displayName"]')?.value.trim() || '', overrides: editor.overrides };
  if (editor.modelProfileId) payload.modelProfileId = editor.modelProfileId;
  const enabled = editor.node.querySelector('.segmented[data-field="enabled"]');
  if (enabled) payload.enabled = enabled.dataset.value === 'true';
  const data = await submit('model.save', payload);
  if (!data) return;
  closePanel();
  setNotice({ level: 'info', text: '已保存模型' });
}

function pickFromCatalog() {
  requestIntent('route.catalog', { routeId: editor.routeId }).then((data) => {
    const models = data?.models || [];
    if (!models.length) { setNotice({ level: 'info', text: '这条路线没有已审核的目录（自定义地址或自定义服务商）。请直接填写 Model ID。' }); return; }
    openChoice({ title: '从目录选择', note: `目录版本 ${data.version}。价格为每百万 token。`, value: modelIdValue(),
      options: models.map((m) => ({ value: m.modelId, label: `${m.name}${m.added ? '（已添加）' : ''}`,
        hint: [m.modelId, m.pricing ? pricingText(m.pricing) : '', RETENTION[m.retention]?.[0] || '', m.verified ? '' : '未实测'].filter(Boolean).join(' · ') })),
      onPick: (value) => {
        const input = editor.node.querySelector('input[name="modelId"]');
        if (input) { input.value = value; refreshPreview(0); }
      } });
  }).catch((error) => setNotice({ level: 'error', text: `无法读取目录：${error?.message || error}` }));
}

// ---------- Shared plumbing ----------

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
  if (saveButton) { saveButton.disabled = true; saveButton.textContent = '保存中…'; }
  try {
    return await requestIntent(type, payload);
  } catch (error) {
    showError(String(error?.message || error || ''));
    return null;
  } finally {
    if (saveButton?.isConnected) { saveButton.disabled = false; saveButton.textContent = '保存'; }
  }
}

function setChoice(target, value, label = value) {
  target.dataset.value = value;
  setText(target.querySelector('[data-swap]'), label);
}

// ---------- Events (scoped to the panel) ----------

document.addEventListener('click', (event) => {
  const reset = event.target.closest?.('.modal-card [data-action="ed-cap-reset"]');
  if (reset && editor?.kind === 'model') {
    delete editor.overrides[reset.dataset.field];
    reset.closest('.modal-backdrop')?.querySelector('[data-action="modal-cancel"]')?.click();
    refreshPreview(0);
    return;
  }
  const target = event.target.closest?.('#panel-root [data-action]');
  if (!target || !editor || !panelOpen()) return;
  const action = target.dataset.action;
  if (action === 'ed-close') closePanel();
  else if (action === 'ed-save') { if (editor.kind === 'provider') saveAccount(); else if (editor.kind === 'model') saveModel(); }
  else if (action === 'ed-preset') openAccountForm(null, presetOf(target.dataset.presetId));
  else if (action === 'ed-open-url') postIntent('external.open', { url: target.dataset.url });
  else if (action === 'ed-key') saveAccount({ thenKey: true });
  else if (action === 'ed-route-add') {
    openChoice({ title: '添加路线', note: '选择这条路线的协议；创建后不能更改。', value: '',
      options: optionsFor('protocol').map((o) => ({ value: o.value, label: PROTOCOL_SHORT[o.value] || o.value, hint: o.hint })), onPick: addRoute });
  }
  else if (action === 'ed-route-delete') deleteRoute(target.closest('.route-card'));
  else if (action === 'ed-account-delete') {
    const provider = providerOf(editor.providerId);
    openConfirmDialog({ title: '删除账号', danger: true, confirmText: '删除',
      message: `「${provider?.displayName || ''}」的路线、${provider?.models?.length || 0} 个模型和 API Key 会被删除。已有会话保留，但要切换到其它模型才能继续。`,
      onConfirm: () => { postIntent('provider.delete', { providerId: editor.providerId }); closePanel(); } });
  }
  else if (action === 'ed-fold') {
    const node = editor.node.querySelector(`.fold[data-fold="${target.dataset.target}"]`);
    const open = node?.classList.toggle('is-closed') === false;
    target.classList.toggle('open', open);
  }
  else if (action === 'ed-auth') {
    openChoice({ title: '鉴权方式', note: '这条路线把 API Key 放在哪个请求头里。', value: target.dataset.value,
      options: optionsFor('authMode').map((o) => ({ value: o.value, label: o.value, hint: o.hint })), onPick: (value) => setChoice(target, value) });
  }
  else if (action === 'ed-dialect') {
    const protocol = target.closest('.route-card')?.dataset.protocol;
    const field = target.dataset.field;
    openChoice({ title: { outputCap: '输出上限字段', thinkingParam: '思考开关', reasoningReplay: '推理内容回传' }[field] || field, value: target.dataset.value,
      options: optionsFor(field, protocol).map((o) => ({ value: o.value, label: o.value, hint: o.hint })), onPick: (value) => setChoice(target, value) });
  }
  else if (action === 'ed-header-add') {
    const list = target.closest('.route-card')?.querySelector('[data-headers]');
    const row = headerRow(target.dataset.name || '', '');
    row.classList.add('fx-rise');
    list?.append(row);
    if (target.dataset.name) target.remove();
    row.querySelector(target.dataset.name ? 'input[name="headerValue"]' : 'input[name="headerName"]')?.focus();
  }
  else if (action === 'ed-header-remove') { const row = target.closest('.header-row'); if (row) { row.dataset.exit = 'collapse'; removeNode(row); } }
  else if (action === 'ed-seg') {
    const group = target.parentElement;
    for (const item of group.children) { const on = item === target; item.classList.toggle('on', on); item.setAttribute('aria-checked', on ? 'true' : 'false'); }
    group.dataset.value = target.dataset.value;
    syncSegment(group);
  }
  else if (action === 'ed-route') {
    const provider = providerOf(editor.providerId);
    openChoice({ title: '路线', value: editor.routeId,
      options: (provider?.routes || []).map((r) => ({ value: r.routeId, label: `${r.label} · ${PROTOCOL_SHORT[r.protocol] || r.protocol}`, hint: r.baseUrl })),
      onPick: (value) => {
        const route = provider.routes.find((r) => r.routeId === value);
        if (!route) return;
        editor.routeId = route.routeId; editor.protocol = route.protocol;
        setChoice(target, route.routeId, routeLabel(route));
        refreshPreview(0);
      } });
  }
  else if (action === 'ed-catalog') pickFromCatalog();
  else if (action === 'ed-cap') editCapability(target.dataset.field);
  else if (action === 'ed-model-delete') {
    openConfirmDialog({ title: '删除模型', danger: true, confirmText: '删除', message: '模型配置会被删除；已有会话保留，但要切换到其它模型才能继续。',
      onConfirm: () => { postIntent('model.delete', { modelProfileId: editor.modelProfileId }); closePanel(); } });
  }
});

document.addEventListener('input', (event) => {
  if (!editor || !event.target?.closest?.('#panel-root')) return;
  if (editor.kind === 'model' && event.target.name === 'modelId') refreshPreview();
  if (editor.kind === 'provider' && event.target.name === 'baseUrl') {
    const card = event.target.closest('.route-card');
    const reviewed = Boolean(card.dataset.presetRouteId) && event.target.value.trim().replace(/\/+$/, '') === card.dataset.presetBaseUrl;
    const chip = card.querySelector('[data-review-chip]');
    if (chip) { chip.classList.toggle('good', reviewed); chip.classList.toggle('quiet', !reviewed); setText(chip, reviewed ? '预设地址' : '自定义地址'); }
  }
});

// The native key dialog and host confirmations report back through the view: keep the account form current.
subscribe(() => {
  if (!editor || editor.kind !== 'provider' || !editor.providerId || !panelOpen()) return;
  const provider = providerOf(editor.providerId);
  const has = Boolean(provider?.hasCredential);
  const status = editor.node.querySelector('[data-key-status]');
  if (status && !status.classList.contains(has ? 'good' : 'warn')) {
    status.classList.toggle('good', has); status.classList.toggle('warn', !has);
    setText(status, has ? '已配置' : '未配置');
    const action = editor.node.querySelector('[data-action="ed-key"]');
    if (action) action.textContent = has ? '更换' : '填写';
  }
  for (const route of provider?.routes || []) {
    if (route.hostConfirmed === false) continue;
    const row = editor.node.querySelector(`.route-card[data-route-id="${route.routeId}"] .notice-row`);
    if (row && !row.dataset.leaving) { row.dataset.exit = 'collapse'; removeNode(row); }
  }
});
