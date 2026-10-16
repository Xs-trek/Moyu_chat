# CLAUDE.md

Moyu Chat 的开发依据。项目事实以当前源码为准，编排应有的行为见 `docs/spec/`，功能的完成状态见 `docs/status.md`；`docs/history/` 中的文档只是 Codex 时期的历史基线，可能描述已删除的功能。

## 定位

- Android 多模型聊天客户端：原生 Java 外壳 + WebView 原生 JS 前端（无框架、无依赖）。
- 自用的**模型实验台**：对比不同模型、不同协议的输出中可利用的部分，以及不同原生编排设计的效果。
- 原生层的复杂度是有意为之。不要以“简化”为由删减编排、上下文、校验或观察能力。
- 本仓库是 beta 线：`applicationId` 为 `com.moyu.chat.beta`，可与原版 `com.moyu.chat` 并存安装；Java namespace 仍是 `com.moyu.chat`。

## 开发原则

1. **可用的 Chat 永远第一。** 观察、实验和编排改动都不能让日常使用变慢、变不稳定或变难用；实验功能默认折叠，不打扰使用。
2. **评测在使用中进行。** 不做批量评测框架。信号来自真实对话：调用链、用量、耗时、校验与用户反馈、分支对比；长期积累后看按模型、按模式的趋势，不追求统计显著性。
3. **极简 / 标准 / 智能是功能裁剪档位**，对应“编排帮多少忙”；日常切换使用即可对比同一模型在不同档位下的表现和成本。
4. **编排要能随模型进步而退场。** 每个辅助模块（Router、压缩、原文回查、联网规划、按需联网、校验修正、子任务）都要能单独关闭，并在调用链里看得到它的投入（token、耗时）和产出（判断、修正），用来决定它是否还值得开。
5. **界面偏好不需要论证**；编排调整要有前后对比的依据，哪怕只是使用中的观察数据。

## 必须保持的约束

1. **API Key** 只存在原生安全存储（`ChatSecretStore`）中，不进 WebView、不进 SQLite、不进日志或观察视图；只发往账号已确认的域名，传输层不跟随重定向（`docs/provider-config.md` §5）。
2. **严格 CSP**（`index.html`）：前端不加载远程资源。第三方库只能以本地文件引入，引入前先说明理由。
3. **界面风格统一**（`docs/ui-design.md`）：界面全部在 WebView 里实现，不用 `<select>` 和系统 Toast；原生只保留 API Key 输入框、系统图片选择器和外部浏览器。动效按规范的 token 和交互表实现。
4. **协议 ≠ 能力**：不从模型名或协议推断推理、thinking、图片、联网、续接、工具调用等能力；能力必须由具体部署（Model Profile）显式声明。
5. 改动 **账号（Provider）/ 路线（Route）/ Model Profile / Session** 结构或**数据库 schema** 前，必须先和用户讨论。服务商预设和模型目录是数据（`provider-presets.json`），目录声明只对原样的预设路线和完全一致的 Model ID 生效。
6. 前端源码只改根目录的 `src/` 和 `index.html`（浏览器预览的 `dev/preview.html` 要保持同样的容器结构）。`android/app/src/main/assets/ui/` 是 `syncMoyuChatUi` 任务在构建时生成的副本（Git 忽略），不要手改。

## 架构概要

```text
WebView (src/)  --postMessage(intent)-->  MainActivity.MoyuHostBridge  -->  ChatCoordinator
WebView (src/)  <--moyu:view (full/patch)--  ChatRuntime  <--  ChatCoordinator
```

