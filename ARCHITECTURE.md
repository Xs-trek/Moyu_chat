# Moyu Chat architecture

## 1. Boundary

Moyu Remote remains the primary remote-control product. Moyu Chat is a supplemental APK whose core path must work with no PC, no Moyu Server, no EasyTier and no Remote database.

```text
                         shared interaction style
                                  |
                    +-------------+-------------+
                    |                           |
             Moyu Remote APK              Moyu Chat APK
             Remote runtime               Local chat runtime
                    |                           |
              Remote private DB            Chat private DB
                    |                           |
           EasyTier + Moyu Server      ProviderTransportRegistry
                    |                           |
       Claude/Codex/OpenCode                  model API
                    |
          RemoteDataBridge (optional)
                    |
             Snapshot / Handoff
```

The applications do not share a SQLite file. They share only a versioned exchange contract.

## 2. Layers

### WebView UI

Presentation only. It receives a complete/patched view model and posts versioned intents using `window.MoyuHost.postMessage`. It never owns credentials, database access, provider networking or Remote bridge access.

### Android glue

`ChatCoordinator` is the single UI host coordinator. It owns routing, intent validation, local persistence, provider execution and optional Remote exchange. It should remain smaller than the Remote `AppCoordinator`; provider protocol parsing is delegated to transports and snapshot shaping is delegated to a context builder.

### Chat domain

`ChatDatabase` owns Moyu-native sessions, timeline rows, immutable snapshots and handoff drafts. Provider-native conversation ids are not the source of truth.

### Provider transport

The transport registry is keyed by protocol, not vendor. v0.0.1 includes `openai-responses`, `openai-chat-completions`, and `anthropic-messages`. A provider configuration is data: display name, base URL, model, context/output budget and protocol. Secrets are stored separately.

Adding another compatible protocol later should mean adding a transport implementation and registry entry, not changing session storage, prompt semantics or UI message identity.

### RemoteDataBridge

Optional Android inter-app contract. Chat may:

1. detect bridge availability;
2. list safe local Remote source summaries;
3. stream one immutable normalized snapshot;
4. submit a bounded handoff package.

Chat may not query arbitrary Remote tables, read node/account credentials, mutate Remote sessions, or send directly to a PC runtime.

## 3. Session model

A Chat session is always local-owned:

```text
ChatSession
- localSessionId
- title
- providerId
- model
- state
- originSnapshotId?   // non-null means branch
- parentSessionId?    // local-chat branch provenance only
- draft
- updatedAt
```

A branch is not a second storage type. It is a normal Chat session with an immutable origin snapshot.

## 4. Snapshot model

Snapshots freeze the source state at branch creation. A branch must not continue to dereference Remote data after import.

The context projector includes user/assistant messages and bounded useful tool results. It excludes Remote transport diagnostics, approvals, usage bookkeeping and hidden reasoning by default.

## 5. Handoff

Handoff is explicit export, not synchronization. A handoff remains a local draft until the user deliberately submits it. Remote remains responsible for choosing the final target runtime/session and for sending anything to the PC.

## 6. Offline semantics

There is no global `remoteConnectionState` gate in Chat. A session's `canSend` is derived from local provider configuration and current turn state. Remote bridge availability only controls import/export affordances.

## 7. Security

- WebView is local-assets-only; no fetch/XHR/WebSocket.
- Provider API keys are entered through native UI and stored with Android Keystore wrapping.
- Cloud base URLs must be HTTPS; loopback HTTP can be allowed for local model servers.
- Remote bridge must be protected by a signature-level permission on the Remote side.
- Snapshot and handoff payloads are size bounded.
- No background resend of failed model inputs or handoffs.

## 8. Deliberately excluded from first implementation

- agent/tool execution;
- MCP/plugin marketplace;
- RAG/vector database;
- cross-device sync/CRDT;
- arbitrary provider scripting;
- automatic model discovery;
- automatic Remote writeback;
- shared SQLite files between APKs.
