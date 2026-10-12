# 更新记录

## v0.0.6-beta — 2026-10-12

Claude 接手后的 beta 线，基于 v0.0.5（`32479203`）。applicationId 改为 `com.moyu.chat.beta`，可与原版并存安装；versionCode 6，SQLite schema 1（beta 线重新计数，库文件 `moyu-beta.db`）。

### 调用链（阶段 2b）

- 新表 `turns`（每个回合一行：冻结的回合配置、用量汇总、状态与结束原因）和 `turn_spans`（每个阶段一行：状态、跳过原因、耗时、该阶段的模型调用与 token、判断摘要）。仍并入 beta schema 1。
- 用量改存在 `turns` 上，时间线只保留用户看得到的消息和错误；视图里由原生在原来的位置投影出 `kind: usage` 条目，前端用量显示不受影响。今日用量改按 `turns.started_at` 查询。回退、删除会话、冷启动恢复（进程被杀的回合记为 `process_died`）都覆盖回合记录。
- 新 intent `turn.trace.get`：按需读取一个回合的调用链；`intent.result` 可以携带 `data`，前端 `requestIntent()` 等待结果。
- 界面：回复底部展开详情后，新增“调用链”一栏。每个阶段一行，带耗时比例条、耗时、一句话说明（路由分类与置信度、是否联网与查询数、来源数、校验结论与风险、是否修正等）、调用次数和 token；跳过的阶段显示原因；标题行显示“辅助调用占 N% token”。默认折叠，展开时才读取，读过的缓存在内存里。
- 浏览器预览：mock host 按会话模式生成调用链。
- 测试：回合记录的配置 / 用量 / 阶段落库与读取、回退进某回合时删除该回合及之后的记录、冷启动把进行中的回合记为失败、视图中用量条目的位置；`TurnPipelineTest` 改为从数据库读调用链。

### 回合管线（阶段 2a）

行为不变的重构。

- 原来约 700 行的 `ChatCoordinator.send()` 拆成 `TurnPipeline` 的明确阶段：接收、上下文、路由、压缩检查点、联网规划、工具选择、联网检索、生成、校验/修正、收尾。生成阶段（多轮主调用、客户端工具轮次、流式投影）独立为 `ProviderRoundLoop`；回合状态集中在 `TurnRun`，不再靠十几个局部数组在闭包间传递。`ChatCoordinator` 从 1846 行降到约 800 行，只保留 intent、会话和视图。
- 新增 `TurnTrace`：每个阶段记录状态、耗时和判断摘要（路由分类与置信度、联网是否触发及搜索词、工具与联网执行方式、证据来源数、校验结论 / 风险 / 是否修正），被跳过的阶段记录原因。
- `TurnUsage` 按发起阶段归属每次模型调用：路由、压缩、联网规划、校验、修正、子任务分别计数和计 token，原来的主调用 / 辅助调用 / 子任务汇总不变。
- 清理：去掉上下文模式 `auto` 的兼容常量；`WebOrchestrator.legacyPlan` 改名为 `rulePlan`（规则规划，不调用模型）。
- 开发原则写入 CLAUDE.md。
- 测试：新增 `TurnPipelineTest`，覆盖三档模式各自运行和跳过的阶段及原因、辅助调用按阶段计费、在准备阶段停止时不会发出主调用。调用链目前只保存在内存里，下一步随回合持久化。

### 数据库基线（beta schema 1）

不保留旧数据：beta 线使用新的库文件 `moyu-beta.db`，schema 从 1 重新计数；原版的 `moyu-chat.db` 启动时直接删除，版本号更新的文件删除重建；原版 v1→v16 的迁移链和对应测试一并移除。设计参照 OpenAI Agents SDK 的 `SQLiteSession` / `AdvancedSQLiteSession`：会话表加有序的 JSON 条目日志，用量、分支、校验这类结构化信息放在侧表。

