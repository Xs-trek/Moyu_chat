// Preview fixtures shaped like contract/moyu-chat-contract.d.ts. All data is fictional.
import { projectProviders } from './mock-config.js';
const MIN = 60_000;
const BASE = Date.parse('2026-10-12T09:00:00+08:00');
const at = (minutes) => new Date(BASE + minutes * MIN).toISOString();

/** Raw accounts (routes + models with overrides); mock-config projects them like native does. */
export const accounts = [
  {
    providerId: 'p-anthropic', displayName: 'Anthropic', presetId: 'anthropic', hasCredential: true,
    routes: [{ routeId: 'r-anthropic', presetRouteId: 'messages', label: 'Messages', protocol: 'anthropic-messages', baseUrl: 'https://api.anthropic.com', authMode: 'x-api-key', dialect: { autoCache: true }, headers: {} }],
    models: [{ modelProfileId: 'm-sonnet', routeId: 'r-anthropic', modelId: 'claude-sonnet-5-5', displayName: 'Claude Sonnet 5.5', overrides: { continuationMode: 'native' } }],
  },
  {
    providerId: 'p-openai', displayName: 'OpenAI', presetId: 'openai', hasCredential: true,
    routes: [{ routeId: 'r-openai', presetRouteId: 'responses', label: 'Responses', protocol: 'openai-responses', baseUrl: 'https://api.openai.com/v1', authMode: 'bearer', dialect: { store: false, outputCap: 'max_output_tokens' }, headers: { 'OpenAI-Project': 'proj_demo' } }],
    models: [
      { modelProfileId: 'm-gpt', routeId: 'r-openai', modelId: 'gpt-6.1-sol', displayName: 'GPT-6.1 Sol' },
      { modelProfileId: 'm-gpt-mini', routeId: 'r-openai', modelId: 'gpt-6-luna', displayName: 'GPT-6 Luna', overrides: { harnessPreset: 'lean' } },
    ],
  },
  {
    providerId: 'p-go', displayName: 'OpenCode Go', presetId: 'opencode-go', hasCredential: true,
    routes: [
      { routeId: 'r-go-chat', presetRouteId: 'chat', label: 'Chat Completions', protocol: 'openai-chat-completions', baseUrl: 'https://opencode.ai/inference/go/v1', authMode: 'bearer', dialect: { outputCap: 'max_tokens', streamUsage: true, thinkingParam: 'thinking-type', reasoningReplay: 'always' }, headers: {} },
      { routeId: 'r-go-messages', presetRouteId: 'messages', label: 'Messages', protocol: 'anthropic-messages', baseUrl: 'https://relay.example.net', authMode: 'x-api-key', dialect: { autoCache: false }, headers: {}, confirmedHost: 'opencode.ai' },
    ],
    models: [
      { modelProfileId: 'm-ds', routeId: 'r-go-chat', modelId: 'deepseek-v4-flash-vision-exp', displayName: 'DeepSeek V4 Flash Vision' },
      { modelProfileId: 'm-glm', routeId: 'r-go-chat', modelId: 'glm-5.3-flash', displayName: '', overrides: { contextTokens: 200000, harnessPreset: 'reinforced' } },
      { modelProfileId: 'm-muse', routeId: 'r-go-chat', modelId: 'muse-spark-1.3-contributor', displayName: 'Muse Spark（停用）', enabled: false },
    ],
  },
  {
    providerId: 'p-empty', displayName: '未配置凭据的自定义端点', presetId: 'custom-openai', hasCredential: false,
    routes: [{ routeId: 'r-empty', presetRouteId: '', label: 'Chat Completions', protocol: 'openai-chat-completions', baseUrl: 'https://example.invalid/v1', authMode: 'bearer', dialect: {}, headers: {} }],
    models: [],
  },
];

export const providers = projectProviders(accounts);

