// Preview stand-in for ProviderConfigService + DeploymentResolver. Accounts are kept raw (routes + models
// with overrides) and projected into the same view shape native emits. Resolution mirrors native in
// spirit: defaults < catalog (pristine preset route only) < manual overrides, with a source per field.
// Validation is deliberately thin; native is the authority. Preview only; never packaged.
import data from './provider-presets.js';

const CHAT = 'openai-chat-completions', RESPONSES = 'openai-responses', MESSAGES = 'anthropic-messages';
export const FIELDS = ['contextTokens', 'inputTokens', 'maxOutputTokens', 'inputModalities', 'outputModalities', 'reasoningWire', 'reasoningLevels',
  'thinkingMode', 'thinkingBudgetTokens', 'toolCapabilities', 'clientToolCalling', 'continuationMode', 'harnessPreset', 'serviceTier', 'pricing'];
const LABELS = { contextTokens: '上下文窗口', inputTokens: '输入上限', maxOutputTokens: '输出上限', inputModalities: '输入模态', outputModalities: '输出模态',
  reasoningWire: '推理参数', reasoningLevels: '推理档位', thinkingMode: '思考模式', thinkingBudgetTokens: '手动思考预算', toolCapabilities: '厂商联网工具',
  clientToolCalling: '工具调用', continuationMode: '跨轮续接', harnessPreset: 'Harness 档位', serviceTier: '服务层级', pricing: '价格' };

const opt = (value, hint = '', ...protocols) => (protocols.length ? { value, hint, protocols } : { value, hint });
export function editorOptions() {
  return {
    catalogVersion: data.version,
    presets: data.presets.map((p) => ({ id: p.id, name: p.name, kind: p.kind, note: p.note || '', console: p.console || '', pricingUrl: p.pricingUrl || '',
      suggestedHeaders: p.suggestedHeaders || [], routes: p.routes.map((r) => ({ id: r.id, label: r.label, protocol: r.protocol, baseUrl: r.baseUrl, authMode: r.authMode, dialect: r.dialect || {} })) })),
    protocol: [opt(CHAT, 'Base URL 含版本段，例如 …/v1，追加 /chat/completions'), opt(RESPONSES, 'Base URL 含版本段，例如 …/v1，追加 /responses'), opt(MESSAGES, 'Base URL 不含 /v1，追加 /v1/messages')],
    authMode: [opt('bearer', 'Authorization: Bearer 请求头'), opt('x-api-key', 'x-api-key 请求头'), opt('api-key', 'api-key 请求头')],
    outputCap: [opt('max_tokens', '多数兼容端点', CHAT), opt('max_completion_tokens', 'OpenAI 官方推理模型要求', CHAT), opt('max_output_tokens', 'Responses 标准字段', RESPONSES), opt('omit', '从不发送，由服务端决定', CHAT, RESPONSES)],
    thinkingParam: [opt('none', '这条路线没有思考开关', CHAT), opt('thinking-type', 'thinking: {type: enabled|disabled}（DeepSeek、GLM、Kimi 等）', CHAT)],
    reasoningReplay: [opt('never', '不回传推理内容', CHAT), opt('with-tools', '带工具的请求回传（DeepSeek 要求）', CHAT), opt('always', '每条回答都回传（Kimi 思考模型要求）', CHAT)],
    reasoningWire: [opt('none', '不发送推理档位'), opt('openai-responses', 'reasoning.effort', RESPONSES), opt('openai-chat-effort', 'reasoning_effort', CHAT), opt('anthropic-effort', 'output_config.effort', MESSAGES)],
    thinkingMode: [opt('provider-default', '不发送，由服务端默认'), opt('enabled', '需要路线声明思考开关', CHAT), opt('adaptive', '', MESSAGES), opt('disabled', '部分模型不接受关闭思考', CHAT, MESSAGES), opt('manual', '需要填写手动思考预算；新模型已不接受', MESSAGES)],
    clientToolCalling: [opt('none', '不提供客户端工具'), opt('openai-chat-tools', '', CHAT), opt('openai-responses-tools', '', RESPONSES), opt('anthropic-tools', '', MESSAGES)],
    continuationMode: [opt('none', '只用可见历史'), opt('native', '回放厂商私有推理状态', RESPONSES, MESSAGES)],
    serviceTier: [opt('', '不发送'), opt('auto', '', CHAT, RESPONSES, MESSAGES), opt('default', '', CHAT, RESPONSES), opt('flex', '更便宜、更慢', CHAT, RESPONSES), opt('priority', '更快、更贵', CHAT, RESPONSES), opt('standard_only', '', MESSAGES)],
    harnessPreset: [opt('lean', '强模型：少干预'), opt('balanced', '默认'), opt('reinforced', '弱模型：多校验')],
    reasoningLevels: ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
    sources: { default: '默认', discovered: '端点', catalog: '目录', probe: '实测', manual: '手动' },
  };
}

