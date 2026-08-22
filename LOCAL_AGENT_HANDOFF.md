# Local development handoff

This copy was authored and statically reviewed without executing npm, Gradle, Android emulator, network requests or provider APIs.

The local agent should treat all code as an implementation baseline, not a verified build.

## Required first checks

1. Build the Web UI and copy/sync it into Android assets.
2. Compile Android with the selected AGP/Gradle/JDK versions available locally.
3. Fix Java/Android API or OkHttp compatibility errors without changing the architecture boundary.
4. Add tests for database migrations, snapshot immutability, context projection, Remote bridge absent/present behavior and streaming cancellation.
5. Run an emulator smoke with **no Moyu Remote installed**. Local session create/send/history must still work.
6. Run with Moyu Remote installed but its server/overlay stopped. The bridge import affordance should depend only on the Android bridge provider, not EasyTier.
7. Implement the matching signature-protected provider in Moyu Remote only after the Chat-side contract is stable.
8. Verify API keys never enter WebView view models, JS storage, logs or database rows.

## Release blockers

- Chat cannot start or create a session when Remote is absent.
- Remote snapshot is dynamically referenced instead of copied locally.
- Chat can arbitrarily mutate/query Remote DB.
- provider key appears in WebView/SQLite/logs.
- failed input or handoff is automatically resent after process/network recovery.
- WebView can navigate/fetch arbitrary network URLs.