function profileOf(id) {
  for (const provider of providers) for (const m of provider.models) if (m.modelProfileId === id) return { provider, model: m };
  return { provider: providers[0], model: providers[0].models[0] };
}

export function session(id, title, modelProfileId, extra = {}) {
  const { provider, model: m } = profileOf(modelProfileId);
  return {
    localSessionId: id, title, providerId: provider.providerId, providerName: provider.displayName, modelProfileId, model: m.modelId, modelName: m.displayName,
    reasoningLevels: m.reasoningLevels, reasoningEffort: 'default', thinkingMode: m.thinkingMode, thinkingBudgetTokens: 0, continuationMode: m.continuationMode,
    harnessPreset: m.harnessPreset, clientToolCalling: m.clientToolCalling, subagentMode: 'off', contextMode: 'standard', effectiveContextMode: 'standard',
    inputModalities: m.inputModalities, outputModalities: m.outputModalities, nativeWebCapabilities: m.toolCapabilities, webMode: 'auto',
    effectiveWebBackend: m.toolCapabilities ? `${provider.displayName} hosted Web` : 'Moyu Web · Exa + Fetch', state: 'completed', updatedAt: at(0),
    messages: [], composerDraft: '', draftMedia: [], ...extra,
  };
}

/** Turn ids are unique across every fixture session, like native UUIDs. */
let fixtureTurns = 0;

/** Builds a timeline with consecutive localSeq values; usage rows follow their assistant reply. */
export function timeline(turns, startMinute = 0) {
  const items = [];
  let seq = 0;
  let minute = startMinute;
  for (const turn of turns) {
    minute += 2;
    items.push({ localSeq: ++seq, kind: 'message', role: 'user', text: turn.user, attachments: turn.attachments, createdAt: at(minute) });
    if (turn.error) { items.push({ localSeq: ++seq, kind: 'error', code: turn.error.code, summary: turn.error.summary, createdAt: at(minute) }); continue; }
    if (turn.assistant === undefined) continue;
    const { model: m } = profileOf(turn.modelProfileId || 'm-sonnet');
    items.push({
      localSeq: ++seq, turnId: `turn-${++fixtureTurns}`, kind: 'message', role: 'assistant', text: turn.assistant, reasoning: turn.reasoning || '', model: m.modelId, modelName: m.displayName,
      modelProfileId: m.modelProfileId, reasoningAvailable: Boolean(m.reasoningLevels), reasoningEffort: turn.effort || 'default', sources: turn.sources, webBackend: turn.webBackend,
      verification: turn.verification, incomplete: turn.incomplete, terminalCode: turn.terminalCode, createdAt: at(minute + 1),
    });
    items.push({ localSeq: ++seq, kind: 'usage', ...usage(turn.usage), createdAt: at(minute + 1) });
  }
  return items;
}

export function usage(extra = {}) {
  const input = extra.inputTokens ?? 18432;
  return {
    inputTokens: 18432, lastMainInputTokens: input, outputTokens: 1260, cachedInputTokens: Math.round(input * 0.83), cacheHitBaseTokens: input, cacheWriteInputTokens: 0, reasoningTokens: 640,
    outputTokensPerSecond: 58.4, firstOutputLatencyMs: 1840, outputDurationMs: 21500, requestDurationMs: 23900, modelCallCount: 1, ...extra,
  };
}

const svg = (w, h, hue, label) => `data:image/svg+xml;utf8,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><rect width="100%" height="100%" fill="hsl(${hue} 60% 70%)"/><text x="50%" y="50%" font-size="${Math.round(h / 8)}" text-anchor="middle" dominant-baseline="middle" fill="#222">${label}</text></svg>`)}`;
export const image = (id, w, h, hue, label) => ({ mediaId: id, kind: 'image', mimeType: 'image/svg+xml', fileName: `${label}.png`, sizeBytes: 182_000, width: w, height: h, url: svg(w, h, hue, label) });

export const LONG_ANSWER = `## 结论

