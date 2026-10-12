# Moyu Chat 项目说明与审查材料

面向：fable5.1 项目审查。本文可独立阅读；实际代码审计仍需配合对应版本源码。

## 1. 文档依据与验证范围

| 项目 | 本地事实 |
| --- | --- |
| 编写日期 | 2026-09-05，Asia/Singapore |
| 项目目录 | `D:\ChatGPT_Workspace\moyu_chat` |
| 源码基线 | `eb8e52e786b95e04aadb2a64f1a9e24ee079729f` |
| 最近提交 | `Merge remote main`，2026-09-04 20:13:41 +08:00 |
| 应用版本 | `0.0.4`；Android `versionCode=4` |
| 本次变更 | 仅新增本文档，不修改业务代码或原有设计说明 |
| 源码规模 | 基线有 113 个 Git 跟踪文件；原生业务 Java 文件 49 个，JVM 测试文件 30 个，设备测试文件 2 个 |
| 本次实际验证 | `npm.cmd run check` 通过；`src/main.js`、`src/render.js`、`src/markdown.js` 的 `node --check` 通过 |
| 本次未验证 | Android 编译、JVM 测试执行、Lint、模拟器/真机、真实模型接口、Remote 对端、签名发布 |

本文依据本地源码、构建配置、类型契约、测试源码与现有 Markdown 文档整理，没有查询外部接口文档或调用真实服务。文中的模型名、能力字段和第三方地址只表示本地代码如何配置，不证明外部服务当前支持这些配置。

证据优先级：当前实现与构建配置 → 当前测试源码 → `README.md`、`ARCHITECTURE.md`、`LOCAL_AGENT_HANDOFF.md` → 历史设计与研究笔记。测试源码存在不等于测试执行通过；旧文档中的构建通过记录不作为本次版本的验证结论。

为便于转交，正文源码路径采用仓库相对路径。下文 `java/文件名` 统一指 `android/app/src/main/java/com/moyu/chat/文件名`；`test/文件名` 指 `android/app/src/test/java/com/moyu/chat/文件名`。审查问题附带的行号对应上述源码基线。

## 2. 产品定位与用户使用路径

Moyu Chat 是单用户、Android 本地存储的多模型聊天客户端。它通过用户配置的 Provider 直接发起模型请求，可选联网检索、图片消息、历史导入、会话分支，以及受限的同模型子任务调用。

正常聊天不要求 PC、Moyu Server、EasyTier 或 Moyu Remote 在线。这里的“本地”指界面资源、会话管理和数据存储位于 Android；应用没有内置推理引擎或模型权重，生成回答仍依赖可达的模型服务。

典型使用流程：

1. 在原生配置窗口新增 Provider，填写名称、API 根地址和凭据。
2. 为 Provider 添加 Model Profile，选择 Auto 或高级配置，并设置上下文与输出限制。
3. 新建会话，选择模型、Minimal / Standard / Smart、联网模式和推理强度。
4. 输入文字或选择图片，发送后查看流式正文、可选推理内容、来源和用量；可停止生成。
5. 可从当前会话创建分支，或从 ChatGPT / Claude 导出文件、可用的 Remote 桥接导入历史。
6. 需要交回 Remote 时，先创建本地 handoff，再显式提交；目标运行时的最终发送由 Remote 侧处理。

当前 UI 路由为 `home`、`conversation`、`providers`、`imports`、`handoffs`、`settings`。默认上下文模式为 Standard，默认联网为 Auto，跨会话记忆默认关闭，Standard 子任务默认关闭。

产品边界：提供 Chat 和有界工具调用，不提供 shell、电脑操作、任意文件修改、Android 设备控制、递归子代理或自主后台任务循环。源码对 Exa 有专用 MCP 调用实现，但没有面向任意 MCP 服务器的通用插件平台。

主要依据：`README.md`、`java/ChatCoordinator.java`、`contract/moyu-chat-contract.d.ts`。

## 3. 技术栈与模块分工

