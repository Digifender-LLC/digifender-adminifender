# ADR-0007 — Server eligibility and private FCM push

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

Critical concerns and neglected actionable backlog should attract attention even while the app is not running. The target phone has Google Play services. Push is not an exact alarm and may be denied, delayed or accepted by a provider without being displayed. Lock-screen content should not disclose private mail.

## Decision

Evaluate critical, due-date and inactivity eligibility on the backend using committed concern/settings revisions. Use FCM HTTP v1 for delivery to registered, revocable devices. Send generic text and opaque identifiers; fetch current authenticated details in the app. Distinguish provider acceptance, observed device delivery/display, tap and meaningful owner review.

Apply the [baseline](../baseline.md) quiet hours, inactivity thresholds and material-signal deduplication: `criticalSignalId` changes only with material critical evidence/escalation, and `dueRevision` only with a changed due obligation. Generic read/review revisions do not create another alert. Trusted rule-confirmed critical events may bypass quiet hours; AI-only critical classification enters urgent review without that bypass. Background sync and push receipt do not reset the meaningful-review watermark. Use system-notification actions only for routine controls or opening authenticated review.

## Alternatives rejected

- **Phone-only scheduling:** misses new backend arrivals while the app is unavailable.
- **Frequent polling for critical attention:** increases device/server work without reliable immediacy.
- **Full message content in push:** exposes private evidence outside the authenticated UI.
- **Treat delivery as resolution:** confuses transport behavior with an owner decision.

## Consequences and verification

FCM is an intentional dependency outside Cloudflare. Delivery remains best effort, and the inbox is authoritative. Notification permission, token rotation, denied registration, quiet-hour/timezone transitions, duplicate pushes, stale concerns and actual background behavior must be verified on the S24+. Local-only inference may delay AI classification, while deterministic server rules can still produce critical notifications. See [TDD](../tdd.md) for primary platform evidence and [SDD](../sdd.md) for collaboration.