WebView 里的 **长文阅读** 主要受三件事影响：行高、段间距和中文字体回退。正文建议 \`16px\` 起步，行高 1.7 左右；段落之间留出约 0.8 个行高，比首行缩进更适合屏幕阅读。

### 1. 字体回退

Android 系统字体是 Roboto，中文会回退到 Noto Sans CJK。字体栈里写 \`Inter\` 但没有随包提供文件时，它只是一个永远不会命中的名字。

> 中文与西文混排时，西文字体的 x-height 通常比汉字视觉尺寸小，混排行会显得“上下不齐”。可以略微放大西文，或者干脆统一用系统字体。

### 2. 代码与表格

\`\`\`java
// 节流：原生侧最多 20 fps 推送流式补丁
if (previous != null && now - previous < 50_000_000L) return;
lastStreamViewNanos.put(sessionId, now);
emitView();
\`\`\`

| 指标 | 当前 | 建议 |
|---|---|---|
| 正文字号 | 15.2px | 16–17px |
| 行高 | 1.62 | 1.7–1.8 |
| 段间距 | 0.58rem | 0.8–1em |
| 代码字号 | 13.4px | 13–14px |

### 3. 列表

- 首屏只保留一行用量摘要，点开再看完整指标；
- 操作按钮收进“更多”，复制保留为常驻；
- [x] 流式阶段就按块渲染 Markdown
- [ ] 代码高亮（需要引入本地库，先评估体积）

参考：[WebView 文本缩放说明](https://developer.android.com/reference/android/webkit/WebSettings) 与 <https://www.w3.org/TR/clreq/>。`;

const REASONING = `用户问的是长文阅读体验。先拆成字体、排版节奏和信息密度三部分。

字体：Android WebView 默认 sans-serif 是 Roboto，CJK 回退到 Noto Sans CJK SC。Inter 没有打包，所以等同于不存在。

排版：当前 markdown-body 行高 1.62、段距 0.58rem，偏紧。中文正文 1.7 以上更舒服。

信息密度：每条回复下面 8 个指标格 + 3 个按钮，会打断阅读节奏。应该折叠。`;

export const SOURCES = [
  { url: 'https://developer.android.com/develop/ui/views/layout/webapps/webview', title: 'Build web apps in WebView | Android Developers', backend: 'exa' },
  { url: 'https://www.w3.org/TR/clreq/', title: 'Requirements for Chinese Text Layout 中文排版需求', backend: 'exa' },
  { url: 'https://material.io/design/layout/spacing-methods.html', title: 'Spacing methods – Material Design', backend: 'exa' },
  { url: 'https://web.dev/articles/font-best-practices', title: 'Best practices for fonts | web.dev', backend: 'fetch' },
  { url: 'https://github.com/notofonts/noto-cjk', title: 'notofonts/noto-cjk: Noto CJK fonts', backend: 'fetch' },
];

const VERIFY_PASS = { status: 'valid', verdict: 'pass', selfConfidence: 0.86, coverageComplete: true, action: 'accept', risk: 0.12, policyVersion: 'v3',
  checks: [{ rule: 'constraint_adherence', result: 'pass', severity: 'major', evidenceIds: [], note: '' }, { rule: 'material_factual_support', result: 'pass', severity: 'hard', evidenceIds: ['S1', 'S2'], note: '' }] };
const VERIFY_REPAIR = { status: 'valid', verdict: 'fail', selfConfidence: 0.71, coverageComplete: true, action: 'repair', risk: 0.64, repairAttempted: true, repaired: true, policyVersion: 'v3',
  checks: [{ rule: 'evidence_integrity', result: 'fail', severity: 'major', evidenceIds: ['S3'], note: '引用的版本号与来源不一致' }] };

