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

v0.0.1 RC status: the project compiles (debug + release/R8) and is covered by JVM unit tests (Robolectric + mock SSE endpoints), emulator instrumented tests for both bridge-absent and bridge-present cases, and an env-gated real-endpoint smoke suite (`RealEndpointSmokeTest`, executed only when `SMOKE_REAL=1` with credentials; skipped runs are not counted as verified).

Transport scope is deliberately small but protocol-centric: `openai-chat-completions`, `openai-responses`, and `anthropic-messages` are the three built-in transports. Provider definitions select `protocol + baseUrl + authMode` (`bearer`, `x-api-key`, or `api-key`); Session/Branch/Snapshot layers do not know which wire format or vendor is used. Transports append only the protocol paths (`/chat/completions`, `/responses`, `/v1/messages`) and never hardcode vendor base URLs. New providers default to Chat Completions + Bearer auth for broad third-party compatibility.