| 层次 | 技术与职责 | 关键文件 |
| --- | --- | --- |
| UI | 本地 HTML、CSS、原生 JavaScript ES modules，无前端框架或打包器依赖 | `index.html`、`src/main.js`、`src/render.js`、`src/styles.css` |
| UI 状态与桥接 | Intent 发送、完整视图与增量视图、修订号过滤 | `src/bridge.js`、`src/state.js` |
| 内容呈现 | 安全 Markdown DOM 构造、流式渲染节流、滚动锚点 | `src/markdown.js`、`src/render-gate.js`、`src/scroll-anchor.js` |
| Android 宿主 | WebView 资源隔离、原生配置窗口、系统文件选择器、外部链接 | `java/MainActivity.java` |
| 生命周期 | Application 级运行时、Activity 附着、生成期间前台服务 | `java/MoyuChatApplication.java`、`java/ChatRuntime.java`、`java/ChatTurnService.java` |
| 业务编排 | Intent 分发、冻结本轮配置、持久化、上下文、工具回合、终态处理 | `java/ChatCoordinator.java` |
| 本地存储 | SQLite、密钥加密保存、私有图片目录 | `java/ChatDatabase.java`、`java/ChatSecretStore.java`、`java/ChatMediaStore.java` |
| 配置与能力 | API 根地址、模型能力校验、静态 Catalog、推理参数 | `java/ProviderEndpoint.java`、`java/ConfigurationValidator.java`、`java/ModelProfileValidator.java`、`java/ModelCatalog.java`、`java/ReasoningPolicy.java` |
| 上下文 | 中立消息投影、预算、Checkpoint、FTS4 检索 | `java/ContextBuilder.java`、`java/ContextBudgetManager.java`、`java/ContextCheckpointService.java`、`java/LocalKnowledgeStore.java` |
| Harness | 模式与策略、任务分类、提示模块、验证与校准 | `java/ContextMode.java`、`java/HarnessPolicyResolver.java`、`java/TaskRouter.java`、`java/PromptPolicy.java`、`java/SmartVerifierService.java` |
| 模型协议 | OkHttp HTTP/SSE，请求编码、事件解析、取消与终态 | `java/ChatTransportRegistry.java`、`java/OpenAiCompatibleTransport.java`、`java/AnthropicMessagesTransport.java`、`java/TerminalSink.java` |
| 工具 | 联网决策、检索抓取、工具批处理、同模型子任务 | `java/WebOrchestrator.java`、`java/MoyuWebService.java`、`java/ClientToolRuntime.java`、`java/FocusedSubagentService.java` |
| 历史交换 | 官方导出解析、不可变快照、可选 Android 跨应用桥接 | `java/ConversationImportService.java`、`java/SnapshotService.java`、`java/AndroidRemoteDataBridge.java` |

```text
Android Application / ChatRuntime
    ├─ MainActivity / 本地 WebView
    │      └─ version=1 Intent ↔ full/patch ViewModel
    └─ ChatCoordinator
           ├─ ChatDatabase / ChatSecretStore / ChatMediaStore
           ├─ ContextBuilder → CanonicalPrompt → ContextBudgetManager
           ├─ ContextMode + HarnessPolicyResolver
           │      └─ 可选 Router / Checkpoint / Verifier
           ├─ WebOrchestrator / ClientToolRuntime
           │      └─ Web 检索抓取 / 同模型子任务
           ├─ ChatTransportRegistry → HTTP/SSE → 模型服务
           └─ Snapshot / Import / RemoteDataBridge
```

当前 `ChatCoordinator.java` 为 1,871 行，`ChatDatabase.java` 为 1,024 行；业务集中度较高，适合作为状态一致性和维护成本审查的入口。这是源码规模观察，不等同于已发现功能缺陷。

## 4. 核心数据模型与权威边界

### 4.1 Provider、Model Profile、Session 与 TurnConfig

系统坚持 `Protocol != Capability`：协议名称只规定消息格式，不能由此推断模型一定支持 reasoning、thinking、图像、联网、continuation 或客户端工具。

| 对象 | 管理内容 | 更新语义 |
| --- | --- | --- |
| Provider | 共用名称、Base URL、原生安全存储中的 API Key | 连接与账号层配置；Base URL 和凭据并不是 Session 冻结字段 |
| Model Profile | Model ID、协议、认证方式、推理字段/等级、thinking、模态、Web 声明、工具 wire、continuation、Harness、输入/输出限制、Catalog 来源 | 描述某个具体部署，而不是抽象模型品牌 |
| Session | 冻结的模型配置、会话模式、推理选择、联网选择、草稿、分支来源、派生上下文状态 | 新建或显式切换模型时冻结；修改 Profile 不自动重写旧会话的冻结字段 |
| TurnConfig | 当前请求使用的 Provider、凭据、模型、能力、预算与部署身份 | 发送时取得本轮配置，避免流式过程中 UI 设置变化改写在途请求 |

Provider Base URL 变化会重新解析受影响的 Auto Profile；若新地址无法匹配其已知部署，则要求先改用高级配置。Provider 与受影响 Profile 使用 SQLite 事务保存；凭据本身由独立安全存储管理，不属于同一 SQLite 事务。

主要依据：`java/ChatDatabase.java`、`java/ChatCoordinator.java`、`java/ProviderContinuationIdentity.java`。

### 4.2 SQLite 与媒体

数据库名为 `moyu-chat.db`，当前 schema version 为 **15**，与应用版本号分开演进。

| 表/存储 | 作用 |
| --- | --- |
| `providers` | Provider 连接记录，保留部分迁移兼容字段，不存 API Key |
| `model_profiles` | 部署配置；`(provider_id, model_id)` 唯一 |
| `sessions` | 会话与冻结能力、草稿、模式、Checkpoint 等 |
| `timeline` | `session_id + local_seq` 有序记录，JSON body 容纳消息、用量、错误等 |
| `snapshots` / `snapshot_items` | 快照元数据与不可变历史正文，记录来源、截断与内容哈希 |
| `knowledge_fts` | SQLite FTS4 全文索引，包含词项和来源定位 |
| `handoffs` | 本地交接包，状态为 `draft / submitted / failed` |
| `settings` | 应用开关、默认模式及派生校准等状态 |
| 私有 `chat-media` 目录 | 图片二进制；数据库只保留 mediaId、类型、尺寸、哈希等描述 |
| `moyu_chat_secure` SharedPreferences | 经过 Android Keystore 所持 AES-GCM 密钥加密的 Provider 凭据 |

