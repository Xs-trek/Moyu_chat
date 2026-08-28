# Moyu Chat architecture

## 1. Product boundary

Moyu Chat is a supplemental Android-local Chat application. It must remain usable with no PC, no Moyu Server and no EasyTier connection. Moyu Remote remains a separate PC-runtime remote-control product; the two applications do not share a SQLite database.

```text
Moyu Chat WebView UI
        |
versioned intents / view models
        |
ChatCoordinator
  |        |           |             |
Chat DB  Context    Tool runtime   RemoteDataBridge (optional)
  |        |           |             |
Session  Memory/     web_search      immutable Snapshot /
History  Snapshot    web_fetch       explicit Handoff
         Checkpoint  delegate_subtask
              \        /
              Provider transports
     Chat Completions / Responses / Messages
```

The product is Chat + bounded feasible tools, not an Android agent executor.

## 2. Authority layers

### WebView UI

Presentation only. It posts versioned intents through `window.MoyuHost.postMessage` and receives full/patch view projections. Credentials, database access, provider networking, tool execution and Remote bridge access remain native.

### ChatCoordinator

Owns intent validation, local session lifecycle, turn cancellation/runtime ownership, context preparation, bounded client-tool rounds, Smart post-processing and optional Remote exchange. Provider-specific wire parsing belongs to transports.

### Durable Chat domain

`ChatDatabase` owns Providers, Model Profiles, Sessions, timeline rows, immutable snapshots and handoff drafts. The durable visible transcript is the product source of truth; provider-native conversation/reasoning state is optional derived metadata.

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
- v0.0.3 profiles migrate as `legacy-manual` and are not silently rewritten.

## 5. Context and Harness

Minimal is the native baseline. Standard adds context management, optional Memory/RAG and explicit provider continuation without Router/verifier. Smart adds adaptive Router/evidence/verifier behavior. `lean / balanced / reinforced` controls how much reviewed reliability assistance is selected; it is not a model benchmark ranking.

Prompt context is disposable. Durable history is not deleted by budgeting. Complete newest user-led turns are retained; older whole turns are omitted when context pressure requires it. Standard/Smart checkpoint compaction triggers at 50% of effective input and targets roughly 30%.

## 6. Client tools

The provider-neutral tool vocabulary in v0.0.4 is intentionally small:

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
- Remote imports are immutable bounded snapshots; handoff is explicit.
- Failed model inputs/handoffs are not autonomously resent.
- No shell, computer use, arbitrary filesystem mutation, Android device control, recursive subagents or autonomous agent planning loop is part of the v0.0.4 Chat runtime.