- `providers` 只存共享 endpoint（名称、Base URL），去掉 protocol / auth / reasoning wire / model / 上下文上限等旧列；只填 Provider 就自动生成模型的一步式创建和 `legacyProfile` 一并删除。
- `model_profiles` 去掉无效的 `default_reasoning`；配置模式只剩 auto / manual。
- `sessions` 保留“创建或切换模型时冻结部署能力”的语义，新增 `created_at`；与 Provider、Model Profile、父会话、来源快照之间加上外键（删除父会话时把子会话的父指针置空）；按 `updated_at` 建索引。
- `timeline` 新增 `kind` 列，与条目里的 kind 一致。今日用量按这一列查询，不再在 JSON 文本里匹配；去掉与 UNIQUE 约束重复的索引；冷启动恢复只解析可能处于流式状态的行。
- `snapshots` 只描述本地分支：去掉 `source_type` / `source_runtime`，`source_id` 改名为 `source_session_id`；提示词里“从其他运行时导入”的分支说明随之删除。视图 `origin` 去掉 `sourceType` / `sourceRuntime`，新增 `sourceSessionId`。
- 原来塞在 settings 表里的状态各回各的位置：凭据代次放到 `providers.credential_epoch`，知识索引标记放到 `sessions.knowledge_indexed_at`（随会话级联删除、回退时清空），校验反馈标签放到新的 `verifier_labels` 表（每个校准键保留最新 200 条）。settings 表只放偏好设置。
- 部署身份指纹改为从会话冻结的 protocol / authMode 计算，传入共享 Provider 还是部署视图结果都一样。
- 启动时清理：删除没有对应 Provider 的 API Key，以及没有任何引用的本地图片。
- 删除旧的 `context.intelligence.set` intent 和 `smartContextEnabled` 设置回退。
- 测试：新增 `TestDeployments` 辅助类（Provider 加一个默认 Model Profile）；新增基线重置、kind 列、凭据代次、索引标记、校验标签上限、会话创建时间与父子关系等测试。

### 前端重做（阶段 1）

依据 `docs/frontend-audit.md`。数据库 schema 不变。

- 视觉：大面积底色降亮降饱和（浅色灰绿纸面、深色青炭灰，不用纯白纯黑），回答放在略亮的面板上，以 1px 细线分层；代码、表格、引用各有底色。设置里可选灰绿 / 暖米 / 中性三种底色。
- 深色跟随：原生按 Android 夜间模式解析“跟随系统”，并按同一 canvas 颜色设置窗口背景、状态栏和导航栏；新增 `values-night` 窗口主题，冷启动不再白屏；系统栏图标深浅在 Android 11+ 用 `WindowInsetsController`，8–10 回退到旧的 View 标志。修正视图 `resolvedTheme` 在“跟随系统”时恒为 light 的问题。
- 结构：侧边抽屉取代底部 Tab（宽屏 ≥960px 时固定在左侧）；标题栏只剩菜单 / 标题 / 更多；输入区合并为“附件 + 本轮设置 + 发送”，模型、推理、联网放进一个底部面板；回复底部收成一行用量摘要，点开看按主调用 / 辅助调用 / 子任务分组的完整指标；回退、分支、编辑收进菜单。所有可点区域 ≥44px；恢复时间线细滚动条；图片预留原始宽高比。
- 流式 Markdown：按块增量渲染，已完成的块不重建，未闭合的代码围栏直接显示为代码块；结束时保留 DOM，不再整块替换。
- 小鱼与彩蛋：16×9 像素小鱼随回合阶段换动作（推理吐泡泡、联网举放大镜、输出摆尾、校验戴眼镜、完成溅水花、出错翻肚皮、深夜打瞌睡）；校验小印“已校 / 已改 / 存疑”、“秒回”“满塘”、时段问候、戳小鱼语录、token 里程碑提示、今日用量、节令配件。动画只改 transform / opacity，空闲时完全静止；设置里有“动效与彩蛋”开关，并尊重系统的减少动画设置。
- 前后端边界：新增 `src/view-model.js` 作为解读原生视图的唯一适配层；抽屉、面板和对话框移出 `#app`；Android Back 先关闭前端浮层，从 Provider / 设置页返回时回到打开的会话。
- 新增接口：`appearance.set` 接受 `tone`；新增 `ui.delight.set`；视图新增 `appearance.tone` / `appearance.delight` 与 `usageToday`（只读当天有更新的会话，经会话索引查询，并按会话更新时间缓存）。
- 浏览器预览：`npm run dev` 用 mock host 原样运行 `src/`，fixtures 覆盖空会话、长会话、流式、推理、代码表格、联网来源、错误与分支；`dev/` 不打包进 APK。
- 静态检查：逐字比对 CSS 的断言改为按含义检查（44px 触控、主题 token、大面积不用纯白纯黑、动画只动 transform/opacity、不用模糊滤镜、不拼 HTML 字符串等），JS 语法检查改为 `node --check`。

#### 验证

- `npm run verify` 通过：静态检查；29 个测试类、216 项测试，0 失败，3 项真实端点 smoke 按凭据开关跳过；新增外观 / 今日用量 / Back 路由的 E2E 测试。Debug Lint 0 错误，5 项原有警告。
- 浏览器预览（375×812 与 1280×800，浅色 / 深色 / 三种底色）逐屏检查：会话列表、空会话、长会话导航、流式输出与结束、错误与中断、本轮设置面板、抽屉、设置与 Provider 页；一次完整流式回合、回退、切换推理显示和 Back 关闭浮层无脚本错误。
- 未在真机上验证：WebView 主题跟随、系统栏颜色、软键盘与 Back 需在设备上确认。