会话可见历史是应用自己的数据事实来源。`CanonicalPrompt`、检索片段、Checkpoint、任务分类、验证状态、provider-private continuation 都属于派生状态，不能代替或暗中改写可见历史。

“预算不删除历史”不表示用户永远不能修改历史：显式编辑/回退会调用 `truncateTimelineFrom` 截断所选位置及后续记录，并使不再兼容的派生状态失效。分支则先冻结 Snapshot，再建立独立会话。

### 4.3 Catalog 的实际范围

`ModelCatalog.CATALOG_VERSION` 为 `2026-08-28-v1`。Auto 是本地硬编码的“API 根地址 + Model ID”匹配，不会查询 Provider 的模型列表。

当前源码中的部署组合包括：

| API 根地址字面值 | Model ID 字面值 | 本地配置方向 |
| --- | --- | --- |
| `https://api.openai.com/v1` | `gpt-5.6`、`gpt-5.6-sol`、`gpt-5.6-terra`、`gpt-5.6-luna` | Responses、显式 reasoning、图像输入、hosted web_search、client tools、native continuation |
| `https://api.anthropic.com/v1` | `claude-opus-5`、`claude-sonnet-5` | Messages、effort、图像输入、client tools；未启用 hosted Web 和 native continuation |
| `https://opencode.ai/zen/go/v1` | `glm-5.3-flash` | Chat Completions、部分 effort 等级；client tools 与 native continuation 关闭 |

上表仅转述代码，不作为真实服务兼容性承诺。上下文、输入与输出上限不会根据这些模型名自动推断；未识别部署应使用高级配置，旧配置迁移为 `legacy-manual`。

## 5. 一次聊天请求如何执行

1. UI 发送 `chat.session.send`，原生层校验会话、请求字段、图片能力和在途状态，取得本轮配置。
2. `beginTurn` 持久化原始用户文本与附件引用，清空草稿并建立 assistant 流式占位记录。`trim()` 只用于判空，不能改写用户正文。
3. 从本地 Timeline、来源 Snapshot、可用记忆与 Checkpoint 构造上下文；必要时执行 Task Router 或 Checkpoint 生成。
4. WebOrchestrator 决定本轮是否联网，再选择 hosted Web、Moyu 抓取/搜索以及可用的客户端工具。
5. 扣除提示策略、工具定义和协议状态的输入预算，调用选中的 transport。
6. SSE 增量更新正文、reasoning、来源、图片或用量；UI 接收带 revision 的局部视图更新。
7. 若模型返回客户端工具调用，校验并执行有界批次，携带对应协议状态回到同一用户轮次的后续请求。
8. Smart 按 Harness 策略选择是否验证；必要时最多修复一次，再验证一次。
9. 正常完成、取消或错误进入终态；有用的部分文本可保留并标记 `incomplete` 与 `terminalCode`。用量与会话状态落库，释放生成期间的运行资源。

Activity/WebView 可以被暂停或重建；运行时放在 Application 生命周期内。生成时启动 `dataSync` 前台服务并持有有超时的 WakeLock；进程被杀后不是无条件恢复网络任务，初始化阶段通过 `recoverInterruptedSessions()` 修复遗留流式状态。

## 6. 上下文、Harness 与回答验证

### 6.1 三种上下文模式

| 能力 | Minimal | Standard | Smart |
| --- | --- | --- | --- |
| 基础应用策略与完整最近轮次 | 有 | 有 | 有 |
| Memory / RAG 检索调用 | 无 | 按开关与策略使用 | 按开关与策略使用 |
| 模型生成 Checkpoint | 无 | 有 | 有 |
| 跨用户轮次 private continuation | 无 | 部署显式允许时可用 | 部署显式允许时可用 |
| Task Router | 无 | 无 | 由 Harness 决定，lean 默认关闭 |
| 回答 Verifier | 无 | 无 | 按任务与风险触发 |
| 同模型子任务 | 关闭 | 可选，默认关闭 | Auto，但可能实际调用 0 次 |
| Web | 独立的 off / auto / on 策略 | 同左 | 同左 |

跨会话记忆需同时满足全局开关和模式能力；全局关闭时 Standard / Smart 也不能使用跨会话记忆。显式来源 Snapshot 的使用与跨会话记忆开关是不同概念。

Harness preset 为 `lean / balanced / reinforced`，控制额外辅助的程度。它是本地策略分类，不是经过本文验证的模型质量排名。Smart 不是每次都调用 Router 和 Verifier：lean 在使用外部证据、发生上下文截断或使用子任务等情况下才触发验证；balanced 还关注 research/review/debug/compare/analyze；reinforced 覆盖更广，保留写作/创作例外。

**Minimal 的实际边界：**它禁用上下文 Harness 的额外调用，但 WebOrchestrator 不以 ContextMode 为开关；Web 开启时仍可能执行同模型联网规划。要审查“单次纯模型基线”，应把 Web 设置为 Off，并单独核实实际调用数。

### 6.2 预算与 Checkpoint

`ContextBudgetManager` 未使用精确 tokenizer。文本估算取 Unicode code point 数与 UTF-8 字节数所算结果的较大值，图片按尺寸分块估算，opaque continuation 也参与预算。

当前源码的有效输入预算公式为：

