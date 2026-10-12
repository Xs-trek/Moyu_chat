# Moyu Chat architecture

Current source: v0.0.6-beta; SQLite `moyu-beta.db` schema 1 (beta numbering restarts at 1).

## 1. Product boundary

Moyu Chat is a standalone Android-local Chat application and model test bench. It has no cross-app bridge and no external history import; all durable state lives in its own SQLite database.

```text
Moyu Chat WebView UI
        |
versioned intents / view models
        |
ChatCoordinator
  |        |              |
Chat DB  Context        Tool runtime
  |        |              |
Session  Memory/          web_search
History  Branch snapshot  web_fetch
         Checkpoint       delegate_subtask
              \            /
            Provider transports
   Chat Completions / Responses / Messages
```

The product is Chat + bounded feasible tools, not an Android agent executor.

## 2. Authority layers

### WebView UI

Presentation only. It posts versioned intents through `window.MoyuHost.postMessage` and receives full/patch view projections through the `moyu:view` event. Credentials, database access, provider networking and tool execution remain native.

Inside the page, `src/view-model.js` is the only module that interprets native view fields (usage, stages, verification, capabilities); renderers consume its output, so native projection changes are absorbed in one place. Transient surfaces (drawer, sheets, dialogs) live outside `#app` and survive full renders; Android Back first calls `window.moyuHandleBack()`. The native side resolves the "system" theme from the Android night mode and paints the window and system bars with the same canvas colour as the CSS tokens (`Host.applyAppearance`).

### ChatCoordinator

Owns intent validation, local session lifecycle, local branching, turn admission (frozen `TurnConfig`, runtime lease, cancellation) and view projection. It hands each accepted turn to `TurnPipeline` and exposes its runtime (executors, busy flags, view emission) through the narrow `TurnPipeline.Env` interface. Provider-specific wire parsing belongs to transports.

### Turn pipeline

```text
accept → context → route → checkpoint → web plan → tools → web retrieve → generate → verify/repair → finish
```

Preparation stages run synchronously on the preparation pool and share one `TurnRun`. `ProviderRoundLoop` is the generate stage: provider rounds, bounded client-tool batches and the coalesced live projection. Verification runs on the preparation pool after the last round. Each stage records a `TurnTrace` span (status, duration, a small decision summary); a skipped helper records why (`mode_off`, `task_profile_cached`, `within_budget`, `web_not_needed`, `provider_hosted`, `not_needed`, …). Helper model calls are tracked per issuing stage, so each helper's cost is visible next to what it decided.

### Durable Chat domain

`ChatDatabase` owns Providers, Model Profiles, Sessions, timeline rows and immutable branch snapshots. A branch snapshot is owned by the sessions that reference it and is collected when the last one is deleted. The durable visible transcript is the product source of truth; provider-native conversation/reasoning state is optional derived metadata.

The beta line keeps its own file, `moyu-beta.db`, with schema numbering restarted at 1. The pre-beta `moyu-chat.db` is deleted, a newer file is dropped and recreated, and there is no pre-beta migration chain. Tables:

```text
providers        shared endpoint + credential_epoch (bumped when a new key is stored)
model_profiles   one deployment: wire protocol, limits, capabilities, catalog provenance
sessions         identity, lineage (parent / origin snapshot), frozen deployment, per-session
                 choices, derived context state, knowledge_indexed_at
timeline         one JSON item per row (what the user sees); kind (message / error) mirrors body.kind
turns            one accepted user turn: frozen turn config, usage summary, status / terminal code
turn_spans       the turn's pipeline stages: status, skip reason, duration, helper usage, decision
snapshots        immutable branch origin + snapshot_items
knowledge_fts    FTS4 lexical index, scope chat:<session> / snapshot:<snapshot>
verifier_labels  explicit verifier feedback per calibration key (bounded)
settings         app preferences only
```

The layout follows the common local-agent pattern (OpenAI Agents SDK `SQLiteSession`: a session table plus an ordered JSON item log; its `AdvancedSQLiteSession` adds per-turn usage and branch structure as side tables). Turn records and their stage spans are such side tables: usage is stored on `turns` and projected into the view as a `kind: usage` row after the turn's reply, so the frontend contract did not change; the trace is read on demand with `turn.trace.get` (the intent result carries `data`).

## 3. Provider / Model / Session separation

### Provider

Shared account/connection only:

```text
providerId
displayName
baseUrl
API key (secure store, not SQLite)
```

### Model Profile

One concrete deployment:

```text
modelId
protocol
authMode
reasoningWire + reasoningLevels
context/input/output limits
input/output modalities
hosted-Web capability declaration
thinking mode/budget
provider continuation mode
client-tool calling wire
Harness preset
Catalog provenance/config mode
```

### Session