### 清理（阶段 0）

- 删除 Moyu Remote 跨 APK 桥接：`RemoteDataBridge` 及其实现、Snapshot 导入、Handoff 回传、Manifest 中的 `com.moyu.remote` 权限与 `<queries>`、前端“回传”页、Remote 徽章与相关 intent / 视图字段 / 契约类型。视图生成时不再每次跨进程查询桥接状态。
- 删除 ChatGPT / Claude 官方导出导入：`ConversationImportService`、导入文件选择器、“分支/导入”页、导入快照的复用与删除逻辑，以及跨 Chat 记忆中对导入源的检索。
- 本地分支保留：快照只由本地分支创建，最后一个引用它的会话被删除时一并回收。
- schema 16 迁移：删除 `handoffs` 表；删除不被任何会话引用的快照及其检索索引。仍被会话引用的旧快照作为不可变历史保留。
- 清理仓库：Codex 时期文档归档到 `docs/history/`；删除 `REMOTE_BRIDGE_CONTRACT.md`、`.__probe__.txt`、只覆盖 Remote 桥接的 `androidTest`；新增 `CLAUDE.md` 和 `npm run verify`（`scripts/verify.sh`）。
- 本地的 `build/releases/v0.0.5/` 构建归档已删除，下方 v0.0.5 记录中引用的产物不再保留。
- 顺带删除早已无调用的死代码：`ConfigurationValidator.validateProfile`（已由 `ModelProfileValidator.validateResolvedProfile` 取代）、`ProviderContinuationIdentity` 的会话历史 epoch、`ChatTurnOptions` 与 `ChatDatabase` 中的几个未用辅助方法。

### 验证

- `npm run verify`：静态检查通过；`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过。
- 29 个测试类、215 项测试：212 通过，0 失败，3 项真实端点 smoke 按凭据开关跳过。新增 schema 16 迁移测试、分支快照回收测试，以及“已删除 intent 被拒绝”的回归测试。
- Debug Lint 0 错误，5 项均为原有警告。Debug APK 的 applicationId 为 `com.moyu.chat.beta`，versionName 为 `0.0.6-beta`。
- 未在真机上安装验证；Release 构建本阶段未执行。

## v0.0.5 — 2026-09-06

基于 v0.0.4（`eb8e52e786b95e04aadb2a64f1a9e24ee079729f`）的修复版本，Android versionCode 升至 5，SQLite schema 保持 15。

- 修复用量漏计：Router、Checkpoint、Web planner、verifier、repair、复核请求与子任务均纳入回合统计；失败、超时和取消保留已经报告的用量。同一请求的累计回调不会重复计算，缺失用量明确标记为未知或部分已报告。
- 修复导入资源边界：在流式 JSON 解析前限制单个原始 token 为 1 Mi 字符、单会话 JSON 为 8 Mi 字符、嵌套 128 层；节点超限立即停止，已完成的会话保留并报告。保留已有格式清洗、分支选择和总文件容量限制。
- 修复图片资源边界：写入前限制 16,777,216 像素、单边 8,192 像素，保留 12 MiB 文件限制；对模型图片返回值增加 Base64 长度检查。
- 修正 Anthropic 真实端点 smoke 的默认 API 根路径为 `/v1`；补齐 `web_planning` 与用量字段契约。
- 更新当前开发说明；v0.0.4 项目审查文档和 v0.0.2 上下文设计保留为历史基线。

本次对应已核实的 R2、R4、R9、D1、D2 和文档漂移；未扩大工具调用上限、改变开发方向、引入新依赖或调整 Provider/Model/Session 架构。

### 验证

- `npm run build` 静态架构与 JavaScript 检查通过，包括取消回合用量不覆盖上一条回复的回归检查。
- 从 `:app:clean` 开始，执行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`，完整构建通过。
- 32 个测试类、231 项测试：228 通过，0 失败，0 错误；3 项真实端点 smoke 按凭据开关跳过，未验证外部服务。
- Debug Lint 为 0 错误、5 项现有配置/依赖提示；Release Lint/R8/资源优化通过。两份 APK 元数据均为 versionName `0.0.5`、versionCode `5`。
- 最终源码与差异已单独只读复核，`git diff --check` 通过。
- 本机未连接 Android 设备，设备测试未执行。Release APK 为未签名产物。

本地产物归档在 `build/releases/v0.0.5/`（Git 忽略目录），含 Debug APK、未签名 Release APK、SHA256SUMS 和完整构建日志。