```text
safety = min(1024, max(256, contextTokens / 16))
totalInput = contextTokens - reservedOutput
declaredInput = inputTokens > 0 ? inputTokens : totalInput
effectiveInput = min(totalInput, declaredInput) - safety
```

省略限制时的内部默认值是 context 32,000、output 4,096；有效输入不足 512 时失败。例如这两个默认值下，effectiveInput 为 26,880，50% 触发点为 13,440，30% 软目标为 8,064。这些是代码计算示例，不是任何具体模型的真实容量。

压缩和取舍原则：

- Standard / Smart 在活动上下文超过有效输入的 50% 后计划生成或更新 Checkpoint，目标约 30%。
- Checkpoint 记录 summary、constraints、decisions、openQuestions、criticalRefs，并记录已覆盖的历史范围。
- 最新用户主导的完整轮次优先保留；被 Checkpoint 覆盖的旧内容避免重复进入请求。
- 30% 是软目标，不能为了达标静默裁剪当前用户请求；当前必需内容装不下时明确失败。
- 提示上下文取舍不删除数据库历史；FTS 检索是轻量词法检索，不含向量数据库或 embedding/reranker。

### 6.3 Smart 验证与校准

Verifier 使用同一模型的新上下文，检查候选正文、用户约束和应用掌握的证据，不能继承候选生成时的私有推理链作为结论依据。

验证状态分为 `valid / invalid / unavailable`；只有有效结果才能解释其 `pass / fail / partial` verdict。程序检查 JSON 结构、必需规则覆盖和 evidence ID 是否存在，不能直接信任模型输出的“通过”。

规则覆盖约束遵循、证据完整性、完成状态诚实性、不确定性表达和重要事实支持。有效且达到修复条件时，最多执行一次 repair 和一次新验证；验证无效或不可用时保留有用回答，避免宣称已验证。

当前校准策略版本为 `smart-verifier-v2`，冷启动修复风险阈值为 0.65，个人标签达到 30 条后才尝试使用学习校准。反馈标签为 `accurate / false_alarm / missed_error`，按具体部署身份与策略版本隔离。阈值属于本地启发式；本次没有验证其误报率、漏报率或统计有效性。

## 7. 模型协议与工具续接

| 协议 | transport | 在 Provider API 根地址后追加 |
| --- | --- | --- |
| `openai-chat-completions` | `OpenAiCompatibleTransport` | `/chat/completions` |
| `openai-responses` | `OpenAiCompatibleTransport` | `/responses` |
| `anthropic-messages` | `AnthropicMessagesTransport` | `/messages` |

认证选项包括 `bearer`、`x-api-key`、`api-key`，具体选择属于部署配置。云端地址要求 HTTPS，仅 loopback 本地模型服务允许 HTTP。Base URL 不能包含最终协议路径、用户名密码、query 或 fragment。

必须区分两种状态：

| 状态 | 生命周期 | 约束 |
| --- | --- | --- |
| 同一用户轮次内的工具协议状态 | 本轮多个模型请求之间 | 必须保持工具调用/结果配对及必要的原始 reasoning/thinking 状态，即使跨轮 continuation 关闭也可能需要 |
| 跨用户轮次的 private continuation | 完整回答之后的后续用户请求 | 需要模式许可、部署显式开启、身份和历史链兼容；属于可丢弃的派生优化 |

Responses 工具续接保留需要的 output items，并在后续 `function_call_output` 之前重放；Messages 保留适用的 thinking、redacted-thinking、tool_use 等 content blocks，再对应 `tool_result`。

工具定义、累计调用/结果和 exact provider round state 都参与预算。可以丢弃更早的普通聊天轮次；已经送出的工具结果及精确协议状态不能被改写，只有新产生且尚未送出的工具结果允许裁剪。必需状态装不下则明确结束并报告错误。

用过客户端工具的轮次不会将不完整的 private continuation 链保存给下一用户轮次，因为重放所需的工具历史只存在于本轮。修改模型、编辑/回退、分支/导入或部署身份不再兼容时也要阻止旧私有状态复用。

主要审查文件：`java/ContextBuilder.java`、`java/ProviderContinuationIdentity.java`、两个 transport、`test/ClientToolTransportTest.java`、`test/ProviderContinuationReplayTest.java`。

## 8. 联网与同模型子任务

### 8.1 联网实际决策

WebOrchestrator 结合用户文字、上下文、上一轮来源、当前日期和可选模型规划判断联网需求。模型规划预算为最多 700 输出 tokens、等待上限 10 秒；失败时保留确定性规划路径。

当前 `ChatCoordinator` 的选路逻辑不是简单的“有原生联网就始终优先”：

```text
hostManagedWeb = 需要联网 AND (
    Web 模式为 on OR 有明确 URL OR 部署没有 native search
)
providerHostedThisTurn = 需要联网 AND 有 native search AND NOT hostManagedWeb
```

因此 Web=On、明确 URL、缺少 native search 时会走宿主检索基线，即使部署另外支持客户端工具；兼容的客户端工具还可以提供后续搜索/抓取。工具定义放不进预算时，代码可以不暴露这些可选 schema，保留宿主联网基线。

当前服务端搜索通路是 `MoyuWebService` 对 `mcp.exa.ai` 的专用 Exa MCP 请求；抓取使用原生 HTTPS 并具有 Exa 抓取回退。实际查询词、目标 URL 会发送给相应服务。这里的“本地客户端”不意味着聊天和联网内容不会出设备。