const LONG_TOPICS = ['WebView 软键盘与 adjustResize', 'SQLite FTS4 中文分词', 'Responses API 的 reasoning item 续接', 'Anthropic thinking 签名回传', '上下文压缩阈值怎么定',
  'verifier 的误判怎么统计', '联网检索去重策略', 'Exa 和 Tavily 的差异', '子任务并行上限', 'Checkpoint 什么时候失效', '图片输入的像素上限', 'token 缓存命中率为什么低',
  '流式补丁 20fps 够不够', '会话分支和回退的区别', '前台服务的通知文案', 'Markdown 表格横向滚动', '暗色模式跟随系统', '长按复制与选择范围', '首字延迟的构成', '模型目录如何更新',
  'Harness 预设 lean 和 reinforced', '跨会话记忆的隐私边界', '本地媒体垃圾回收', 'Provider 凭据轮换', 'Router 判断失误怎么办', 'Smart 模式的额外成本', 'Web Planner 的触发条件',
  '工具调用批量上限', '错误码怎么展示', '导出会话的格式'];

export function longTurns(count = 30) {
  return Array.from({ length: count }, (_, i) => ({
    user: `${LONG_TOPICS[i % LONG_TOPICS.length]}？${i % 4 === 0 ? '请具体一点，最好给个例子。' : ''}`,
    assistant: i % 5 === 4 ? LONG_ANSWER : `关于“${LONG_TOPICS[i % LONG_TOPICS.length]}”，要点有三个：\n\n1. 先确认当前实现的边界和失败模式；\n2. 用最小的改动验证假设，并保留可观测的指标；\n3. 把结论写回文档，避免下次重新推导。\n\n具体到这个项目，\`ChatCoordinator\` 已经记录了每一轮的用量，可以先从那里拿数据。`,
    modelProfileId: i % 3 === 2 ? 'm-gpt' : 'm-sonnet',
    usage: { inputTokens: 4000 + i * 1800, outputTokens: 300 + (i % 7) * 120, cachedInputTokens: i ? 3000 + i * 1500 : 0 },
  }));
}

export const STREAM_SCRIPT = { reasoning: REASONING, answer: LONG_ANSWER, sources: SOURCES };

function mainSessions() {
  return [
    session('s-reading', '长文阅读排版怎么调', 'm-sonnet', {
      contextMode: 'smart', effectiveContextMode: 'smart', updatedAt: at(40), preview: 'WebView 里的长文阅读主要受三件事影响…',
      messages: timeline([
        { user: '这个聊天界面看长回答很累，排版上该怎么调？', assistant: LONG_ANSWER, reasoning: REASONING, sources: SOURCES, webBackend: 'Moyu Web · Exa + Fetch', verification: VERIFY_PASS,
          usage: { auxiliaryCallCount: 3, auxiliaryInputTokens: 6200, auxiliaryOutputTokens: 410, totalInputTokens: 24632, totalOutputTokens: 1670 } },
        { user: '那暗色模式呢？截图里这两张哪个更好？', attachments: [image('img-1', 1080, 2340, 210, '截图 A'), image('img-2', 1080, 2340, 30, '截图 B')],
          assistant: '暗色模式下 **B 更好**：背景不是纯黑，正文对比度约 13:1，长时间阅读不刺眼。A 的纯黑背景配纯白文字对比度过高，滚动时有拖影感。',
          reasoning: '两张图：A 纯黑背景，B 深灰偏蓝。比较对比度与 OLED 拖影。', verification: VERIFY_REPAIR,
          usage: { subagentCallCount: 2, childInputTokens: 9100, childOutputTokens: 820, totalInputTokens: 30100, totalOutputTokens: 2010 } },
      ], 30),
    }),
    session('s-long', '项目问答（长会话）', 'm-gpt', { updatedAt: at(20), contextBudgetTokens: 64000, profileChanged: true, preview: '关于“导出会话的格式”，要点有三个…', messages: timeline(longTurns(30)) }),
    session('s-errors', '限流与中断', 'm-ds', {
      contextMode: 'minimal', effectiveContextMode: 'minimal', state: 'failed', updatedAt: at(10), preview: 'Provider 返回 429',
      messages: timeline([
        { user: '列出 Android 15 的 WebView 变化。', assistant: 'Android 15 中 WebView 的主要变化包括：\n\n1. 默认启用', incomplete: true, terminalCode: 'interrupted', usage: { outputTokens: 18, cachedInputTokens: undefined, reasoningTokens: undefined, requestDurationMs: 4100 } },
        { user: '继续', error: { code: 'provider.http_429', summary: 'Provider 限流：429 Too Many Requests。请稍后重试或切换模型。' } },
      ]),
    }),
    session('s-branch', '长文阅读排版怎么调 · 分支', 'm-gpt', {
      updatedAt: at(5), preview: '（分支）', origin: { snapshotId: 'snap-1', sourceSessionId: 's-reading', sourceTitle: '长文阅读排版怎么调', createdAt: at(5), itemCount: 3 },
      messages: timeline([{ user: '这个聊天界面看长回答很累，排版上该怎么调？', assistant: '（继承自原会话）先看行高和段间距。' }]).map((item) => ({ ...item, inherited: true })),
    }),
  ];
}

