# G0 Architecture Foundation

This document is the public implementation checkpoint for `tevriqorg/Signal#3`.

## Scope

G0 establishes boundaries only. It intentionally does **not** implement Gallery indexing/grouping, passive Sensors, Sign in with ChatGPT, provider inference, or Signal Server sync/business behavior.

Signal is independent from P14. No P14 Server, P14 product protocol, or P14 runtime dependency is part of this architecture.

## Baseline preservation

The current working Android baseline lives in the existing `app` module and contains cellular/network observation, Shizuku enhancement, ping/traceroute diagnostics, and provider transfer tests. G0 keeps those classes and behavior in place to avoid a large rewrite.

The existing root package `com.wb.signalpro` is therefore treated as the **legacy application/diagnostics surface** during G0. Feature extraction is incremental and must not change working diagnostics behavior merely to satisfy package aesthetics.

## Android boundaries

New v0.2 code should converge on these boundaries:

```text
com.wb.signalpro
├── agent
│   ├── provider      # provider contracts only in G0
│   └── tools         # typed Signal Tool boundary
├── photos            # G1+
├── timeline          # G5+
├── sensors           # G5+
├── diagnostics       # existing behavior; migrate incrementally only when useful
├── sync              # G7+
└── data
    ├── model         # local semantic/data contracts
    └── repository    # persistence ports; storage implementation comes later
```

The project intentionally remains a single Android Gradle module in G0. The v0.2 design does not require an immediate mechanical multi-module migration, and avoiding one reduces regression risk.

## Agent provider boundary

`AiProvider` is the stable Android-side provider contract. G0 defines capability discovery, model discovery, auth state, and streamed response events without implementing a provider.

Two provider families are represented as contracts:

- OpenAI-compatible;
- ChatGPT plan / Sign in with ChatGPT.

Authentication flows, HTTP request semantics, token storage, dynamic client registration, and real inference are later gates.

## Signal Tool boundary

Agent access to Signal data goes through typed `SignalTool<Input, Output>` contracts. Tools expose descriptors and execute with an explicit `SignalToolContext` rather than receiving arbitrary database access.

The catalog boundary is type-erased only at lookup time. Each concrete tool implementation remains strongly typed.

Redaction, permission checks, limits, pagination, and evidence references belong at the tool boundary when concrete tools arrive in later gates.

## Local data boundary

G0 defines stable identity and semantic records for:

- `MediaItem`;
- `SignalEvent`;
- `Episode`;
- `ContextState`.

Repository ports define how higher layers talk to persistence. G0 does not choose Room tables, migrations, MediaStore indexing logic, or event collectors yet.

Raw sensitive payloads are not embedded into the generic event envelope. `SignalEvent` carries a `payloadRef` plus sensitivity / retention / sync policy metadata.

## Server boundary

The public repository owns future Signal Server source under `server/`, but G0 provides only the boundary marker. Server auth, device registration, ingestion, sync, cursor/replay, uploads, and AI jobs remain deferred to G7.

## Dependency direction

Expected direction:

```text
UI / feature orchestration
        ↓
Agent tools / feature services
        ↓
repository ports + data contracts
        ↓
platform/storage adapters (later gates)
```

Providers depend on provider contracts; Signal tools depend on repository/feature interfaces. Agent/provider code must not reach directly into Room/SQLite or Android collectors.

## G0 acceptance

G0 is acceptable when:

1. the existing Android release APK still builds in GitHub Actions;
2. existing Shizuku/network/diagnostics code is not functionally rewritten;
3. provider contracts compile but perform no live inference;
4. typed Signal Tool contracts compile but expose no real user data yet;
5. Media/Event/Episode/Context data boundaries compile without adding collectors/indexers;
6. `server/` contains no business implementation;
7. no P14 implementation dependency is introduced.

Later feature work must follow the v0.2 gate sequence.
