# ADR-0003 — Durable jobs, fenced attempts and honest uncertainty

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

Email arrival, private-object writes, database commits, queue delivery, phone background execution and external provider requests cannot share one transaction. Network loss can hide a completed result. A retry policy suitable for extraction is unsafe for email sending, infrastructure changes or uncertain Whumpo recovery.

## Decision

Store replay-safe jobs and outbox records in CONTROL_DB, transactionally with their controlling state and sync changes. Queues carry job identities, not the only copy of work. A scheduled reconciler repairs due outbox entries, expired leases and partial captures. Claims use conditional state updates and fenced attempt tokens; stale results cannot commit. Retry limits, delays and lease timing are defined once in the [baseline](../baseline.md).

Archive-partition/R2 writes publish through verified immutable references; reconciliation handles their partial outcomes. Inference/extraction can resume bounded checkpoints. Device tasks use the same job identity and lease semantics as backend tasks.

External effects use immutable operation intents and a separate review/dispatch/receipt state machine. Persist and consume authority before dispatch. Missing or inconclusive responses become **uncertain**, with readback/manual reconciliation; they never automatically replay. Preserve every prior intent and receipt.

## Alternatives rejected

- **Queue-only state:** a missed handoff or expired message can silently erase work.
- **Exactly-once delivery assumption:** ignores cross-service gaps and provider-specific effect semantics.
- **Retry every timeout:** can send duplicate replies or perform a second infrastructure change.
- **Long-lived phone-only coordinator:** makes capture and recovery depend on app uptime.

## Consequences and verification

Some crashes deliberately result in uncertainty even when no external request was sent; truthful state is preferable to duplicate effects. Control-plane transactions and dispatch reconciliation are core invariants and must be fault-tested. Test crashes before/after object writes, archive publication, queue dispatch and provider dispatch; stale leases; duplicate completion; process death; and uncertain readback without redispatch. See [SDD](../sdd.md) and [LLD](../lld.md).