const host = (url) => { try { return new URL(url).host; } catch { return ''; } };
const trim = (url) => String(url || '').trim().replace(/\/+$/, '');
function presetRoute(account, route) {
  const preset = data.presets.find((p) => p.id === account.presetId);
  const spec = preset?.routes.find((r) => r.id === route.presetRouteId);
  return spec && spec.baseUrl && spec.protocol === route.protocol && trim(spec.baseUrl) === trim(route.baseUrl) ? spec : null;
}
function catalogFor(account, route) {
  const spec = presetRoute(account, route);
  return spec ? data.models.filter((m) => m.preset === account.presetId && m.route === spec.id) : [];
}

export function resolve(account, route, modelId, overrides = {}) {
  const v = { contextTokens: 128000, inputTokens: 0, maxOutputTokens: route.protocol === MESSAGES ? 32000 : 16384, inputModalities: 'text', outputModalities: 'text',
    reasoningWire: 'none', reasoningLevels: '', thinkingMode: 'provider-default', thinkingBudgetTokens: 0, toolCapabilities: '', clientToolCalling: 'none',
    continuationMode: 'none', harnessPreset: 'balanced', serviceTier: '', pricing: {} };
  const s = Object.fromEntries(FIELDS.map((f) => [f, 'default']));
  const c = catalogFor(account, route).find((m) => m.id === String(modelId || '').trim()) || null;
  if (c) {
    const put = (f, value) => { if (value !== undefined) { v[f] = value; s[f] = 'catalog'; } };
    put('contextTokens', c.context); put('maxOutputTokens', c.maxOutput); put('inputModalities', c.input); put('outputModalities', c.output);
    if (c.reasoning) { put('reasoningWire', c.reasoning.wire); put('reasoningLevels', c.reasoning.levels); }
    put('toolCapabilities', c.nativeWeb); put('clientToolCalling', c.clientTools); put('continuationMode', c.continuation);
    put('harnessPreset', c.harness || undefined); put('pricing', c.pricing);
  }
  for (const [f, value] of Object.entries(overrides || {})) {
    if (!FIELDS.includes(f)) throw new Error(`未知的模型配置项：${f}`);
    v[f] = ['contextTokens', 'inputTokens', 'maxOutputTokens', 'thinkingBudgetTokens'].includes(f) ? Number(value) : value;
    s[f] = 'manual';
  }
  let error = '';
  if (!Number.isInteger(v.contextTokens) || v.contextTokens < 2048) error = `上下文窗口必须为 2,048–4,000,000 token，当前值：${overrides.contextTokens}`;
  else if (!Number.isInteger(v.maxOutputTokens) || v.maxOutputTokens < 256 || v.maxOutputTokens >= v.contextTokens) error = '输出上限必须 >=256 且小于上下文窗口';
  else if (v.reasoningLevels && v.reasoningWire === 'none') error = '推理档位需要先设置推理参数（reasoning wire）';
  return { values: v, sources: s, catalog: c ? { modelId: c.id, name: c.name, verified: Boolean(c.verified), retention: c.retention || '', pricing: c.pricing } : null, error };
}