Responses 的 hosted Web 支持显式声明的 `web_search`；Messages 要求本实现支持的具体 wire 名称，例如 `web_search_20260209`、`web_fetch_20250910`，不能把通用别名直接猜成 Messages schema。

检索正文是带来源的非权威上下文，不追加为数据库中的用户原始正文；assistant 可保存来源元数据。引用格式或 evidence ID 合法也不能单独证明网页实际支持回答中的全部事实。

### 8.2 客户端工具边界

| 项目 | 当前实现 |
| --- | --- |
| 工具白名单 | `web_search`、`web_fetch`、`delegate_subtask` |
| 允许的 wire | `openai-chat-tools`、`openai-responses-tools`、`anthropic-tools`，须与协议匹配 |
| 每批工具调用上限 | 8 |
| 单用户轮次工具批次/续接上限 | 4；这不是整轮全部模型 HTTP 请求数上限 |
| 子任务并行度 | 最多 4，客户端工具线程池大小也为 4 |
| 子任务深度 | 一层，子任务不获得递归工具循环 |
| 子任务模型 | 同一 Provider、Model 与指定 effort，使用独立上下文 |
| 子任务输入 | 自包含任务描述，超过 12,000 字符会截断 |
| 子任务等待 | 45 秒上限，输出预算按 Provider 设置并由辅助调用约束 |

需要关注的成本结构：一次用户请求可能包括 Web planner、Router、Checkpoint、多个主模型工具回合、子任务、Verifier、repair 和 re-verification。代码中的硬上限控制局部循环，不等于已有整轮价格上限或经过验证的延迟目标。

## 9. 历史导入、快照、分支与 Remote

官方导出导入由用户先选择 ChatGPT 或 Claude 来源，再通过系统文件选择器读取 JSON / ZIP。解析器仅提取可见 user / assistant 文本，排除 system / developer、工具权限、隐藏推理等。导入不是账号登录或云端同步，也不宣称完整恢复外部平台的所有消息类型。

导入会话持久化为本地 Snapshot，可基于 Snapshot 建立 Chat 分支。来自 Remote 的内容也先校验、复制入本地快照，再用于聊天，不能直接访问或挂接 Remote 数据库。

| 有界处理 | 源码上限 | 位置 |
| --- | --- | --- |
| 导入 JSON 累计字节 | 256 MiB，含所解析 ZIP JSON 的解压字节 | `ConversationImportService` |
| 导入会话数 / 单会话节点数 | 各 20,000 | `ConversationImportService` |
| 单条导入消息文本 | 200,000 字符 | `ConversationImportService` |
| 本地/Remote 快照 | 2,000 项、300,000 文本字符 | `SnapshotService` |
| 外部导出快照 | 10,000 项、2,000,000 文本字符 | `SnapshotService` |
| WebView 继承历史投影 | 500 项、512,000 字符，并按完整用户轮次选择 | `ChatCoordinator` |
| Remote 请求 / 响应 | 64,000 / 900,000 字符；来源列表最多 500 项 | `AndroidRemoteDataBridge` |
| 图片 | 单张最多 12 MiB，草稿最多 4 张；JPEG / PNG / WebP / GIF | `ChatMediaStore` |

这些字符、项数和字节上限分别约束不同层，不能互换。导入过程中已有成功会话后再遇到错误，会报告部分导入，而不是保证整份文件全有或全无。UI 仅截取继承历史不意味着数据库历史被删除，也不意味着模型实际收到了 UI 展示的全部内容。

Remote Bridge 使用 `content://com.moyu.remote.chatbridge`，契约版本 1，语义操作为：

- `status`：可用性和版本。
- `listSources`：可用的本地缓存来源。
- `createSnapshot`：请求指定来源、指定边界的快照。
- `submitHandoff`：显式提交由 Chat 创建的交接包。

Chat 侧检查 Provider 所属包名 `com.moyu.remote` 以及签名是否匹配。Manifest 请求 Remote 定义的签名级权限。桥接不存在、签名不符或版本不兼容时按不可用处理，正常独立聊天仍应可用。

本仓库包含 Chat 侧桥接客户端、契约和相关测试；没有 Remote APK 对端业务源码，无法仅据此证明对端权限、入箱和最终发送行为已正确实现。失败 handoff 不自动重发。

## 10. 界面契约、安全与隐私

### 10.1 UI 与原生交互

UI 经 `window.MoyuHost.postMessage` 发送：

```json
{"version":1,"type":"chat.session.send","requestId":"opaque-request-id","payload":{"localSessionId":"opaque-session-id","text":"用户输入"}}
```

原生经 `moyu:view` 事件返回 `view.full`、`view.patch` 或 `intent.result`。full/patch 具有 revision，前端忽略旧修订；局部更新无法合并时请求 `view.reload`。流式 patch 以会话 ID 与消息 localSeq 定位。

`contract/moyu-chat-contract.d.ts` 是共享说明性类型契约；当前 `package.json` 没有 TypeScript 编译检查步骤，不能假定它自动约束 Java 输出或 JavaScript 实际运行值。

### 10.2 已存在的控制措施

