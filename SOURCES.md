# Design references

Primary references checked for the v0.0.1 context/prompt design (August 2026):

- OpenAI, **Unrolling the Codex agent loop** — structured prompt inputs, stateless replay, prefix-stable prompt caching and Responses transport.
  https://openai.com/index/unrolling-the-codex-agent-loop/
- OpenAI Codex repository, built-in **context checkpoint compaction prompt**.
  https://github.com/openai/codex/blob/main/codex-rs/prompts/templates/compact/prompt.md
- OpenAI Codex repository, compaction/replacement-history implementation and rollout reconstruction.
  https://github.com/openai/codex/blob/main/codex-rs/core/src/compact_remote.rs
  https://github.com/openai/codex/blob/main/codex-rs/core/src/session/rollout_reconstruction.rs
- OpenAI API, **Responses / compact / latest-model guidance** — instructions, response continuation, compaction and reasoning-context behavior.
  https://developers.openai.com/api/reference/
  https://developers.openai.com/api/docs/guides/latest-model
- Anthropic, **Context windows** — context composition and context-rot guidance.
  https://platform.claude.com/docs/en/build-with-claude/context-windows
- Anthropic, **Compaction** — server-side compaction blocks and stateless round-tripping.
  https://platform.claude.com/docs/en/build-with-claude/compaction
- Anthropic, **Prompt caching** — stable system-prefix caching behavior.
  https://platform.claude.com/docs/en/build-with-claude/prompt-caching

Community issues were used only to identify failure modes to avoid (for example flattening imported compacted sessions or losing active context after compaction), not as authoritative API specifications.
