# ADR-0005 — Policy-first hybrid inference

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

The owner wants both local inference and Cloudflare-hosted models. The phone may be unavailable, thermally constrained or not ready to run a model. Cloud use has privacy and cost constraints. Neither route can become necessary for retaining and searching evidence or using routine inbox controls.

## Decision

Implement local LiteRT-LM/Gemma and Cloudflare Workers AI adapters using the selected model identities in the [baseline](../baseline.md). Freeze artifacts/versions before device acceptance. A shared task/result schema permits the same application behavior, but input budgeting is route-specific: local token/context bounds are independent of cloud character bounds. Chunk long inputs without truncating the archive.

Apply policy before task claim and again before dispatch: deletion/privacy, quarantine and local-only/no-AI restrictions precede route readiness, allowances and model interpretation. Auto mode prefers eligible foreground local work and permits background cloud triage under the baseline rules. Local-only queues for the device without cloud fallback. No-AI also excludes OCR and embeddings, while deterministic text parsing remains allowed. Reserve cloud allowance before dispatch and account for uncertain usage conservatively. Both adapters validate citations and typed proposals; neither executes model-produced code.

## Alternatives rejected

- **Cloud-only first release:** fails the accepted requirement for both routes and leaves no local option under cost/privacy limits.
- **Local-only global architecture:** cannot classify new server mail while the phone is unavailable.
- **Silent fallback:** violates explicit privacy choices and obscures costs.
- **Same oversized prompt on both models:** ignores the selected local model's bounded context.

## Consequences and verification

Some inference waits, and low-cost local quality may differ from cloud output. The route/reason/model and coverage are visible; malformed or unsupported output becomes a recoverable stage failure. Local-only does not change cloud archival. Verify model memory/latency/thermal behavior on the actual S24+, policy changes during queued work, exhausted budgets, route-specific context overflow, stale task completion, schema validation and AI-independent workflows. See [TDD](../tdd.md) for dated model evidence and verification gates.
