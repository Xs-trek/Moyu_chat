# Design references

Primary references checked for the v0.0.2 context/prompt design (August 2026):

- OpenAI API, **latest model guidance / Responses** — lean instruction design, response continuation, modern reasoning context and cache-aware behavior.
  https://developers.openai.com/api/docs/guides/latest-model
  https://developers.openai.com/api/reference/
- OpenAI, **Unrolling the Codex agent loop** — structured prompt inputs, replayable state and stable-prefix caching lessons.
  https://openai.com/index/unrolling-the-codex-agent-loop/
- OpenAI Codex repository, context checkpoint/compaction implementation references.
  https://github.com/openai/codex/blob/main/codex-rs/prompts/templates/compact/prompt.md
  https://github.com/openai/codex/blob/main/codex-rs/core/src/compact_remote.rs
- Anthropic, **Context editing** — keep full client history while editing only model-visible context; thinking/tool-result retention and cache tradeoffs.
  https://platform.claude.com/docs/en/build-with-claude/context-editing
- Anthropic, **Compaction** — structured server-side summary blocks and cache-boundary guidance.
  https://platform.claude.com/docs/en/build-with-claude/compaction
- Anthropic, **Extended thinking / prompt caching** — thinking continuity and cache invalidation behavior.
  https://platform.claude.com/docs/en/build-with-claude/extended-thinking
  https://platform.claude.com/docs/en/build-with-claude/prompt-caching

Community issues are used only to identify compatibility failure modes. They are not treated as authoritative protocol specifications.
