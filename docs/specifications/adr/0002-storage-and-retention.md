# ADR-0002 — Durable text evidence and private original attachments

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

The archive must retain complete email text and attachment bytes indefinitely until explicit deletion, while remaining searchable and inexpensive. Keeping a growing archive in one database would conflict with provider capacity limits. Summaries and embeddings are insufficient archival evidence. Manual rejection and automatic spam suspicion have different retention requirements.

## Decision

Use `CONTROL_DB` for mutable source registry, identity, concerns, jobs, cursors, reviews and receipts. Bounded D1 archive partitions hold immutable normalized source revisions, extraction chunks and FTS indexes. Private R2 holds staged MIME, attachments and export bytes. Partition routing, rollover thresholds and bounded search fan-out follow the [baseline](../baseline.md). A new bound partition is provisioned by a reviewed deployment; if none is writable, keep accepted MIME staged and expose pending indexing.

Write and verify object/archive data before atomically publishing its ready pointer in CONTROL_DB. No transaction spans R2 and the databases. Reconciliation repairs partial writes using immutable identities and hashes. Deletion first appends a content-free record and conditionally advances the signed head of an independent private R2 suppression ledger, then hides via a control tombstone and later removes originals and derived data. Restore must verify and reapply the latest independent ledger rather than an older snapshot copy; unavailable current suppression evidence blocks restored reads. Retention windows and cleanup targets are exactly those in the baseline.

Reject manually blocked senders before archival. Retain automatically quarantined mail, but prevent downstream OCR/AI until release. Export normalized records, relationships, original files and integrity manifest; credentials are excluded.

## Alternatives rejected

- **Single unbounded D1 archive:** cannot satisfy indefinite growth within a finite per-database limit.
- **Attachments in SQL:** consumes database capacity and increases row/query overhead for binary content.
- **Summary/vector-only archive:** loses original meaning, prevents reliable reprocessing and weakens source-grounded answers.
- **Permanent raw MIME requirement:** stores presentation the owner does not need; temporary successful staging plus permanent failed-capture recovery is sufficient.

## Consequences and verification

Partition management and cross-store reconciliation are explicit maintenance costs. Search may return continuation instead of scanning every partition at once. Local inference does not imply end-to-end encrypted cloud archival. Test cross-store failures, source corruption, rollover without a writable partition, explicit deletion/restore, quarantined retention and original attachment byte fidelity. See [LLD](../lld.md) and [TDD](../tdd.md).
