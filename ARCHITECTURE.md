# Moyu Chat architecture

Current source: v0.0.6; SQLite `moyu-chat.db` layout version 1. During 0.0.x there are no migrations: a file of another layout version is dropped and recreated.

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

Preparation stages run synchronously on the preparation pool and share one `TurnRun`. `ProviderRoundLoop` is the generate stage: provider rounds, bounded client-tool batches and the coalesced live projection. Verification runs on the preparation pool after the last round. Each stage records a `TurnTrace` span (status, duration, a small decision summary); a skipped helper records why (`mode_off`, `within_budget`, `web_not_needed`, `provider_hosted`, `not_needed`, …). Helper model calls are tracked per issuing stage, so each helper's cost is visible next to what it decided.

### Durable Chat domain

`ChatDatabase` owns Providers, Model Profiles, Sessions, timeline rows and immutable branch snapshots. A branch snapshot is owned by the sessions that reference it and is collected when the last one is deleted. The durable visible transcript is the product source of truth; provider-native conversation/reasoning state is optional derived metadata.

The whole schema is created in `onCreate`; a file of any other layout version is dropped and recreated (no migration chain during 0.0.x). Tables:

```text
providers        account: preset, credential_epoch (bumped when a new key is stored), key status, budget
provider_routes  one endpoint of an account: protocol, Base URL, auth, dialect, headers, confirmed host
model_profiles   one deployment: route + model id, limits, capabilities, per-field sources, pricing
sessions         identity, lineage (parent / origin snapshot), frozen deployment, per-session
                 choices, derived context state (checkpoint), leaf_key (end of the shown path)
timeline         one JSON item per row (what the user sees); kind (message / error) mirrors body.kind;
                 parent_key makes the rows a tree: edits, retries and re-answers are versions
turns            one answer to one question (a question can have several): frozen turn config,
                 usage summary, status / terminal code, rating
turn_spans       the turn's pipeline stages: status, skip reason, duration, helper usage, decision
turn_payloads    protocol inspector: redacted request / response text per model call, recent turns only
turn_archive     a deleted Chat's turn records, still counted by statistics and budgets
snapshots        immutable branch origin + snapshot_items
knowledge_fts    FTS4 lexical index, scope chat:<session> / snapshot:<snapshot>
verifier_labels  explicit verifier feedback per calibration key (bounded)
settings         app preferences only
```

The layout follows the common local-agent pattern (OpenAI Agents SDK `SQLiteSession`: a session table plus an ordered JSON item log; its `AdvancedSQLiteSession` adds per-turn usage and branch structure as side tables). Turn records and their stage spans are such side tables: usage is stored on `turns` and projected into the view as a `kind: usage` row after the turn's reply, so the frontend contract did not change; the trace is read on demand with `turn.trace.get` (the intent result carries `data`).

The protocol inspector records, per model call, the request body and the raw response stream. Transports emit them through `Sink.onWire`; `TurnUsage` tags each record with its stage and call index. `WireCapture` never sees request headers (the only place the credential travels), scrubs the API key from all text as a second line of defence, replaces inline base64 and long opaque strings by their length, and caps each record at 256K characters. Payloads are kept for the 30 most recent turns, read on demand with `turn.payload.get`, and can be switched off in Settings.

Comparison and feedback stay on the same records. `turn.rerun` answers a question again in the same session (optionally switching the session's model and/or context mode) as a new version; `turn.answers.get` returns every version of the answer to that question. `turn.rate` stores a good / bad mark on the turn. `stats.get` aggregates finished turns per model × context mode with `TurnStats`: latency, tokens, the helper stages' share of tokens and how often each helper actually acted. These are in-use trends, not benchmarks.

## 3. Account / Route / Model Profile / Session

Full description: `docs/provider-config.md`.

### Account (Provider)

One API Key (native secure store, never SQLite) shared by its routes, plus key status and an optional spend budget:

```text
providerId, displayName, presetId (fixed at creation)
API key (secure store) + keyStatus / keyCheckedAt
budget {currency, daily?, monthly?}
```

### Route

One endpoint of the account; the protocol is fixed at creation:

```text
protocol + Base URL (as the protocol's official SDK takes it) + authMode
dialect (endpoint wire knobs, incl. maxConcurrent) + extra non-secret headers
confirmed host (the key is only sent to a confirmed host)
```

### Model Profile

One concrete deployment (account + route + Model ID). Every capability value is resolved field by field and records its source:

```text
default < discovered (the endpoint's model list) < catalog (reviewed preset entry) < probe (reserved) < manual
```

Fields: context/input/output limits, modalities, reasoning wire and levels, thinking mode/budget, hosted-Web declaration, client-tool wire, continuation, Harness preset, service tier, prices. Discovered values and probe results belong to the endpoint they were read from and are dropped when the route points elsewhere.

### Session

Freezes the resolved deployment fields on creation/model switch. Changing the Model Profile later changes future sessions or explicitly switched sessions only. Branches preserve the source session's frozen deployment.

`Protocol != Capability`: the protocol names define wire format and never imply reasoning, thinking, images, hosted Web, continuation or client-tool support.

## 4. Presets and model catalog

`provider-presets.json` holds the vendor presets (routes, dialects) and the reviewed model catalog. A catalog entry applies only to an unmodified preset route and the exact Model ID; edited or custom routes get nothing from it. Manual values are stored separately and survive catalog updates. Nothing is inferred from a model name.

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

A route stores the API root exactly as the protocol's official SDK takes it; transports append the final path:

```text
openai-chat-completions  https://api.openai.com/v1      -> /chat/completions
openai-responses         https://api.openai.com/v1      -> /responses
anthropic-messages       https://api.anthropic.com      -> /v1/messages
```

## 10. Security and execution exclusions

- WebView is local-assets-only and does not receive API keys.
- Provider API keys use native secure storage.
- Cloud endpoints require HTTPS; approved loopback HTTP is treated separately by endpoint validation.
- Branch snapshots are immutable and bounded.
- A main call that fails with a rate-limit / overload / 5xx error before producing any output is retried at most twice (Retry-After honoured up to 60 s). Nothing else is resent without the user asking (the Retry action).
- Transports and account checks never follow redirects; error summaries are scrubbed of the key.
- No shell, computer use, arbitrary filesystem mutation, Android device control, recursive subagents or autonomous agent planning loop is part of the Chat runtime.

## 11. Turn usage and media bounds

`TurnUsage` decorates each turn's transport calls by main/auxiliary/subagent category and by the pipeline stage that issued them. It accumulates reported cumulative usage once per request, preserves reported usage from failed or cancelled helpers, and writes final totals after Smart post-processing. Unknown dimensions remain absent and `usageIncomplete` marks partial totals. Main cache/reasoning/speed metrics retain their existing scope. Usage is an ordinary timeline item (`kind = usage`). Each call is priced from the prices frozen with the turn (`cost`, an estimate); spend of turns that are later rewound or deleted is carried over per account and day, so budgets keep counting it.

`ChatMediaStore` reads image bounds without decoding pixels, rejects excessive dimensions/area, and only then creates the private media file. Byte/draft-image limits remain in force.
