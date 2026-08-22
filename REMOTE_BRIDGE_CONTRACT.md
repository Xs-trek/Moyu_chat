# RemoteDataBridge contract v1

## Purpose

Moyu Chat and Moyu Remote are separate APKs with separate private databases. This contract exposes only the minimum semantic data required to branch from locally cached PC sessions and to hand results back.

Suggested Android authority:

`content://com.moyu.remote.chatbridge`

The Remote provider/service must be exported only under a signature-level permission and should additionally validate the caller package/signing certificate.

## Methods

### `status`

Returns:

```json
{
  "contractVersion": 1,
  "available": true,
  "capabilities": ["listSources", "createSnapshot", "submitHandoff"]
}
```

### `listSources`

Returns only locally cached source metadata:

```json
{
  "items": [
    {
      "sourceId": "opaque-id",
      "title": "session title",
      "runtime": "codex",
      "updatedAt": "ISO-8601",
      "cachedThroughSeq": 120,
      "complete": true
    }
  ]
}
```

`sourceId` is an opaque bridge identifier. Chat must not infer a Remote DB primary key or native provider session ID from it.

### `createSnapshot`

Input:

```json
{ "sourceId": "opaque-id", "throughSeq": 120 }
```

Output:

```json
{
  "snapshotId": "remote-export-id",
  "sourceId": "opaque-id",
  "sourceTitle": "session title",
  "sourceRuntime": "codex",
  "createdAt": "ISO-8601",
  "items": [
    { "kind": "message", "role": "user", "text": "...", "createdAt": "..." },
    { "kind": "message", "role": "assistant", "text": "...", "createdAt": "..." }
  ]
}
```

Remote performs normalization. Chat validates again and copies the accepted items into its own immutable Snapshot table.

Remote should not export source system/developer prompts, approvals, credentials, hidden reasoning, transport diagnostics, native database blobs or arbitrary tool payloads in v1.

## `submitHandoff`

Input is a bounded package created by Chat:

```json
{
  "handoffId": "uuid",
  "branchSessionId": "uuid",
  "sourceSnapshotId": "uuid-or-null",
  "summary": "user-visible conclusion/state",
  "selectedContext": [],
  "createdAt": "ISO-8601"
}
```

The bridge may place this in a Remote-side inbox. It must not automatically send it to Claude/Codex/OpenCode. Final target/session/runtime choice remains a Remote-side explicit user operation.

## Availability semantics

Bridge absence is normal. Chat must treat these as `available=false`, not startup failures:

- Remote APK not installed;
- Remote DB never created;
- provider authority unavailable;
- contract version unsupported;
- Remote feature disabled.

No failed import/handoff is automatically retried in the background.
