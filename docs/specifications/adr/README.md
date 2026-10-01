# Architecture Decision Records

Version 1.0 · October 1, 2026. All records below are **accepted design decisions, not evidence of implementation or deployment**. They use the [baseline](../baseline.md) for shared constants and the [SRS](../srs.md) for requirements. A changed decision requires a new numbered ADR that names the superseded record; preserve the historical record and add its supersession link.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-personal-boundaries.md) | Independent personal Android application and modular Worker backend | Accepted |
| [0002](0002-storage-and-retention.md) | Control database, bounded archive partitions, private objects, explicit retention/deletion | Accepted |
| [0003](0003-jobs-and-recovery.md) | Durable jobs/outbox, fenced leases and explicit uncertain effects | Accepted |
| [0004](0004-identity-and-actions.md) | Passkeys, independent recovery and exact-effect reviewed operations | Accepted |
| [0005](0005-hybrid-inference.md) | Policy-first hybrid inference with compatible validated outputs | Accepted |
| [0006](0006-android-and-sync.md) | Native Room-backed offline app with transactional cursor synchronization | Accepted |
| [0007](0007-notifications.md) | Server-side eligibility, FCM transport and private push hints | Accepted |
| [0008](0008-search-and-extraction.md) | Retained evidence, bounded extraction, FTS first and derived semantic retrieval | Accepted |
| [0009](0009-phased-integrations.md) | Email-only P1/P2, signed operational reports and gated typed P3 connectors | Accepted |
| [0010](0010-cost-and-observability.md) | Measured allowances, inexpensive early filtering and content-free diagnostics | Accepted |

The [HLD](../hld.md) shows how decisions compose. [SDD](../sdd.md) assigns subsystem behavior, [TDD](../tdd.md) supplies dated technology evidence and verification gates, and [LLD](../lld.md) defines implementation contracts. Requirement-to-ADR mapping belongs only to the SRS traceability matrix.