| 边界 | 当前代码措施 | 审查时需要保留的区别 |
| --- | --- | --- |
| WebView 资源 | 只映射 `appassets.androidplatform.net` 的本地 UI/media，禁止任意远程资源；关闭 DOM storage、Web SQL、file/content access 和 mixed content | 原生网络层仍会访问模型与搜索服务 |
| 页面 CSP | `connect-src 'none'`、`default-src 'none'`，限定本地脚本/样式/图片 | 应结合 WebView 实际行为检查，不能只看字符串 |
| Markdown | 用安全 DOM 文本节点构造内容，避免直接注入模型 HTML；不创建 Markdown 远程图片 | 模型文字仍可能误导用户，DOM 安全不等于事实准确 |
| 外部链接 | 通过原生 `ACTION_VIEW` 打开 HTTPS 链接 | 外部浏览器属于另一个内容与权限边界 |
| Provider 凭据 | Android Keystore + AES/GCM/NoPadding；配置使用原生窗口；ViewModel 只提供 hasCredential 等标志 | 本地 SQLite 和图片没有单独内容加密实现；不能将密钥加密扩写为全部数据加密 |
| 应用备份 | Manifest 设置 `allowBackup=false` | 不代表所有设备/调试/导出路径已完成审计 |
| Provider 地址 | 限制云端 HTTPS，HTTP 仅 loopback，拒绝 URL 内嵌凭据和 query/fragment | Provider 是用户配置的服务，不等同于任意模型生成 URL |
| Web Fetch | 公共 HTTPS、地址/DNS 校验、逐跳重定向检查、内容类型与响应体限制 | 需覆盖 IPv6、DNS 变化和回退路径后才能评价防护完整性 |
| Remote | 包名/签名校验、版本和大小限制、显式 handoff | 对端实现仍需要配套审计 |
| 工具权限 | 本轮暴露名称白名单、参数错误处理、批次/轮次/深度限制 | 提示语本身不能代替原生校验 |

Web Fetch 当前限制响应体 1 MiB、最多 5 次重定向；检索上下文最多 24,000 字符，单页抓取最多 12,000 字符，搜索结果最多 14,000 字符。这些是输入控制，未证明完整抵抗压缩包、超大图片尺寸或所有资源耗尽情形。

需要识别三条对外数据路径：模型请求（当前用户内容及选中的历史/图片/记忆）、Exa/目标站点请求（查询与 URL）、用户显式提交的 Remote handoff。凭据不应进入 WebView、聊天文本或检索服务；本次没有查看用户真实凭据、私有数据库或聊天内容。

## 11. 构建、运行与测试

### 11.1 本地构建配置

| 项目 | 当前配置 |
| --- | --- |
| Android 应用 ID / namespace | `com.moyu.chat` |
| Android SDK | min 26、compile 34、target 34 |
| Android Gradle Plugin | 8.2.2 |
| Java 编译语言级别 | Java 8 |
| 本地工具目录 | `.tools/gradle-8.5`、`.tools/jdk-17.0.20+8`；目录存在，但本次没有运行 Gradle |
| 本次 Node | v24.13.0 |
| 网络库 | `okhttp:5.0.0-alpha.11` |
| JVM 测试依赖 | JUnit 4.13.2、org.json 20240303、MockWebServer 5.0.0-alpha.11、Robolectric 4.11.1 |
| 设备测试依赖 | AndroidX runner 1.5.2、ext:junit 1.1.5 |
| Release | 开启 minify 和 shrinkResources；本地构建脚本未配置发布签名 |
| 许可证 | 仓库 `LICENSE` 为 Apache License 2.0；第三方依赖许可未在本次单独审计 |

Gradle 的 `syncMoyuChatUi` 把根目录 `index.html` 与 `src/**` 复制到 `android/app/src/main/assets/ui`，挂在 `preBuild`。生成资源被 `.gitignore` 排除，应编辑根目录 UI 源文件。

`npm run check` 与 `npm run build` 都只运行 `node scripts/static-check.mjs`。它们不生成 APK，也不进行真实 WebView 测试。直接打开 HTML 缺少原生 MoyuHost，不能等价替代 Android 应用运行。

Git 跟踪文件中没有 `gradlew` / `gradlew.bat` 或 Gradle Wrapper 配置。`.tools` 与 `android/local.properties` 被忽略，单独克隆仓库后需要另行准备工具链和 Android SDK。

### 11.2 可复现检查入口

以下是提供给审查者的执行入口，不表示本文已经执行过全部命令。

在项目根目录运行前端检查：

```powershell
npm.cmd run check
```

已准备好匹配工具链和 SDK 后，在当前 Windows 工作目录可使用：

```powershell
$env:JAVA_HOME = (Resolve-Path '.\.tools\jdk-17.0.20+8').Path
# 先通过 ANDROID_HOME 或 android/local.properties 指定本机 SDK。
& .\.tools\gradle-8.5\bin\gradle.bat -p .\android --no-daemon :app:testDebugUnitTest
& .\.tools\gradle-8.5\bin\gradle.bat -p .\android --no-daemon :app:lintDebug
& .\.tools\gradle-8.5\bin\gradle.bat -p .\android --no-daemon :app:assembleDebug
& .\.tools\gradle-8.5\bin\gradle.bat -p .\android --no-daemon :app:assembleRelease
```

