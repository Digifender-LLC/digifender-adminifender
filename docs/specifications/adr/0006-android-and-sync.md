# ADR-0006 — Room-backed Android state and transactional synchronization

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

The app runs on the owner's S24+ and must remain useful without connectivity or a continuously running process. Read, acknowledged and resolved states are independent. Lost mutation responses must not duplicate an action, and timestamp ordering cannot safely resolve all concurrent edits.

## Decision

Use Kotlin, Compose Material 3, ViewModels, coroutines/Flow, Room, DataStore, WorkManager, Credential Manager, Keystore and FCM as specified by the [baseline](../baseline.md). Room-backed repositories are the UI data source. Persist a pending routine mutation and optimistic local projection before submitting its immutable identity, exact payload and expected revision.

CONTROL_DB commits authoritative entity changes and monotonic change cursors together. Android commits each received page and its cursor together. Bootstrap establishes a stable boundary and then consumes later changes. Tombstones and stale-cursor rebootstrap preserve deletion correctness. Pending intents survive rebootstrap and are reconciled against server receipts, never silently replayed with new identities.

Revision conflicts show current state and preserve the owner's intent for deliberate reconciliation. Foreground refresh, push hints and WorkManager provide opportunities to sync without promising exact scheduling. Cached text/attachments follow baseline retention and capacity. Consequential actions remain online reviewed operations, even if their draft originated offline.

## Alternatives rejected

- **Network-only UI:** loses review access during travel or outages.
- **Independent local/server truth with last-write-wins:** can silently erase a snooze, resolution or updated deadline.
- **Push as a complete event log:** cannot establish durable, ordered synchronization.
- **Timestamp-only sync:** risks skipping or reordering concurrent writes and deletions.

## Consequences and verification

Offline reads can be stale and must show synchronization state. Device revocation immediately blocks server access but cannot erase an offline cache remotely. Test process death before/after Room commits, repeated request identity, lost response recovery, reordered pages, cursor expiry, concurrent edits, deletion during bootstrap and OS-delayed background work. Minimum API is an application choice; the owner's installed version still requires preflight. See [LLD](../lld.md).