| 区域 | 主要文件 |
|---|---|
| 进程与宿主 | `MoyuChatApplication`、`ChatRuntime`（进程级，Activity 只是挂载点）、`MainActivity`（WebView、API Key 原生输入框、图片选择）、`ChatTurnService`（回合期间前台服务） |
| 编排中枢 | `ChatCoordinator`：intent 分发、会话生命周期、分支、视图投影；通过 `TurnPipeline.Env` 向回合提供执行器、忙碌标记和视图推送 |
| 回合管线 | `TurnPipeline`：接收 → 上下文 → 路由 → 压缩（放不下时等后台压缩或同步压缩）→ 联网规划 → 工具选择 → 联网检索 → 生成 → 校验/修正 → 收尾 → 按实际用量决定是否后台压缩；`ProviderRoundLoop`（生成阶段：多轮主调用、客户端工具轮次、20 fps 流式投影）；`TurnRun`（回合内状态）、`TurnConfig`（冻结配置）、`TurnControl`（取消、用量、调用链） |
| 协议适配 | `ChatTransport`、`ChatTransportRegistry`、`OpenAiCompatibleTransport`（Chat Completions + Responses）、`AnthropicMessagesTransport`、`ProviderEndpoint`（SDK 根地址约定）、`ProviderAuth`、`RouteDialect`（路线的协议细节）、`RouteHeaders`（非机密附加请求头）、`ProviderErrors`（失败分类与请求 ID） |
| 账号与能力配置 | `ProviderConfigService`（账号 / 路线 / 模型的保存、校验、视图投影、密钥域名确认、预算）、`ProviderGuard`（检查 Key、端点模型列表、连接测试、余额）、`RouteGate`（路线并发上限）、`ProviderPresets` + `resources/com/moyu/chat/provider-presets.json`（服务商预设与模型目录）、`DeploymentResolver`（默认 < 端点 < 目录 < 实测 < 手动，逐项记录来源）、`ModelPricing`、`ModelProfileValidator`、`ModelPreflight`、`ConfigurationValidator`、`ReasoningPolicy`、`ProviderContinuationPolicy/Identity`；说明见 `docs/provider-config.md` |
| 上下文 | `ContextMode`（minimal/standard/smart）、`ContextBuilder`、`ContextBudgetManager`、`ContextCheckpoint`（压缩摘要及其覆盖范围的哈希）、`CompactionService`（压缩：回合结束后按实际用量在后台进行，每会话一个）、`ToolHistory`（工具调用和结果作为历史：随回答保存、后续回合重放、旧结果清除）、`CanonicalPrompt`、`PromptPolicy`、`PromptModules`、`LocalKnowledgeStore` + `KnowledgeLexicon`（FTS 记忆/检索）、`SnapshotService`（本地分支快照）、`SessionHistory`（会话历史的唯一读取入口：快照 + 本会话时间线） |
| Harness | `TurnModules`（每个模块本回合能否运行，接收时一次决定并记原因）、`HarnessProfile`、`HarnessPolicyResolver`、`TaskRouter`、`TaskProfile`、`ReliabilityModules`、`SmartVerifierService`、`SmartVerifierCalibration`、`FocusedSubagentService` |
| 工具与联网 | `ClientToolRuntime`（web_search / web_fetch / delegate_subtask / history_recall：本会话原文回查，每轮 ≤3 次）、`WebOrchestrator`（规划）、`MoyuWebService`（检索/抓取） |
| 会话标题 | `SessionTitles`（临时标题、模型命名的提示词与清洗；只替换仍是自动的标题），命名调用由 `ChatCoordinator` 在首轮完成后发起，记为该回合的 `title` 阶段 |
| 观察 | `TurnStats`（统计页：按模型 × 上下文模式汇总延迟、token、辅助占比、各辅助模块实际执行率和评价）、`TurnUsage`（按主调用 / 辅助调用 / 子任务分类，并按发起阶段归属每次模型调用）、`TurnTrace`（每个阶段的状态、耗时、判断摘要，跳过时记录原因）、`WireCapture`（协议检查器：脱敏后的请求 / 响应原文，不含请求头和 API Key，只保留最近 30 回合，可在设置中关闭） |
| 存储 | `ChatDatabase`（`moyu-beta.db`，schema 2；v1→v2 增加 `turn_archive`，见 `onUpgrade`）、`ChatMediaStore`、`ChatSecretStore` |
| 前端 | `src/bridge.js`（intent 发送、视图接收、`requestIntent` 等待带数据的结果）、`src/state.js`（full/patch 合并）、**`src/view-model.js`（原生视图 → 界面数据的唯一适配层）**、`src/render.js`（页面与时间线协调、流式补丁）、`src/overlays.js`（抽屉、底部面板、对话框、Back）、`src/markdown.js`（安全 Markdown + 流式增量渲染）、`src/math.js`（公式：按需加载本地 `src/vendor/katex/`，文件按 sha256 锁定在静态检查里）、`src/highlight.js`（本地轻量代码高亮）、`src/mascot.js`（像素小鱼的播放：精灵条 + 逐帧 Web Animation）、`src/mascot-sprite.js`（小鱼的像素数据与姿态帧表）、`src/mascot-director.js`（小鱼反应与轻量彩蛋的调度）、`src/fish-game.js`（等回答时的小游戏）、`src/morph.js`（原地更新 DOM、一次性动效、FLIP、数值淡入）、`src/drawer-gesture.js`（侧边栏跟手拖动）、`src/session-select.js`（会话列表长按多选与批量删除）、`src/editors.js`（Provider / Model Profile 编辑页）、`src/remote.js`（按需读取的数据：调用链、回答对比、统计）、`src/dom.js`、`src/main.js`（事件分发）、`src/render-gate.js`、`src/scroll-anchor.js`、`src/styles.css` |
| 前后端边界 | 原生视图字段的解释只放在 `src/view-model.js`；渲染器不直接解读原生字段。后端调整编排、用量或校验投影时，同步改 `contract/moyu-chat-contract.d.ts` 和 `view-model.js`。同一页面收到新视图时用 `morph` 原地更新（保留节点、滚动位置、进行中的过渡和展开状态），只有路由或会话变化才重建页面并播放进入动画；新出现的节点用 `fx-*` 类做一次性动效（只用 transform / opacity），要退场动画的节点声明 `data-exit`调用链只通过 `turn.trace.get` 按需读取，不随视图推送；用量存在 `turns` 表，由原生按原有形状投影成 `kind: usage` 条目。Android Back 先调用 `window.moyuHandleBack()` 关闭前端浮层；窗口/系统栏颜色由 `Host.applyAppearance` 按 `--canvas` token 设置 |
| 契约 | `contract/moyu-chat-contract.d.ts`：intent 与视图模型类型，改动原生视图或 intent 时同步更新 |

