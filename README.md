# Moyu Chat

Moyu Chat is a supplemental Android application built on the same offline WebView + native glue boundary as Moyu Remote.

It is intentionally **not** a fourth Remote adapter and does not depend on Moyu Server, EasyTier, Claude Code, Codex, or OpenCode at runtime.

## Product boundary

- Android-local chat sessions are owned by Moyu Chat.
- Chat data is stored in Moyu Chat's own database.
- Provider credentials stay in Android native secure storage and are never injected into the WebView.
- Optional access to locally cached Moyu Remote history is through a narrow, signature-protected `RemoteDataBridge` contract.
- Imported Remote history becomes an immutable local Snapshot. Existing Remote state is not mutated.
- Export back to Remote uses an explicit Handoff package. No queued handoff is auto-sent after reconnection.

## Runtime topology

```text
Bundled WebView UI
      |
 window.MoyuHost
      |
ChatCoordinator
  |        |         |
Chat DB  Provider  RemoteDataBridge (optional)
            |              |
      model endpoint     Moyu Remote APK
```

If Moyu Remote is absent, disabled, has no database, or the PC is offline, Local Chat remains fully usable.

## Current implementation scope

v0.0.2 upgrades the context layer while preserving the v0.0.1 provider-neutral transcript boundary. The app compiles in debug + release/R8 and is covered by Robolectric/MockWebServer tests; the credential-gated real-endpoint smoke suite remains opt-in and must not be reported as verified when skipped.

Context v0.0.2 adds:

- complete user-led turn selection instead of message-level truncation;
- `incomplete` assistant semantics for interrupted/failed partial answers;
- explicit Session Context Modes: `auto`, `minimal`, and `smart`, frozen at creation time;
- `minimal` performs no extra model preprocessing; `smart` enables the bounded Task Router plus model-generated Checkpoint; `auto` keeps ordinary local Chat Router-free, enables Checkpoint only under real context pressure, and resolves Remote-origin Snapshots to the full smart layer;
- the Task Router creates only an allowlisted `TaskProfile` and selects local reviewed prompt modules;
- model-generated structured `ContextCheckpoint` is created only when enabled by the mode and older turns must be omitted;
- immutable Timeline/Snapshot remains the source of truth; TaskProfile/Checkpoint never rewrite old messages;
- model-card Chat creation and Remote import can explicitly choose a mode; local edit/rollback/branches inherit the source Session mode;
- provider-native response IDs/reasoning blocks/Claude compaction are intentionally not authoritative state in this version.

Web v0.0.2 is a Chat capability rather than a model label. Each Session freezes `webMode = off | auto | on` and a snapshot of provider-native Web capabilities. OpenAI Responses may use hosted `web_search`; Anthropic Messages may use hosted `web_search` / `web_fetch`. When a deployment has no matching native capability, Moyu can build bounded ephemeral Web context through Exa Search and SSRF-bounded HTTPS Fetch. Raw search/fetch payloads are never durable timeline rows; finalized assistants persist only visible answer text, source metadata, and the effective Web backend. Hidden Router/Checkpoint calls always use the no-Web transport path.

The conversation UI now keeps keyed DOM identity while streaming instead of rebuilding the full timeline. Final assistant messages use a local safe Markdown reader, fold usage/cache/speed and Web source metadata into the assistant footer, expose copy/source actions, and provide a jump-to-latest affordance without relaxing the WebView network boundary.

Transport scope remains protocol-centric: `openai-chat-completions`, `openai-responses`, and `anthropic-messages`. Provider definitions select `protocol + baseUrl + authMode` (`bearer`, `x-api-key`, or `api-key`); Session/Branch/Snapshot layers do not know which wire vendor is used. Transports append only protocol paths (`/chat/completions`, `/responses`, `/v1/messages`) and never hardcode vendor base URLs.

SQLite schema v9 persists `tool_capabilities` and `web_mode` while retaining v8 context-mode state. See `PROMPT_CONTEXT.md` and `V0.0.2_DESIGN.md` for the context/Web invariants and v0.0.2 scope.
