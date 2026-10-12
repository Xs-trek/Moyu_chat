# Local development handoff

Status: v0.0.5 source line (versionCode 5, SQLite schema 15). Local source and Git state are the only authority; do not assume this file proves that the current checkout has passed a build.

v0.0.5 is maintenance on the v0.0.4 architecture. `TurnUsage` tracks all main/auxiliary/subagent transport calls, including reported usage on failure/cancellation; unknown usage must not be represented as zero. Import guards apply before JSON string allocation and cap aggregate conversation size. Image bounds are checked before persistence. See README/CHANGELOG for exact limits.

## Product boundary

Moyu Chat is Android-local Chat + bounded tools. It is not the Moyu Remote runtime and does not require a PC to perform normal Chat requests. Do not add shell, computer-use, arbitrary local file mutation, Android device control, recursive subagents or an autonomous background agent loop without a separate product decision.

## Configuration authority

`Protocol != Capability`.

- Provider owns shared display name, Base URL and API key.
- Model Profile owns Model ID, protocol, auth mode, reasoning wire/levels, modalities, hosted-Web declaration, thinking, continuation, client-tool wire, Harness preset and context/input/output limits.
- Session freezes the resolved deployment when created or explicitly switched.
- Existing v0.0.3 profiles migrate as `legacy-manual` and must not be silently converted to Auto.

Auto Catalog configuration is valid only when endpoint + model matches a reviewed concrete deployment overlay. Unknown/same-named third-party routes must use Advanced; do not guess Chat Completions or any optional capability from model/provider names. Context/input/output limits remain explicit user/deployment inputs in Auto mode.

Provider Base URL changes must re-resolve affected Auto profiles in the same save transaction. A new endpoint that cannot resolve an existing Auto profile must be rejected until the user switches that profile to Advanced.

## Endpoint roots

Provider Base URL is the common API root; transport appends the final protocol path:

- Chat Completions -> `/chat/completions`
- Responses -> `/responses`
- Messages -> `/messages`

Official Anthropic root is `https://api.anthropic.com/v1`; do not configure `https://api.anthropic.com` expecting the transport to append `/v1/messages`.

## Context/Harness modes

- Minimal: native baseline; no Memory/RAG caller, checkpoint, Router, verifier, subagent or provider-private continuation.
- Standard: context management + optional Memory/RAG + checkpoint + explicit native continuation. Subagent is optional and defaults Off.
- Smart: adaptive Router/evidence/verifier behavior plus subagent Auto. Auto may choose zero child calls.

Harness preset is `lean / balanced / reinforced`; it controls bounded reliability assistance, not model ranking.

## Client tools

Current canonical client tools:

```text
web_search
web_fetch
delegate_subtask
```

Hard bounds:

```text
max parallel subagents = 4
subagent depth = 1
max tool calls per batch = 8
max provider tool rounds per user turn = 4
```

Children use the same Provider/Model/effort and do not receive a recursive client-tool loop.

Responses and Messages client-tool rounds must replay required exact same-turn provider state before tool results. Do not collapse this into the optional cross-user-turn continuation setting. Tool definitions/history/provider-round state must participate in context budgeting; already-sent state is immutable and only a newly generated unsent tool result may be clipped.

## Hosted Web

Responses hosted Web uses explicitly declared `web_search`. Messages hosted Web requires an explicitly declared supported exact Anthropic wire version such as `web_search_20260209` / `web_fetch_20250910`; never infer a Messages schema from generic aliases. Moyu typed/fallback Web covers deployments without native hosted Web according to their explicit client-tool capability.

## Validation before handing out an APK

Do not report a testable release candidate until the current checkout has passed, from a clean build state:

1. Web static architecture checks/build.
2. JVM/Robolectric/MockWebServer tests, including all three protocols and multi-round client-tool replay.
3. `lintDebug`.
4. Debug APK assembly.
5. Release assembly including release lint/R8/resource optimization.
6. Independent read-only source/diff audit after the final code change.
7. Final Git/checkpoint and fresh APK hashes.

Real-endpoint smoke remains credential-gated. A skipped `RealEndpointSmokeTest` is not proof that an external endpoint was verified.

Release APK remains unsigned unless a signing configuration is deliberately supplied.
