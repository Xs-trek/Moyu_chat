# Moyu Chat

Moyu Chat is an Android-local Chat client with optional bounded tools. It shares the offline WebView + native glue style of Moyu Remote, but its core path does not depend on Moyu Server, EasyTier, Claude Code, Codex or OpenCode being online.

Current version: **v0.0.5** (Android versionCode 5, SQLite schema 15). This maintenance release fixes turn-wide usage accounting, import memory bounds, image dimensions, and smoke/contract mismatches. See `CHANGELOG.md`.

## Product boundary

- Chat sessions and durable visible history are owned by Moyu Chat and stored in its own database.
- Provider credentials remain in Android native secure storage and never enter the WebView or SQLite.
- Remote/imported history enters Chat only through immutable local snapshots; local branches do not mutate the source history.
- Provider-private reasoning/thinking/continuation state is derived protocol state, never the durable source of truth.
- v0.0.4 supports bounded client `web_search`, `web_fetch`, and isolated same-model `delegate_subtask` when the concrete deployment explicitly supports the corresponding client-tool wire.
- Moyu Chat is not an Android agent runtime: no shell, computer use, arbitrary filesystem mutation, device control, recursive subagents or autonomous background task loop is exposed.

## Provider, Model and Session

`Protocol != Capability` is a hard invariant.

- **Provider** is shared connection/account data: display name, Base URL and API key.
- **Model Profile** describes one concrete deployment: Model ID, protocol, auth mode, reasoning wire/levels, modalities, hosted-Web declaration, thinking, continuation, client-tool wire, Harness preset and context/input/output limits.
- **Session** freezes the resolved deployment fields when it is created or explicitly switched to another Model Profile. Editing a profile later does not silently relabel an existing Chat.

Supported protocol families are `openai-chat-completions`, `openai-responses` and `anthropic-messages`. They define wire shape only. Reasoning, thinking, hosted Web, images, native continuation and client tools must be explicitly declared by the concrete deployment.

## Model Catalog and configuration

v0.0.4 provides a small reviewed Model Catalog for known deployment overlays. Auto mode is allowed only when the endpoint + model matches a reviewed overlay. An unknown or same-named third-party route must use **Advanced** configuration rather than guessing a protocol or capability.

Context window, maximum input and maximum output remain explicit deployment/user inputs even in Auto mode. The catalog does not infer these limits from a model name. Existing v0.0.3 profiles migrate as `legacy-manual` and remain authoritative until the user explicitly changes them.

Changing a Provider Base URL revalidates and re-resolves affected Auto profiles atomically. A change that would leave an Auto profile without a reviewed deployment overlay is rejected and requires that profile to be switched to Advanced first.

## Context modes and adaptive Harness

- **Minimal** — thin native Chat baseline with complete recent user-led turns. No Memory/RAG caller, Checkpoint, Router, verifier, subagent or provider-private continuation.
- **Standard** — production Chat context management: optional global Memory/RAG, Snapshot retrieval, proactive compaction and explicitly enabled provider continuation. Reliability prompt modules are selected by the frozen `lean / balanced / reinforced` Harness preset. Subagent use is optional and defaults Off.
- **Smart** — Standard plus adaptive Router/evidence/verifier behavior where the Harness policy warrants it. Subagent mode defaults Auto, but Auto permits zero child calls for simple tasks.

Subagents are isolated same-model calls, not device agents. Maximum parallel subagents is 4 and nesting depth is one. A child receives a bounded task context and cannot recursively obtain another client-tool loop.

Standard/Smart proactive compaction uses:

```text
trigger = 50% of effective input window
target  = approximately 30%
```

Durable history is never deleted by prompt budgeting. The disposable model prompt keeps complete newest user-led turns and drops older whole turns when necessary.

## Client tools and same-turn protocol state

Client-tool execution is bounded to at most 8 calls in one batch and 4 provider tool rounds in one user turn.

Tool definitions, accumulated tool call/results and exact provider round state participate in the input budget. Older ordinary Chat turns may be omitted from a later provider round to make room. Already-sent tool results and exact provider reasoning/thinking state are never rewritten; only a newly produced, not-yet-sent tool result may be clipped to fit. If the mandatory tool/protocol state still cannot fit, the turn fails explicitly instead of sending an over-context request.

OpenAI Responses same-turn replay preserves the response output items needed before `function_call_output`. Anthropic Messages same-turn replay preserves the applicable thinking/redacted-thinking/tool-use content blocks before `tool_result`. This same-turn protocol history is separate from optional cross-user-turn provider-private continuation.

## Web

Provider-native hosted Web has priority when explicitly declared. Otherwise, a deployment with explicit client-tool support may use Moyu typed `web_search` / `web_fetch`; deployments without client tools can still use the bounded deterministic Moyu Web fallback.

Web mode is independent of Minimal/Standard/Smart. Web On and explicit URLs can trigger bounded host retrieval even when client tools are available; supported hosted Web remains preferred. The semantic planner can itself make an auxiliary model request.

For Responses, the currently supported generic hosted capability is `web_search`. For Messages, hosted Web requires an explicitly declared supported Anthropic wire version such as `web_search_20260209` or `web_fetch_20250910`; generic aliases are not converted into a Messages schema.

Fallback search/fetch material is ephemeral untrusted context and is not written as user-authored timeline text.

## Endpoint roots

Provider Base URL is the shared API root; transports append the protocol endpoint:

- Chat Completions: `/chat/completions`
- Responses: `/responses`
- Messages: `/messages`

For the official Anthropic endpoint the shared root is therefore `https://api.anthropic.com/v1`.

## Usage and local media/import limits

Turn usage includes reported main, auxiliary (Router, Checkpoint, Web planner, verifier/repair), and subagent requests. Provider-reported usage is retained even when a helper fails, times out, or is cancelled. Missing usage stays unknown; partial totals are labeled accordingly. Hosted tool fees and money prices are not inferred from token counts.

Call counts refer to logical transport invocations; Anthropic internal `pause_turn` HTTP continuations belong to one such invocation and retain transport-accumulated usage. These counters are not an invoice or a raw HTTP request count.

Image input/output is limited to 12 MiB encoded bytes, 16,777,216 pixels and 8,192 pixels on either side; up to four draft images remain supported. Oversized images are rejected before private media files are written.

Official ChatGPT/Claude imports use streaming JSON. Parsed JSON is limited to 256 MiB across selected ZIP entries, each raw JSON token to 1 Mi characters, each conversation to 8 Mi raw JSON characters, and nesting to 128 levels. Escapes count as written in JSON. Existing 20,000-conversation/node and 200,000-character message limits remain. A safety-limit failure stops the import and reports already completed conversations.

`PROMPT_CONTEXT.md` and `PROJECT_REVIEW_ZH.md` are historical design/review baselines. Use this README, `ARCHITECTURE.md`, and current source for the active version.
