# Signal Server boundary

`server/` is reserved for the independent Signal Server owned by this public repository.

G0 intentionally contains **no server runtime or business implementation**. The following remain deferred to G7:

- auth / device registration;
- event/context/media metadata ingestion;
- sync cursor and replay;
- resumable object upload;
- multi-device state;
- server-side AI job hooks.

Signal Server is independent from P14 Server and must not use P14 product protocols as implementation authority.

Any future server implementation must keep public-safe contracts/tests/CI in this repository while credentials, private deployment topology, runtime databases and private datasets remain outside Git or in the registered private companion as appropriate.