设备测试入口为 `:app:connectedDebugAndroidTest`。桥接 absent / present 场景需要分别满足测试预设的 Remote 安装和签名条件，不能将任意单台设备的一次运行视为覆盖两个环境。

完整发布前检查要求见 `LOCAL_AGENT_HANDOFF.md`：从干净构建状态完成静态检查、JVM 测试、Lint、debug 与 release 构建、最终代码独立只读审查、Git 检查点及 APK 哈希。本文是审查材料，不是 APK 发布验收记录。

### 11.3 现有测试覆盖方向

| 主题 | 代表测试源码 |
| --- | --- |
| 数据库迁移、冻结配置、终态和快照 | `ChatDatabaseTest`、`ChatTurnOptionsTest`、`RemoteBranchHandoffTest` |
| 三种协议、SSE、受控网络与取消 | `TransportMockServerTest`、`ProviderEndpointTest`、`ChatCoordinatorE2eTest` |
| 工具 wire 与 exact round replay | `ClientToolTransportTest`、`ClientToolRuntimeTest`、`ProviderContinuationReplayTest` |
| 模式、预算、Checkpoint、上下文来源 | `ContextModeTest`、`ContextBudgetManagerTest`、`ContextCheckpointServiceTest`、`SmartContextV002Test`、`ChatCoordinatorPromptEvidenceTest` |
| Router、提示策略、reasoning、Catalog | `TaskProfileTest`、`PromptPolicyTest`、`ReasoningPolicyTest`、`ModelCatalogTest`、`ModelProfileValidatorTest` |
| 检索、联网、导入与轮次边界 | `LocalKnowledgeStoreTest`、`WebOrchestratorTest`、`MoyuWebServiceTest`、`ConversationImportServiceTest`、`SnapshotTurnBoundaryTest` |
| 验证与校准 | `SmartVerifierServiceTest`、`SmartVerifierCalibrationTest` |
| UI 投影与继承历史 | `ChatCoordinatorDisplayTest`、`SnapshotBranchContextTest` |
| Remote 可用/缺失 | `RemoteBridgeAbsentTest` 及 `androidTest` 中的两个 Bridge 测试类 |
| 可选真实服务 | `RealEndpointSmokeTest` |

本次实际结果：

```text
node --version
v24.13.0

npm.cmd run check
moyu-chat v0.0.4 static architecture checks passed

node --check src/main.js
node --check src/render.js
node --check src/markdown.js
以上语法检查成功退出，无错误输出。
```

`static-check.mjs` 主要检查代码模式、关键不变量字符串、UI 禁用能力和语法等。不能从静态检查通过推导所有逻辑路径正确，也不能用它替代 TypeScript 检查、Android 编译或真实交互验证。

真实服务测试要求 `SMOKE_REAL=1` 及相应凭据变量，可通过 `SMOKE_OPENAI_PROTOCOL` 选择协议。它只验证实际输出、完成和单终态等基础行为，不代表已经覆盖某个 Catalog 组合的图像、工具或 reasoning 全部能力。Anthropic 默认地址另见下面的已确认差异。

## 12. 已确认的本地不一致

下列是通过对照本地文件确认的事实；影响判断仍需审查者评估。本次只记录，没有修改它们。

| 编号 | 本地证据 | 影响与建议核查 |
| --- | --- | --- |
| D1 | `test/RealEndpointSmokeTest.java:109` 的 Anthropic 默认根地址仍为 `https://api.anthropic.com`，文件头示例也如此；`java/AnthropicMessagesTransport.java:118` 只追加 `/messages` | 不覆盖环境变量时会构造 `/messages`，与当前项目要求的 `/v1/messages` 路径不一致。应修正示例和默认值，之后实际运行真实服务测试；本次没有发送该请求 |
| D2 | `java/ChatCoordinator.java:687` 写入 `streamStage=web_planning`；`src/render.js:286` 已呈现此状态；`contract/moyu-chat-contract.d.ts:209` 的 `TimelineItem.streamStage` 联合类型缺少它 | 原生、UI 与共享类型契约不一致。当前界面有显示分支，不能直接称为界面崩溃；应同步契约并考虑建立契约校验 |
| D3 | `PROMPT_CONTEXT.md` 的 Minimal 说明包含无额外预处理模型调用；`ChatCoordinator.send` 在各模式均进入 WebOrchestrator，后者 `plan()` 可以调用模型 | Minimal + Web Auto/On 不保证只有一次模型请求。应明确基线定义、成本呈现与测试条件，再决定改文档还是改策略 |
| D4 | `README.md` 对 Web 优先级的说明较概括；`ChatCoordinator` 在 Web=On、明确 URL 或无 native search 时强制选择宿主检索基线 | 文档需要描述真实选路和强制联网含义，避免误认为客户端工具只在没有 hosted Web 后才是唯一回退 |
| D5 | `PROMPT_CONTEXT.md` 标为 v0.0.2，并含较旧的预算表达式与 Smart 描述；`RESEARCH_NOTES.md` 仍把协议/认证归 Provider 并称工具抽象未采用 | 历史说明与 v0.0.4 的职责划分、选择性验证和工具实现混杂。建议标注历史范围或同步当前说明 |

## 13. 交给 fable5.1 的优先审查问题

本节属于**待验证问题**，不预先认定为漏洞，也不以“有测试文件”视为解决。

