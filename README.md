# Moyu Chat

Moyu Chat is an Android-local multi-model Chat client with optional bounded tools: a native Java shell (protocol adapters, SQLite sessions, context management, Web tools, same-model subtasks, smart verification) plus a dependency-free WebView frontend that only renders view models.

It is a personal model test bench for comparing what different models and protocols return and what native orchestration designs can achieve. The native complexity is intentional.

Current version: **v0.0.6** (Android versionCode 6, SQLite layout version 1 in `moyu-chat.db`, applicationId `com.moyu.chat`). During 0.0.x the database is not migrated: a layout change recreates it. See `CHANGELOG.md`.

## Status

The main features work in daily use on the author's device. No APK or GitHub Release is published: device testing so far does not cover enough cases for even an internal test release, and the minified release build has not been tried on a device. To try it, build from source (below). This is a personal project; there is no support.

## Build and verify

```bash
npm run verify
```

This runs the frontend static checks (`npm run build`) and then `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Frontend sources live in `src/` and `index.html`; `android/app/src/main/assets/ui/` is a generated copy synced at build time.

The toolchain is not in the repository (`.tools/` is Git-ignored and there is no Gradle wrapper). Before the first build, provide:

- Node.js (the frontend checks and tests use only its built-in modules);
- Gradle 8.5, unpacked to `.tools/gradle-8.5/` (`scripts/verify.sh` runs `.tools/gradle-8.5/bin/gradle`);
- JDK 17, unpacked to `.tools/jdk-17*` (used as `JAVA_HOME` when present; otherwise the system `JAVA_HOME` must be JDK 17);
- the Android SDK, from `ANDROID_HOME` or `android/local.properties`.

### Release APK

The release build is minified (R8) and signed with a local key that never enters the repository. Create the key once, outside the repository:

```bash
keytool -genkeypair -v -keystore ~/moyu-release.jks -alias moyu -keyalg RSA -keysize 4096 -validity 10000
```

Then write `android/app/keystore.properties` (Git-ignored):

```properties
storeFile=C:/Users/you/moyu-release.jks
storePassword=...
keyAlias=moyu
keyPassword=...
```

`bash scripts/verify.sh :app:assembleRelease` then also writes `android/app/build/outputs/apk/release/app-release.apk`. Without the properties file the release APK is built unsigned. A release-signed APK cannot update a debug-signed install: uninstall once (local data is lost).

## Product boundary

- Chat sessions and durable visible history are owned by Moyu Chat and stored in its own database.
- Provider credentials remain in Android native secure storage and never enter the WebView or SQLite.
- Local branches start from an immutable snapshot of the source session's visible history; branching never mutates the source session.
- Provider-private reasoning/thinking/continuation state is derived protocol state, never the durable source of truth.
- The runtime supports bounded client `web_search`, `web_fetch`, and isolated same-model `delegate_subtask` when the concrete deployment explicitly supports the corresponding client-tool wire.
- Moyu Chat is not an Android agent runtime: no shell, computer use, arbitrary filesystem mutation, device control, recursive subagents or autonomous background task loop is exposed.

## Accounts, routes, models and sessions

`Protocol != Capability` is a hard invariant. Details: `docs/provider-config.md`.

- **Account** holds one API Key (native secure store only), its key status and an optional daily / monthly budget.
- **Route** is one endpoint of the account: protocol + Base URL + auth mode + wire knobs (dialect, including an optional concurrency cap) + non-secret headers. The key is only sent to hosts confirmed for the account; redirects are never followed.
- **Model Profile** is one concrete deployment on a route. Each capability is resolved field by field — default < discovered (the endpoint's own model list) < reviewed catalog < manual — and the source of every value is shown.
- **Session** freezes the resolved deployment when it is created or switched to another model. Editing a profile later does not relabel an existing Chat; the Chat offers to apply the edited profile.

Supported protocol families are `openai-chat-completions`, `openai-responses` and `anthropic-messages`. They define wire shape only.

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

Base URLs are written as each protocol's official SDK takes them; transports append the path:

- Chat Completions / Responses: the root includes the version segment (`https://api.openai.com/v1`) and gets `/chat/completions` or `/responses`.
- Messages: the root has no `/v1` (`https://api.anthropic.com`) and gets `/v1/messages`.

## Usage and local media limits

Turn usage includes reported main, auxiliary (Router, Checkpoint, Web planner, verifier/repair), and subagent requests. Provider-reported usage is retained even when a helper fails, times out, or is cancelled. Missing usage stays unknown; partial totals are labeled accordingly. Turn cost is an estimate from the prices declared on the model profile (frozen when the turn starts); hosted tool fees are not included.

Call counts refer to logical transport invocations; Anthropic internal `pause_turn` HTTP continuations belong to one such invocation and retain transport-accumulated usage. These counters are not an invoice or a raw HTTP request count.

Image input/output is limited to 12 MiB encoded bytes, 16,777,216 pixels and 8,192 pixels on either side; up to four draft images remain supported. Oversized images are rejected before private media files are written.

`docs/history/` keeps the v0.0.1–v0.0.5 design and review baselines. Use this README, `ARCHITECTURE.md`, `CLAUDE.md` and current source for the active version.

## Git history

Commits up to `v0.0.5` come from the Codex period. Most of them are automatic snapshots taken by a tool around every edit and build (`pre-change checkpoint`, `post-build …`), so their messages say nothing about intent; the tagged releases and `docs/history/` are the useful record of that period. From `v0.0.5` on, each commit is one reviewed phase: `git log v0.0.5..main`.