export function projectProviders(accounts) {
  return accounts.map((a) => {
    const routes = a.routes.map((r) => ({ routeId: r.routeId, presetRouteId: r.presetRouteId || '', label: r.label, protocol: r.protocol, baseUrl: r.baseUrl,
      authMode: r.authMode, host: host(r.baseUrl), reviewed: Boolean(presetRoute(a, r)),
      hostConfirmed: !a.hasCredential || (r.confirmedHost ?? host(r.baseUrl)) === host(r.baseUrl), dialect: r.dialect || {}, headers: r.headers || {} }));
    const models = a.models.map((m) => {
      const route = a.routes.find((r) => r.routeId === m.routeId) || a.routes[0];
      const r = resolve(a, route, m.modelId, m.overrides);
      return { modelProfileId: m.modelProfileId, routeId: route.routeId, routeLabel: route.label, modelId: m.modelId, displayName: m.displayName || r.catalog?.name || m.modelId,
        enabled: m.enabled !== false, protocol: route.protocol, ...r.values, retention: r.catalog?.retention || '', sources: r.sources, overrides: { ...(m.overrides || {}) },
        catalogKey: r.catalog ? `${a.presetId}/${route.presetRouteId}/${m.modelId}` : '', catalogVerified: Boolean(r.catalog?.verified) };
    });
    const preset = data.presets.find((p) => p.id === a.presetId);
    const enabled = models.filter((m) => m.enabled);
    return { providerId: a.providerId, displayName: a.displayName, presetId: a.presetId, presetName: preset?.name || a.presetId, presetKind: preset?.kind || 'custom',
      hasCredential: Boolean(a.hasCredential), keyStatus: 'unknown', model: enabled[0]?.modelId || '', configured: Boolean(a.hasCredential) && enabled.length > 0, routes, models };
  });
}

// ---------- Editor intents (same payloads and results as native) ----------

