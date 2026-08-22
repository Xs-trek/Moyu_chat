# Architecture research notes

The goal of this research is to extract stable architectural patterns, not copy feature breadth.

## Patterns worth keeping

### Provider != session

Open WebUI explicitly distinguishes model providers from autonomous agents. Moyu Chat v1 follows the provider-only side of that boundary; tools/agents remain out of scope.

### Central protocol/provider registration

Chatbox documents an evolution from provider changes scattered across many files to a registry-oriented provider architecture. Moyu Chat uses a smaller version: provider configurations are data and a transport registry owns protocol implementations.

### Shared data contract as a separate concern

LibreChat separates shared data/API types from backend implementation packages. Moyu applies this principle across APKs: `RemoteDataBridge` exchanges stable DTOs while Remote and Chat keep independent databases and runtimes.

### Branching belongs above provider APIs

LobeChat exposes branching conversations while also supporting multiple providers and local/remote database modes. The useful lesson is that branch identity should belong to the application's conversation model, not to a vendor session id. Moyu Chat therefore freezes a provider-neutral Snapshot.

### Capabilities should be data, not UI assumptions

Continue and Cherry Studio model/provider systems both expose capability/registry metadata. Moyu Chat keeps only the small subset needed initially (`streaming`, `imageInput` later, protocol id), avoiding a full marketplace/plugin model.

## Patterns intentionally not adopted

- Open WebUI/LibreChat server-centric multi-user infrastructure: Moyu Chat is single-user Android local-first.
- LobeChat CRDT/local-remote synchronization: unnecessary for a supplemental APK.
- mature provider catalogues with pricing/model metadata: first version accepts an explicit model id and does not probe providers.
- tool/plugin/agent abstraction: deferred until a concrete Local Chat need exists.
