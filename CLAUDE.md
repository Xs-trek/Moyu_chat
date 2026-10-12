# CLAUDE.md

Moyu Chat 的开发依据。项目事实以当前源码为准；`docs/history/` 中的文档只是 Codex 时期的历史基线，可能描述已删除的功能。

## 定位

- Android 多模型聊天客户端：原生 Java 外壳 + WebView 原生 JS 前端（无框架、无依赖）。
- 自用的**模型实验台**：对比不同模型、不同协议的输出中可利用的部分，以及不同原生编排设计的效果。
- 原生层的复杂度是有意为之。不要以“简化”为由删减编排、上下文、校验或观察能力。
- 本仓库是 beta 线：`applicationId` 为 `com.moyu.chat.beta`，可与原版 `com.moyu.chat` 并存安装；Java namespace 仍是 `com.moyu.chat`。

## 必须保持的约束

1. **API Key** 只存在原生安全存储（`ChatSecretStore`）中，不进 WebView、不进 SQLite、不进日志或观察视图。
2. **严格 CSP**（`index.html`）：前端不加载远程资源。第三方库只能以本地文件引入，引入前先说明理由。
3. **协议 ≠ 能力**：不从模型名或协议推断推理、thinking、图片、联网、续接、工具调用等能力；能力必须由具体部署（Model Profile）显式声明。
4. 改动 **Provider / Model Profile / Session** 三层结构或**数据库 schema** 前，必须先和用户讨论。
5. 前端源码只改根目录的 `src/` 和 `index.html`。`android/app/src/main/assets/ui/` 是 `syncMoyuChatUi` 任务在构建时生成的副本（Git 忽略），不要手改。

## 架构概要

```text
WebView (src/)  --postMessage(intent)-->  MainActivity.MoyuHostBridge  -->  ChatCoordinator
WebView (src/)  <--moyu:view (full/patch)--  ChatRuntime  <--  ChatCoordinator
```

| 区域 | 主要文件 |
|---|---|
| 进程与宿主 | `MoyuChatApplication`、`ChatRuntime`（进程级，Activity 只是挂载点）、`MainActivity`（WebView、配置对话框、图片选择）、`ChatTurnService`（回合期间前台服务） |
| 编排中枢 | `ChatCoordinator`：intent 分发、会话生命周期、分支、`send()` 回合流程（Router → Checkpoint → 联网规划/检索 → 主调用 → 客户端工具轮次 → 校验/修正）、视图投影 |
| 协议适配 | `ChatTransport`、`ChatTransportRegistry`、`OpenAiCompatibleTransport`（Chat Completions + Responses）、`AnthropicMessagesTransport`、`ProviderEndpoint`、`ProviderAuth` |
| 能力与配置 | `ModelCatalog`、`ModelProfileValidator`、`ModelPreflight`、`ConfigurationValidator`、`ReasoningPolicy`、`ProviderContinuationPolicy/Identity` |
| 上下文 | `ContextMode`（minimal/standard/smart）、`ContextBuilder`、`ContextBudgetManager`、`ContextCheckpoint(Service)`、`CanonicalPrompt`、`PromptPolicy`、`PromptModules`、`LocalKnowledgeStore` + `KnowledgeLexicon`（FTS 记忆/检索）、`SnapshotService`（本地分支快照） |
| Harness | `HarnessProfile`、`HarnessPolicyResolver`、`TaskRouter`、`TaskProfile`、`ReliabilityModules`、`SmartVerifierService`、`SmartVerifierCalibration`、`FocusedSubagentService` |
| 工具与联网 | `ClientToolRuntime`（web_search / web_fetch / delegate_subtask）、`WebOrchestrator`（规划）、`MoyuWebService`（检索/抓取） |
| 观察 | `TurnUsage`（按主调用 / 辅助调用 / 子任务分类记录用量） |
| 存储 | `ChatDatabase`（schema 16）、`ChatMediaStore`、`ChatSecretStore` |
| 前端 | `src/bridge.js`（intent 发送、视图接收）、`src/state.js`（full/patch 合并）、`src/render.js`（`h()` 手写 DOM）、`src/markdown.js`（惰性 DOM Markdown）、`src/main.js`（事件分发）、`src/render-gate.js`、`src/scroll-anchor.js`、`src/styles.css` |
| 契约 | `contract/moyu-chat-contract.d.ts`：intent 与视图模型类型，改动原生视图或 intent 时同步更新 |

## 构建与验证

每次改动后都要完整验证，失败不能报告为完成：

```bash
npm run verify
```

等价于：

```bash
npm run build
JAVA_HOME="$PWD/.tools/jdk-17.0.20+8" ./.tools/gradle-8.5/bin/gradle -p android --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- 系统 `JAVA_HOME` 可能是 JDK 8，必须使用 `.tools/` 下的 JDK 17（`scripts/verify.sh` 已处理）。
- Android SDK 来自环境变量 `ANDROID_HOME`（仓库没有 `local.properties`）。
- 额外任务可以作为参数传入，例如 `bash scripts/verify.sh :app:assembleRelease`。
- `scripts/static-check.mjs` 主要是针对源码的字符串断言（版本号、schema 版本、安全边界、关键结构）。修改它断言的代码时要同步修改断言，不要为了通过检查而删掉安全或边界断言。
- 版本号集中在 `static-check.mjs` 顶部的 `VERSION_NAME` / `VERSION_CODE` / `SCHEMA_VERSION`，并需与 `package.json`、`android/app/build.gradle`、`src/main.js` 的 `uiVersion`、`MoyuWebService` 的 User-Agent 保持一致。
- `RealEndpointSmokeTest` 需要真实凭据，默认跳过；跳过不代表外部端点已验证。

## 浏览器预览

```bash
npm run dev
```

打开 `http://localhost:5178/?f=<fixture>&theme=<system|light|dark>`。`dev/mock-host.js` 模拟 `MoyuHost` 和 `ChatCoordinator` 的 view.full / view.patch 信封（流式补丁按原生的 20 fps 节奏发送），`src/` 原样运行，CSP 与 `index.html` 相同。fixture 定义在 `dev/fixtures.js`，页面左侧的 DEV 面板可以切换 fixture、主题，以及触发流式、联网、出错和中断。`dev/` 不会被打包进 APK（`syncMoyuChatUi` 只复制 `index.html` 和 `src/**`，静态检查会核对这一点）。预览不能代替真机：软键盘、WebView 版本差异、系统字体都要在设备上确认。

## 工作方式

1. 先读代码再动手；每个阶段开始前先给出计划，等用户确认后再执行。
2. 分支：`claude/beta` 是 beta 主干；每个阶段在 `claude/<阶段>` 分支上开发，验证通过后提交一次，再快进合并回 `claude/beta`。未经要求不推送。
3. 每个阶段结束时用简短中文汇报：改了什么、为什么改、验证结果、遗留问题。

## 已移除的功能（不要恢复）

- Moyu Remote 跨 APK 桥接（RemoteDataBridge、Snapshot 导入、Handoff 回传）。
- ChatGPT / Claude 官方导出文件导入。以后最多考虑导入本应用自身、同协议版本的会话导出。
- schema 16 的迁移会删除 `handoffs` 表，以及不再被任何会话引用的快照。