Freezes the resolved deployment fields on creation/model switch. Changing the Model Profile later changes future sessions or explicitly switched sessions only. Branches preserve the source session's frozen deployment.

`Protocol != Capability`: the protocol names define wire format and never imply reasoning, thinking, images, hosted Web, continuation or client-tool support.

## 4. Model Catalog

Auto configuration is a reviewed deployment-overlay mechanism, not model-name inference.

- Base-model entries may provide only reviewed behavior/Harness priors.
- Protocol/auth/reasoning/tool/continuation properties require a reviewed endpoint + model overlay.
- Context/input/output limits remain explicit user/deployment inputs.
- If Auto does not resolve a concrete deployment, saving requires Advanced/manual configuration.
- Provider endpoint changes revalidate affected Auto profiles in the same save transaction. A change that makes an Auto deployment unresolved is rejected.

## 5. Context and Harness

Minimal is the native baseline. Standard adds context management, optional Memory/RAG and explicit provider continuation without Router/verifier. Smart adds adaptive Router/evidence/verifier behavior. `lean / balanced / reinforced` controls how much reviewed reliability assistance is selected; it is not a model benchmark ranking.

Prompt context is disposable. Durable history is not deleted by budgeting. Complete newest user-led turns are retained; older whole turns are omitted when context pressure requires it. Standard/Smart checkpoint compaction triggers at 50% of effective input and targets roughly 30%.

## 6. Client tools

The provider-neutral tool vocabulary is intentionally small:

- `web_search`
- `web_fetch`
- `delegate_subtask`

A deployment must explicitly declare a compatible client-tool wire:

- `openai-chat-tools`
- `openai-responses-tools`
- `anthropic-tools`

Hard bounds:

```text
max client tool calls / batch = 8
max provider tool rounds / user turn = 4
max parallel subagents = 4
subagent depth = 1
```

`delegate_subtask` invokes an isolated copy of the same selected model. It receives a bounded subtask and has no recursive client-tool loop, shell, filesystem write, Android control or computer-use authority.

## 7. Same-turn provider state

Client-tool rounds need protocol state that is distinct from cross-user-turn durable continuation.

- Responses retains/replays exact prior response output items for the current user turn before the corresponding `function_call_output`.
- Messages retains/replays applicable exact content blocks, including thinking/redacted-thinking and tool-use blocks, before `tool_result`.
- Tool definitions, tool history and exact same-turn provider state are reserved in the input budget.
- Already-sent tool/protocol state is immutable. Only a newly produced unsent tool result can be clipped if necessary.
- If mandatory tool/protocol history cannot fit, the turn fails explicitly.

A turn that used client tools does not persist a partial provider-private continuation chain for the next user turn, because the non-durable tool history needed to replay that chain is intentionally scoped to the current turn.

## 8. Web capability plane

Hosted Web is deployment capability, not protocol inference.

- Responses accepts the explicitly declared generic hosted `web_search` capability supported by this build.
- Messages requires a supported exact Anthropic Web tool wire version; generic `web_search`/`web_fetch` aliases are not guessed into Messages schemas.
- When provider-native Web is absent, explicit client tools may supply Moyu `web_search` / `web_fetch`.
- A bounded deterministic Exa/HTTPS fallback remains available for deployments without client-tool support.
- Web mode is independent of context mode. Without hosted Web, Web On or an explicit URL may also use host retrieval on a deployment that supports client tools. Semantic Web planning is a bounded auxiliary model call.

Fetched/search material is untrusted ephemeral context, not durable user text.

## 9. Endpoint ownership

Provider stores the shared API root. Transports own final paths:

```text
openai-chat-completions -> /chat/completions
openai-responses        -> /responses
anthropic-messages      -> /messages
```

Official Anthropic shared root is `https://api.anthropic.com/v1`.

## 10. Security and execution exclusions

- WebView is local-assets-only and does not receive API keys.
- Provider API keys use native secure storage.
- Cloud endpoints require HTTPS; approved loopback HTTP is treated separately by endpoint validation.
- Branch snapshots are immutable and bounded.
- Failed model inputs are not autonomously resent.
- No shell, computer use, arbitrary filesystem mutation, Android device control, recursive subagents or autonomous agent planning loop is part of the Chat runtime.

## 11. Turn usage and media bounds

`TurnUsage` decorates each turn's transport calls by main/auxiliary/subagent category and by the pipeline stage that issued them. It accumulates reported cumulative usage once per request, preserves reported usage from failed or cancelled helpers, and writes final totals after Smart post-processing. Unknown dimensions remain absent and `usageIncomplete` marks partial totals. Main cache/reasoning/speed metrics retain their existing scope. Usage is an ordinary timeline item (`kind = usage`).

`ChatMediaStore` reads image bounds without decoding pixels, rejects excessive dimensions/area, and only then creates the private media file. Byte/draft-image limits remain in force.
