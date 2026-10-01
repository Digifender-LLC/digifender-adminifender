# ADR-0010 — Bounded processing cost and content-free diagnostics

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

Email volume, attachment volume and a comfortable monthly budget are unknown. Spam can incur storage and inference work. A quiet inbox must not conceal a broken pipeline. Cost estimates, internal usage and invoices are different evidence classes and should not be conflated.

## Decision

Evaluate manual blocks before archival and inexpensive automatic spam suspicion before costly processing. Quarantine retains evidence but skips OCR and downstream AI until release. Apply explicit model/job/input limits, bounded retries, concurrency controls, capacity alarms and baseline allowances. Cloud automatic inference uses the included allocation only until the owner configures a paid ceiling; zero incremental paid inference does not imply zero infrastructure cost. Included-only cloud dispatch requires a dedicated allocation or a reservation ledger used by every account AI writer; otherwise cloud dispatch defers until the owner configures a paid ceiling. Delayed provider metrics cannot establish exclusive free headroom.

Reserve expected usage before dispatch; settle measured usage when available and retain conservative reservations for uncertain provider outcomes. Track provider included allowance and owner-configured ceilings independently, blocking when either disallows work. A fallback to local/queued state is visible. Provisioning new archive partitions or paid capacity is a reviewed infrastructure step, never an inference side effect.

Collect content-free stage timings, counts, queue age, failure classes, capacity and notification observations under the baseline retention window. Store source/job/operation identities sufficient for authorized diagnosis without message bodies, customer statements or credentials. Capacity estimates use low/typical/high scenarios with explicit assumptions, not an invented owner budget.

## Alternatives rejected

- **Model classify every arrival before spam checks:** pays for spam and blocks capture on AI availability.
- **Assume free-tier usage indefinitely:** hides finite account limits and unknown workload.
- **Unlimited automatic retries:** amplifies cost during persistent failure.
- **Log raw messages for debugging:** creates an uncontrolled second private archive.

## Consequences and verification

Limits can defer interpretation while evidence remains available. Provider billing may include unrelated account use or non-AI resources, so application counters are not promised to cap the entire invoice. Test concurrency around the final allowance, uncertain usage, malicious oversized input, queue backlog, failed capture visibility, secret-free logs and capacity rollover. Dated pricing evidence and calculation assumptions are recorded in [TDD](../tdd.md).