export function makeConfigIntents(world) {
  const account = (id) => { const a = world.accounts.find((x) => x.providerId === id); if (!a) throw new Error('账号不存在'); return a; };
  let seq = 0;
  return {
    'model.editor.options': () => editorOptions(),
    'provider.save': (p) => {
      let a = p.providerId ? account(p.providerId) : null;
      if (!a) {
        const preset = data.presets.find((x) => x.id === (p.presetId || 'custom-openai'));
        if (!preset) throw new Error(`未知的服务商预设：${p.presetId}`);
        a = { providerId: `p-${Date.now()}`, presetId: preset.id, hasCredential: false, routes: [], models: [] };
        world.accounts.push(a);
        if (!p.routes) p.routes = preset.routes.filter((r) => r.baseUrl).map((r) => ({ presetRouteId: r.id, label: r.label, protocol: r.protocol, baseUrl: r.baseUrl, authMode: r.authMode, dialect: r.dialect }));
      }
      a.displayName = String(p.displayName || '').trim() || data.presets.find((x) => x.id === a.presetId)?.name || a.displayName;
      if (p.routes) {
        if (!p.routes.length) throw new Error('账号至少需要一条路线');
        const claimed = new Set(p.routes.map((r) => r.routeId).filter(Boolean));
        const next = p.routes.map((r) => {
          const base = trim(r.baseUrl);
          let old = r.routeId ? a.routes.find((x) => x.routeId === r.routeId) : null;
          if (r.routeId && !old) throw new Error('路线不存在');
          // Like native: a new route identical to a stored one this save does not keep reuses it (id, models).
          if (!r.routeId) old = a.routes.find((x) => !claimed.has(x.routeId) && x.protocol === r.protocol && trim(x.baseUrl) === base) || null;
          if (old) claimed.add(old.routeId);
          if (!/^https:\/\/[^/]+/.test(base)) throw new Error(`路线「${r.label || r.protocol}」：Base URL 必须使用 HTTPS`);
          if (r.protocol === MESSAGES && /\/v1$/.test(base)) throw new Error(`路线「${r.label}」：Anthropic 格式的 Base URL 不含末尾的 /v1`);
          for (const name of Object.keys(r.headers || {})) if (/key|token|secret|auth|cookie|session|password/i.test(name)) throw new Error(`不能在附加请求头里设置 ${name}`);
          return { ...(old || { routeId: `r-${Date.now()}-${++seq}`, presetRouteId: r.presetRouteId || '', protocol: r.protocol }), label: r.label || r.protocol,
            baseUrl: base, authMode: r.authMode, dialect: r.dialect || {}, headers: r.headers || {},
            confirmedHost: old ? old.confirmedHost : (a.routes.some((x) => host(x.baseUrl) === host(base)) ? host(base) : (a.hasCredential ? '' : undefined)) };
        });
        a.routes = next;
        a.models = a.models.filter((m) => next.some((r) => r.routeId === m.routeId));
      }
      return { providerId: a.providerId, hasCredential: a.hasCredential, routeIds: a.routes.map((r) => r.routeId) };
    },
    'provider.delete': (p) => { world.accounts = world.accounts.filter((x) => x.providerId !== p.providerId); },
    'route.host.confirm': (p) => { for (const a of world.accounts) for (const r of a.routes) if (r.routeId === p.routeId) r.confirmedHost = host(r.baseUrl); },
    'model.preview': (p) => {
      const a = account(p.providerId);
      const route = a.routes.find((r) => r.routeId === p.routeId);
      if (!route) return { fields: [], error: '请选择路线', catalog: null, sendsOutputCap: false };
      let r;
      try { r = resolve(a, route, p.modelId, p.overrides); } catch (e) { r = { ...resolve(a, route, p.modelId, {}), error: e.message }; }
      return { fields: FIELDS.map((f) => ({ field: f, label: LABELS[f], value: r.values[f], source: r.sources[f] })), error: p.modelId ? r.error : '填写 Model ID 后预览',
        catalog: r.catalog, sendsOutputCap: route.protocol === MESSAGES || r.sources.maxOutputTokens !== 'default' };
    },
    'route.catalog': (p) => {
      for (const a of world.accounts) for (const r of a.routes) if (r.routeId === p.routeId) {
        return { routeId: r.routeId, version: data.version, models: catalogFor(a, r).map((m) => ({ modelId: m.id, name: m.name, verified: Boolean(m.verified), retention: m.retention || '',
          pricing: m.pricing, added: a.models.some((x) => x.routeId === r.routeId && x.modelId === m.id) })) };
      }
      throw new Error('路线不存在');
    },
    'model.save': (p) => {
      const a = account(p.providerId);
      const route = a.routes.find((r) => r.routeId === p.routeId);
      if (!route) throw new Error('请选择这个账号下的一条路线');
      if (!String(p.modelId || '').trim()) throw new Error('Model ID 不能为空');
      const r = resolve(a, route, p.modelId, p.overrides);
      if (r.error) throw new Error(r.error);
      let m = a.models.find((x) => x.modelProfileId === p.modelProfileId);
      if (!m) { m = { modelProfileId: `m-${Date.now()}` }; a.models.push(m); }
      Object.assign(m, { routeId: route.routeId, modelId: p.modelId.trim(), displayName: p.displayName || r.catalog?.name || p.modelId.trim(), overrides: { ...(p.overrides || {}) } });
      if ('enabled' in p) m.enabled = p.enabled;
      return { modelProfileId: m.modelProfileId };
    },
    'model.delete': (p) => { for (const a of world.accounts) a.models = a.models.filter((m) => m.modelProfileId !== p.modelProfileId); },
    'model.enabled.set': (p) => { for (const a of world.accounts) for (const m of a.models) if (m.modelProfileId === p.modelProfileId) m.enabled = Boolean(p.enabled); },
  };
}