1. **数据与终态一致性。**发送刚接受、上下文准备失败、停止生成、工具执行中取消、Verifier 中取消、切换 Activity、杀进程时，Timeline、草稿、session state、用量和前台服务是否一致？取消后迟到的 callback 是否可能再次写入？审查 `ChatCoordinator`、`ChatRuntime`、`ChatTurnService`、`TerminalSink` 和数据库的 begin/finalize/recover 路径。
2. **会话冻结与凭据更新。**Profile 修改不影响旧会话的冻结能力，但共享 Base URL/凭据会影响后续请求。切换到能力不同的新 endpoint 时，旧会话的 frozen wire 是否仍有效？凭据加密存储与 SQLite 提交之间发生失败时是否保留一致状态？continuation 和 verifier calibration 的部署指纹是否同步失效？
3. **跨协议工具回放。**覆盖多工具同批、多批工具、空/畸形参数、重复 ID、thinking 签名缺失、redacted thinking、budget 超限和用户取消。检查 provider 原始对象顺序、配对和不可改写约束，而不仅是文本回复是否出现。
4. **上下文损失与输入预算。**启发式 tokenizer、图像估算、工具 schema、错误结果、私有状态是否漏算？旧轮次、Checkpoint、Snapshot、当前用户请求是否会重复、孤立或被静默裁剪？50%/30% 是否在声明 input cap 较小情况下保持一致？
5. **Web 决策与证据。**Web=Off 是否所有网络辅助路径都关闭；Web=On 是否确实取到证据后才宣称联网；Auto 在普通问句下触发 planner 的成本是否合理；提供给 Verifier 的来源是否确实属于本轮可见证据？审查明确 URL、追问、抓取失败、搜索无结果和预算裁剪后的状态。
6. **外部数据注入与数据外发。**导入角色、Checkpoint、跨聊天记忆、网页、子任务输出中的指令是否可能获得应用权限？Web Fetch 的 DNS、重定向、IPv6 与 Exa 回退是否保持同一公共地址边界？错误正文、日志、view.full/patch 是否可能携带凭据或原始私有协议状态？
7. **Smart 价值与成本。**相同模型的独立验证是否能提供净收益？证据 ID 的存在与证据支持结论是否被混淆？0.65 阈值、30 条标签、任务分类缓存和一次修复策略是否需要实际数据支持？需测准确性、延迟与 token 消耗，不能仅凭结构完整宣称可靠性提升。
8. **大数据与移动端资源。**验证 256 MiB 导入边界、ZIP 中未解析条目、200,000 字符消息、大量本地 Timeline、500 项继承投影、12 MiB 但像素极大的图片、FTS 重建失败、媒体 GC。当前继承历史有投影上限，本地 Timeline 与全量视图路径的长期内存压力仍需实测。
9. **构建与部署能力证据。**Catalog 注释称 reviewed/verified，但本次未核验每一组合的原始兼容性记录。要求补齐可重复请求样例和精确能力测试；同时检查缺少 Wrapper、工具链不入库、alpha 网络依赖、发布签名和 Remote 同签名部署对复现的影响。
10. **产品范围是否保持清晰。**区分本地数据管理、远程模型推理、受限子任务和自主 agent；为用户明确 Web、记忆和子任务的行为与费用，而不是用模式名称隐去实际额外调用。

建议首先核对 D1–D5，再从取消/终态、工具回放、配置身份、上下文预算四条关键链路展开。拆分大型类应以可验证的责任边界为前提，避免为了形式统一引入框架或重写整个应用。

## 14. 建议的审查输出格式与转交内容

可以将下面文字与本文一起交给 fable5.1：

> 请审查 Moyu Chat v0.0.4。本文是根据本地源码整理的说明，源码基线为 eb8e52e786b95e04aadb2a64f1a9e24ee079729f。请先核对产品边界、Provider/Profile/Session 职责、上下文模式、Web 选路与工具协议状态，再审查状态一致性、安全边界、成本和测试覆盖。
>
> 请把结论分为：已证实缺陷、待验证风险、设计权衡、文档/契约差异。每项提供优先级、具体触发条件、证据路径与函数/行号、用户影响、最小修复建议和验证办法。没有代码证据时不要把疑点表述为已复现 bug；只有本文时，请明确这是文档级审查，并列出需要补充的源码。
>
> 请重点核查第 12、13 节。不要把历史构建记录、测试源码存在或静态检查通过视为真实服务兼容性与发布质量已通过。修改建议应保留本地持久历史、不可变快照、部署显式能力声明和有界工具执行的基本设计。

若进行源码审计，建议同时提供同一基线的 `src/`、`contract/`、`android/app/src/main/`、两个测试目录、`scripts/static-check.mjs`、Gradle 配置和 `package.json`。无需提供 `.tools/`、缓存、真实 API Key、用户私有数据库或聊天导出。

原有资料用途：`README.md` 提供当前概览，`ARCHITECTURE.md` 提供架构原则，`LOCAL_AGENT_HANDOFF.md` 给出发布检查要求，`REMOTE_BRIDGE_CONTRACT.md` 说明跨 APK 契约，`V0.0.1_DESIGN.md` / `V0.0.2_DESIGN.md` 与研究笔记用于理解历史演进。本文中的现状结论仍应以对应源码为准。
