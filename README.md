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

This workspace contains architecture docs and a statically reviewable implementation baseline only. It has **not** been compiled or executed here. A local development agent should perform Gradle/npm/build/runtime work and correct any compile/runtime issues before release.

Initial transport scope is deliberately small but protocol-centric: OpenAI-compatible Chat Completions and OpenAI Responses are the two built-in transports. Provider definitions select a protocol; Session/Branch/Snapshot layers do not know which wire format is used. This follows the same maintenance direction seen in mature chat clients while avoiding a plugin framework.