const baseUi = { pendingRequestIds: [], showReasoning: true, crossChatMemoryEnabled: true, notifyInBackground: true, customInstruction: '', helpersOff: [], defaultContextMode: 'standard', defaultWebMode: 'auto', standardSubagentMode: 'off' };

/** Each fixture returns a fresh mutable world for the mock host. */
export const fixtures = {
  home: { label: '会话列表', build: () => ({ route: 'home', accounts: structuredClone(accounts), sessions: mainSessions(), ui: { ...baseUi } }) },
  empty: { label: '空状态（无会话）', build: () => ({ route: 'home', accounts: structuredClone(accounts), sessions: [], ui: { ...baseUi } }) },
  'new-session': { label: '空会话', build: () => {
    const fresh = session('s-new', '新会话', 'm-sonnet', { state: 'idle', preview: '' });
    return { route: 'conversation', activeSessionId: 's-new', accounts: structuredClone(accounts), sessions: [fresh, ...mainSessions()], ui: { ...baseUi } };
  } },
  reading: { label: '长文 + 推理 + 来源 + 校验', build: () => ({ route: 'conversation', activeSessionId: 's-reading', accounts: structuredClone(accounts), sessions: mainSessions(), ui: { ...baseUi } }) },
  long: { label: '长会话（30 轮）', build: () => ({ route: 'conversation', activeSessionId: 's-long', accounts: structuredClone(accounts), sessions: mainSessions(), ui: { ...baseUi } }) },
  streaming: { label: '流式回复（自动开始）', autoplay: 'stream', build: () => ({ route: 'conversation', activeSessionId: 's-reading', accounts: structuredClone(accounts), sessions: mainSessions(), ui: { ...baseUi } }) },
  errors: { label: '错误与中断', build: () => ({ route: 'conversation', activeSessionId: 's-errors', accounts: structuredClone(accounts), sessions: mainSessions(), ui: { ...baseUi } }) },
  branch: { label: '分支会话', build: () => ({ route: 'conversation', activeSessionId: 's-branch', accounts: structuredClone(accounts), sessions: mainSessions(), ui: { ...baseUi } }) },
  providers: { label: 'Provider 页', build: () => ({ route: 'providers', accounts: structuredClone(accounts), sessions: mainSessions(), ui: { ...baseUi } }) },
  settings: { label: '设置页', build: () => ({ route: 'settings', accounts: structuredClone(accounts), sessions: mainSessions(), ui: { ...baseUi } }) },
  unconfigured: { label: '首次使用（无 Provider）', build: () => ({ route: 'home', accounts: [], sessions: [], ui: { ...baseUi } }) },
};
