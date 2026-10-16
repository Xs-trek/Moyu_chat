# Moyu Chat 编排规格

这组文档定义会话、回合、上下文和编排模块**应该**怎样工作。代码是对规格的实现，测试验证规格的边界。

## 为什么要有它

之前 15 个审查问题（A01–A05、复审 3 项、N1–N7），都不是代码写错，而是以下语义从没写下来，每个阶段实现时各自给了答案：

- 分支的历史指什么；
- “模型已经看到的内容”按哪一轮算；
- 模块关掉后，它以前的产物还用不用；
- 记忆能不能看见兄弟分支；
- 前端和原生谁决定等多久。

规格把这些答案固定下来。之后的审查（包括 6Astra）以规格为基线：代码与规格不符算缺陷；规格本身有问题，先改规格。

## 机制来源

1. **机制以 Claude / GPT 为准。** 依据包括：
   - Anthropic 和 OpenAI 的官方 API 文档和工程文章；
   - 两家的 agent 应用（Claude Code、Codex CLI）；
   - 两家的产品行为（Claude、ChatGPT）。
   
   参照 **agent 应用**，不参照聊天应用。两家做法不同时，条目里写明选哪家和理由。
2. **其他项目只参考实现**，比如代码组织、边界处理、出错处理。它们的机制，只有在能证明更优时才采用（论文或可复现的评测），证明写进条目。
3. **Moyu 自有的机制**（不来自 Claude / GPT 的）逐条列在 [mechanisms.md](mechanisms.md#二moyu-自有的机制)，并写明保留、替换或删除。
4. **实验台本身的要求**不受第 1 条约束：档位、辅助开关、调用链、统计、对照组隔离。它们是产品要求，不是对话机制。

## 改动流程

编排、上下文、会话结构、数据归属方面的改动按这个顺序进行：

1. **先改规格**：新增或修改条目并编号。写明机制来源；取舍列为决策，由用户确认。
2. **确认架构**：指出条目归哪个组件负责（[05-runtime.md](05-runtime.md) 的归属表）。一个概念只能有一个负责的组件。
3. **实现**：代码注释引用条目编号，例如 `// spec C3`。
4. **测试**：静态检查守结构类条目；单元测试和端到端测试守行为类条目，测试名或注释引用编号。

界面样式、文案、动效不走这个流程（CLAUDE.md：界面偏好不需要论证）。

## 文件与编号

| 文件 | 内容 | 编号 |
|---|---|---|
| [mechanisms.md](mechanisms.md) | 逐项对照 Claude / GPT 和 Moyu 的机制，得出目标 | — |
| [01-conversation.md](01-conversation.md) | 术语；会话、消息树、分支、历史 | G、H |
| [02-turn.md](02-turn.md) | 回合、轮次、工具历史、可见性、取消、失败 | T |
| [03-context.md](03-context.md) | 上下文组成、预算、工具结果清除、压缩、回查、跨会话检索 | C |
| [04-modules.md](04-modules.md) | 档位、模块矩阵、开关、每个模块的契约 | M |
| [05-runtime.md](05-runtime.md) | 写入归属、线程、截止时间、观察 | R |
| [decisions.md](decisions.md) | 待决策、待修复、实施顺序、不成熟的部分 | D、N |

条目的状态标记：

- ✅ 现状符合；
- ⚠️ 现状不符，实现阶段修；
- ❓ 依赖待定的决策。

D1–D13 已于 2026-10-16 确认（全部采用推荐选项），对应条目标为 ⚠️，按 [实施顺序](decisions.md#实施顺序) 实现。以后新增的决策先标 ❓，确认后改为 ✅ 或 ⚠️。

## 参考来源

**机制来源**（Claude / GPT）：

| 来源 | 用在 |
|---|---|
| Claude API：[压缩概览](https://platform.claude.com/docs/en/build-with-claude/compaction)、[按需压缩](https://platform.claude.com/docs/en/build-with-claude/compaction-on-demand)、[保留最近几轮](https://platform.claude.com/docs/en/build-with-claude/compaction-keep-recent-turns)、[后台压缩](https://platform.claude.com/docs/en/build-with-claude/compaction-background) | C3 |
| Claude API：[context editing（工具结果清除）](https://platform.claude.com/docs/en/build-with-claude/context-editing) | C2 |
| Claude API：[web search 工具](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool) | M 联网、T3 |
| Anthropic：[Effective context engineering for AI agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) | C 总则、C4、子任务 |
| Anthropic：[Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) | 路由、校验修正、子任务 |
| Claude 帮助中心：[聊天搜索与记忆](https://support.claude.com/en/articles/11817273-use-claude-s-chat-search-and-memory-to-build-on-previous-context) | C5 |
| OpenAI：[Compaction 指南](https://developers.openai.com/api/docs/guides/compaction) | C3、D3 |
| OpenAI：[Agents SDK tracing](https://openai.github.io/openai-agents-python/tracing/)、[guardrails](https://openai.github.io/openai-agents-python/guardrails/) | R5、校验修正 |
| OpenAI：[GPT-5 system card（路由）](https://deploymentsafety.openai.com/gpt-5)、ChatGPT 帮助中心 [记忆](https://help.openai.com/en/articles/8590148-memory-and-newer-features) | 路由、C5 |
| Codex CLI：[`codex-rs/core/src/compact.rs`](https://github.com/openai/codex/blob/main/codex-rs/core/src/compact.rs)：回合前和回合后的自动压缩，保留最近用户消息 ≤2 万 token，摘要前缀 | C3 |
| ChatGPT“在新聊天中分支”、编辑产生分支（产品行为） | H5、H6 |

**有证明的其他机制**：

| 来源 | 证明 | 用在 |
|---|---|---|
| [MemGPT 论文](https://arxiv.org/html/2310.08560)（Deep Memory Retrieval 评测） | 只给前几次会话递归摘要的基线准确率 32–39%；能用工具回查原文的 MemGPT 达到 67–93% | C4 原文回查 |

**只参考实现**：

- LibreChat（`fork.js`：复制路径、重新生成 ID、去掉私有和运行时字段）；
- Open WebUI（`parentId` / `currentId` 消息树的存储方式）；
- LangGraph（按键写入、合并规则）；
- NextChat（压缩状态字段的组织方式）。

## 不在规格里的

- 账号、路线、模型能力：见 [../provider-config.md](../provider-config.md)。
- 界面：见 [../ui-design.md](../ui-design.md)。
- 功能清单和完成状态：见 [../status.md](../status.md)。
