# Moyu Chat

Moyu Chat is a supplemental Android application built on the same offline WebView + native glue boundary as Moyu Remote. It is intentionally not a fourth Remote adapter and does not depend on Moyu Server, EasyTier, Claude Code, Codex, or OpenCode at runtime.

## Product boundary

- Android-local Chat sessions are owned by Moyu Chat and stored in its own database.
- Provider credentials remain in Android native secure storage and never enter the WebView.
- Remote history is optional input through a narrow `RemoteDataBridge`; imported history becomes an immutable local Snapshot.
- Local branches never mutate the source Remote/Chat history.
- Provider-native reasoning/continuation state is optional derived state and never replaces the durable visible transcript.

## Context modes

v0.0.2 exposes three frozen per-session Harness modes:

- **Minimal** — thin Moyu policy + raw complete turns. No Router, Memory/RAG caller, Checkpoint model call or verifier.
- **Standard** — mature Chat harness: raw context first, optional global user Memory/RAG, Snapshot retrieval, proactive 50%→~30% compaction, structured Checkpoint and same-provider/model native reasoning continuation where the protocol exposes replayable state. No Task Router or verifier.
- **Smart** — Standard plus bounded Task Router, reviewed local PromptModules, evidence/completion-honesty policy, fresh same-model verifier, deterministic verifier validation, provisional calibration and at most one repair/re-verify cycle.

A global user-memory switch controls whether cross-chat Memory/RAG is allowed at all. Minimal still has no Memory caller even when the global switch is on; Standard and Smart may use it.

The provider-neutral visible transcript stays authoritative across all three modes. OpenAI Responses reasoning items and Anthropic thinking/signature blocks are treated as discardable provider-private continuation state and are reused only on an exact compatible same-provider/model chain.

## Context and retrieval

Context selection keeps complete user-led turns. Current input is mandatory. Fallback Web/RAG material is ephemeral and never becomes user-authored durable text. SQLite FTS4 provides a lightweight local retrieval layer without adding a vector-database framework.

Standard/Smart proactive compaction uses an explicit quality-protection envelope:

```text
trigger = 50% of effective input window
target  = approximately 30%
```

The target is best-effort and never forces deletion of mandatory current input or durable history. Large inherited snapshots remain fully durable and available to Context/RAG; only the WebView display projection is bounded to a recent whole-turn tail so import size cannot directly become DOM size.

## Verification

Smart verification separates verifier validity from answer verdict. A verifier must satisfy a fixed JSON schema, cover mandatory local rules and reference only evidence IDs that Moyu actually supplied. Invalid/unsupported or coverage-incomplete verification abstains instead of pretending success and is not accepted as calibration data. Initial numerical risk thresholds are permissive cold-start defaults and are designed to be replaced by deployment/policy-scoped calibration from explicit verifier feedback.

## Web and transport

Third-party compatible endpoints are the primary deployment case. OpenAI and Anthropic are protocol-family references, not capability assumptions. `openai-chat-completions`, `openai-responses`, and `anthropic-messages` define request/stream shape only; they do not imply first-party reasoning, thinking, hosted Web, image, or opaque continuation support.

Optional behavior is deployment-scoped and explicit in the Model Profile. Reasoning wire mapping defaults to `none`; provider-private continuation defaults to `none`; Messages thinking is independent from Messages effort support. A capability is sent only when the concrete endpoint/model profile declares it. Messages-compatible hosted Web may use generic `web_search` / `web_fetch` aliases or an explicitly declared supported tool wire version when a third-party endpoint requires one. Missing native Web capability can fall back to bounded Exa Search and SSRF-bounded HTTPS Fetch. Raw fetched/search material is not a timeline row.

Provider definitions select `protocol + baseUrl + authMode`; Model Profiles select model/deployment capabilities. Session/Branch/Snapshot layers do not hardcode vendor URLs or infer capability from vendor/model names.

See `PROMPT_CONTEXT.md` and `V0.0.2_DESIGN.md` for normative context/Memory/compaction/reasoning/verifier behavior.