## 构建与验证

每次改动后都要完整验证，失败不能报告为完成：

```bash
npm run verify
```

等价于：

```bash
npm run build
npm test
JAVA_HOME="$PWD/.tools/jdk-17.0.20+8" ./.tools/gradle-8.5/bin/gradle -p android --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- 系统 `JAVA_HOME` 可能是 JDK 8，必须使用 `.tools/` 下的 JDK 17（`scripts/verify.sh` 已处理）。
- Android SDK 来自环境变量 `ANDROID_HOME`（仓库没有 `local.properties`）。
- 额外任务可以作为参数传入，例如 `bash scripts/verify.sh :app:assembleRelease`。
- `npm test` 跑 `tests/*.test.mjs`：Node 自带测试运行器 + `tests/dom-shim.mjs`（极简 DOM，无依赖），直接加载 `src/` 模块验证前端行为（Markdown、流式补丁、bridge、view-model）。改这些行为时同步补测试。
- `scripts/static-check.mjs` 主要是针对源码的字符串断言（版本号、schema 版本、安全边界、关键结构）。修改它断言的代码时要同步修改断言，不要为了通过检查而删掉安全或边界断言。
- 版本号集中在 `static-check.mjs` 顶部的 `VERSION_NAME` / `VERSION_CODE` / `SCHEMA_VERSION`，并需与 `package.json`、`android/app/build.gradle`、`src/main.js` 的 `uiVersion`、`MoyuWebService` 的 User-Agent 保持一致。
- `RealEndpointSmokeTest` 需要真实凭据，默认跳过；跳过不代表外部端点已验证。

## 浏览器预览

```bash
npm run dev
```

打开 `http://localhost:5178/?f=<fixture>&theme=<system|light|dark>`。`dev/mock-host.js` 模拟 `MoyuHost` 和 `ChatCoordinator` 的 view.full / view.patch 信封（流式补丁按原生的 20 fps 节奏发送），`src/` 原样运行，CSP 与 `index.html` 相同。小鱼图鉴在 `/dev/mascot.html`。fixture 定义在 `dev/fixtures.js`，页面左侧的 DEV 面板可以切换 fixture、主题，以及触发流式、联网、出错和中断。`dev/` 不会被打包进 APK（`syncMoyuChatUi` 只复制 `index.html` 和 `src/**`，静态检查会核对这一点）。预览不能代替真机：软键盘、WebView 版本差异、系统字体都要在设备上确认，清单见 `docs/device-checklist.md`。

## 工作方式

1. 先读代码再动手；每个阶段开始前先给出计划，等用户确认后再执行。
   - 涉及编排、上下文、会话 / 分支语义、数据归属的改动，先改 `docs/spec/`（规格）：新增或修改条目，取舍列为决策由用户确认；再确认归属组件，然后实现，代码和测试引用条目编号。审查以规格为基线。
   - 机制以 Claude / GPT（官方文档、Claude Code / Codex 等 agent 应用、两家的产品行为）为准；其他项目只参考实现，它们的机制要有证明更优的依据才采用（`docs/spec/README.md`）。
2. 分支：`claude/beta` 是 beta 主干；每个阶段在 `claude/<阶段>` 分支上开发，验证通过后提交一次，再快进合并回 `claude/beta`。未经要求不推送。
3. 每个阶段结束时用简短中文汇报：改了什么、为什么改、验证结果、遗留问题。

## 已移除的功能（不要恢复）

- Moyu Remote 跨 APK 桥接（RemoteDataBridge、Snapshot 导入、Handoff 回传）。
- ChatGPT / Claude 官方导出文件导入。以后最多考虑导入本应用自身、同协议版本的会话导出。
- 原版的 v1→v16 迁移链。beta 线 schema 从 1 重新计数，库文件改为 `moyu-beta.db`；原版库文件 `moyu-chat.db` 直接删除，不迁移；版本号比当前构建新的文件删除重建。启动时清掉没有对应 Provider 的密钥和没有引用的图片。阶段 2 结束、装上真机之前，新表直接并入 v1；之后的 schema 变更从 v1 往后写 `onUpgrade` 步骤，并在 `static-check.mjs` 补断言。
- `providers` 表上的 protocol / model / 上下文上限 / base_url 等旧列，以及“只填 Provider 就自动生成模型”的一步式创建；Model Profile 的 `defaultReasoning` 和 `legacy-manual` 配置模式；`context.intelligence.set` intent。
- 模型配置的“自动 / 高级”二选一（`ModelCatalog`、`configMode`、`model.catalog.preview`）：改为逐项合成并标明来源，手动值单独保存。
