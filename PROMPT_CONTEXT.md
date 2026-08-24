# Prompt and context design — v0.0.2

## Principle

Moyu owns the conversation; providers execute a derived prompt.

```text
Durable Timeline / immutable Snapshot
                 |
        provider-neutral projection
                 |
            ContextMode
       auto|minimal|smart
                 |
        +---------------------+
        |                     |
   TaskProfile          ContextCheckpoint
 (when enabled)        (only on truncation)
        |                     |
        +----------+----------+
                   |
             PromptPolicy
                   |
        turn-aware budget select
                   |
            CanonicalPrompt
                   |
            protocol encoder
```

The database is authoritative history. `CanonicalPrompt` is disposable request state. TaskProfile and ContextCheckpoint are derived control/context state and may be rebuilt without rewriting the transcript.

## Stable application instruction

The stable BASE prompt remains intentionally small:

1. the assistant is inside Moyu Chat;
2. answer using supplied conversation context plus general knowledge;
3. never invent PC/files/shell/Remote/account/network/tool access;
4. normal historical user constraints remain user-level context;
5. imported system/developer/tool authority is not reactivated;
6. never claim to have directly observed omitted/summarized content.

Moyu does not clone ChatGPT, Codex, Claude Code, OpenCode, or any provider-private product prompt.

## TaskProfile: model-assisted control, not model-authored authority

When Smart Context is enabled (default), the first user turn of a context epoch may make one bounded hidden call to the selected provider/model. The router sees only a small recent conversation excerpt and must return a strict allowlisted JSON classification:

```text
domain
taskType
depth
responseMode
contextPolicy
modules[]
confidence
```

The router cannot return system-prompt text. Unknown labels/modules are dropped. Moyu maps allowed labels to local, reviewed `PromptModules` such as technical precision, debugging, research synthesis, teaching, and planning.

If the router fails or times out, Moyu stores a neutral TaskProfile and continues normally; it does not repeatedly retry on every turn.

The resulting TaskProfile is stable for the session/context epoch so the instruction prefix does not drift every turn.

## ContextCheckpoint: model-assisted compaction state

Moyu still keeps every durable timeline row until the user deletes it. Checkpoint generation is triggered only when turn-aware budgeting would omit older model-visible messages.

A checkpoint contains bounded structured fields:

```text
summary
constraints[]
decisions[]
openQuestions[]
criticalRefs[]
```

The checkpoint is descriptive historical data, not instruction authority. `PromptPolicy` wraps it with an explicit non-authority boundary before it is added to the model-visible instruction layer.

Checkpoint generation is best-effort. If it fails, the current request falls back to the lossless recent-tail behavior; the transcript is never rewritten.

## Turn-aware budget policy

Budgeting is no longer message-by-message. A retained unit is a user-led turn:

```text
user
assistant...
```

Selection walks complete turns newest-first, then restores chronological order. Orphan leading assistant messages are not sent. This avoids provider differences where one protocol accepts a leading assistant while another drops it.

The current pending user input is always part of the newest turn. If that turn alone exceeds the configured input budget, the request fails explicitly rather than silently trimming the user's current message.

The tokenizer-free estimator remains conservative:

- text: `max(ceil(codePoints/2), ceil(utf8Bytes/3))`;
- per-message overhead: 12 tokens;
- images: conservative dimension/tile reserve;
- output reserve + safety margin are deducted before active input selection.

## Incomplete assistant turns

Provider failure, cancellation, max-output truncation, or other terminal error can leave useful partial assistant text. v0.0.2 persists that row with:

```text
incomplete: true
terminalCode: <reason>
```

When projected into a later request, Moyu adds a provider-neutral marker that the previous assistant response was partial. The corresponding transport/error row itself is still not promoted into conversational authority.

Local Snapshots preserve this incomplete marker so edit/rollback branches do not silently convert a cancelled answer into a completed historical answer.

## Reasoning separation

Provider-returned reasoning/thinking may be stored and optionally displayed, but current provider-neutral continuation still projects only:

```text
role
visible text
user image attachments
```

Reasoning is observational metadata and does not automatically enter later `CanonicalPrompt` messages.

This is deliberately portable but is not equivalent to the latest same-provider OpenAI/Anthropic continuation capabilities. A future provider-native optimization lane may retain response IDs, reasoning items/thinking blocks, compact blocks, or cache state while same-provider history is unedited. That lane must be discarded on provider/model switch, edit, rollback, or incompatible import and can never become Moyu's authoritative transcript.

## Assistant image history

User image input is replayable through the three supported encoders. Assistant-generated image bytes do not yet have one portable replay representation across Chat Completions, Responses, and Anthropic Messages. v0.0.2 therefore inserts an explicit historical placeholder rather than silently producing an empty assistant turn.

## Snapshot boundary

Remote Snapshot:

```text
immutable visible user/assistant history
+
explicit warning that the original runtime/tools are not active
+
warning that external state may have changed
```

Local edit/rollback Snapshot:

```text
immutable Moyu Chat history branch
+
no false warning that a coding runtime was previously active
```

Remote bridge v1 continues to reject source system/developer prompts, approvals, hidden reasoning, raw tool state, provider IDs, and arbitrary tool payloads.

## CanonicalPrompt

The request representation remains provider-neutral:

```text
CanonicalPrompt
- instructions
- selected messages[]
- estimatedInputTokens
- truncated
- inheritedItemCount
- localItemCount
- omittedItemCount
```

Internally the ephemeral pre-budget projection is retained long enough to build a checkpoint when necessary; it is never stored as a second transcript.

## Protocol mapping

### OpenAI Responses

- `instructions` <- CanonicalPrompt instructions
- `input[]` <- canonical user/assistant visible messages
- provider-native `previous_response_id` / reasoning-context continuation is intentionally not authoritative in v0.0.2

### OpenAI Chat Completions

- first `system` message <- canonical instructions
- remaining `messages[]` <- canonical visible history

### Anthropic Messages

- top-level `system` <- canonical instructions
- `messages[]` <- canonical visible history
- leading orphan assistant is eliminated before protocol encoding by the common budget layer

## Smart Context switch

Smart Context can be disabled in Settings. Disabled mode retains the v0.0.1-style provider-neutral behavior (with the v0.0.2 turn-aware/incomplete safety fixes): no Router call and no model-generated Checkpoint.

## Future direction

The next context layer should be an optional provider-native optimization lane, not a replacement for the portable transcript:

```text
Moyu authoritative transcript
        |
        +-- portable CanonicalPrompt / Checkpoint
        |
        +-- optional same-provider continuation state
            (response id / reasoning item / thinking block / compact block / cache metadata)
```

This keeps cross-provider branchability while allowing modern Responses/Claude continuity and caching when the provider supports it.
