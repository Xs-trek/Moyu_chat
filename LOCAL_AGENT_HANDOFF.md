# Local development handoff

Status: v0.0.1 RC. The original baseline has since been compiled, fixed, and verified as summarized below.

## Verification status (RC)

| Check | Status |
|---|---|
| Web UI asset sync into Android (`syncMoyuChatUi`) | passed (wired into preBuild) |
| Android compile (debug, release with R8) | passed (AGP 8.2.2 / Gradle 8.5 / JDK 17) |
| Static architecture checks (`npm run check`) | passed |
| JVM unit tests (Robolectric SQLite + MockWebServer, all three protocols) | passed (see `app/build/reports/tests/`) |
| `lintDebug` | passed |
| Streaming cancel / error / completed + single-terminal | passed (unit) |
| DB migrations, snapshot immutability, budget, key isolation | passed (unit) |
| No-Remote startup (Robolectric E2E + emulator instrumented) | passed |
| Cross-APK bridge (signature-protected provider, inbox write) | passed on emulator, 4/4 instrumented tests |
| Real-endpoint smoke (OpenAI Responses / Chat Completions / Anthropic Messages) | **NOT executed — no credentials in this environment.** Env-gated suite exists (`RealEndpointSmokeTest`, `SMOKE_REAL=1` + keys); skipped runs must never be reported as verified. |

## Remaining notes for the next agent / release

1. Run the real-endpoint smoke with credentials before shipping: `SMOKE_REAL=1`, `SMOKE_OPENAI_API_KEY`, optional `SMOKE_OPENAI_BASE_URL` / `SMOKE_OPENAI_MODEL` / `SMOKE_OPENAI_PROTOCOL` / `SMOKE_OPENAI_AUTH_MODE`, and `SMOKE_ANTHROPIC_API_KEY` / `SMOKE_ANTHROPIC_BASE_URL` / `SMOKE_ANTHROPIC_MODEL` / `SMOKE_ANTHROPIC_AUTH_MODE`. Anthropic base URLs use the API root form (for the official endpoint: `https://api.anthropic.com`); the transport appends `/v1/messages`.
2. Production Moyu Remote release builds must include `ChatBridgeProvider` (implemented in the `moyu_ui` workspace copy; not yet part of the `D:\CC` release pipeline). Both APKs must share a signing key; install Remote before Chat so the signature permission is granted at install time.
3. Release APK needs a real signing config; current release artifact is unsigned.
4. Toolchain lives in `.tools/` (gitignored); `android/local.properties` points at the local SDK.

## Original release blockers — all resolved

- Chat cannot start or create a session when Remote is absent — resolved (verified).
- Remote snapshot dynamically referenced instead of copied locally — resolved (import copies into Chat's own immutable snapshot table; unit-tested).
- Chat can arbitrarily mutate/query Remote DB — resolved (call-only provider; SQL surface disabled; verified).
- provider key appears in WebView/SQLite/logs — resolved (envelope + full-table scans in tests).
- failed input or handoff automatically resent after recovery — resolved (no retry paths; handoff submit is idempotent and explicit).
- WebView can navigate/fetch arbitrary network URLs — resolved (local-asset interception only; CSP `connect-src 'none'`; static-checked).
