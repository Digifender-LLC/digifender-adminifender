# ADR-0009 — Signed reports first, gated typed connectors later

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

Every suggested feature is accepted, but initial external ingestion must stay email-only. Whumpo has useful maintenance/release/operator artifacts, not an already available Adminifender operator API. Direct actions must preserve both products' privacy, review and uncertain-outcome boundaries.

## Decision

P1 implements the personal email workflow. P2 adds new bounded, sanitized report email producers and operational views while retaining email-only intake. Reports use a versioned HMAC wrapper over exact decoded payload bytes, per-producer keys, service/kind authority, event deduplication and sequence tracking as defined in the [LLD](../lld.md). Recognized report envelopes and dedicated aliases remain no-AI until validation. Invalid/unknown/protected-field reports remain retained owner evidence under restricted-metadata quarantine, with no fallback to ordinary model-eligible email. Reported observations are labeled last-reported and do not grant action authority. Operator-decision reports exclude protected customer statements.

P3 activates explicit connector instances for scoped direct reads, signed webhooks/GitHub sync, selected Android notifications/share intake, reviewed outbound replies and typed infrastructure/Whumpo actions. An adapter owns prepare/dispatch/readback semantics; capability validation and owner-approved scope precede activation. Revocation stops new dispatch and invalidates pending reviews. There is no generic shell, arbitrary URL tool or automatically replayed uncertain effect.

## Alternatives rejected

- **Introduce all ingest channels in P1:** violates the agreed email-only start and delays the core workflow.
- **Treat sender domain as report authenticity:** transport identity alone does not authorize service/kind claims or protect structured payload integrity.
- **Reuse Whumpo customer credentials as operator access:** crosses a deliberate privilege boundary.
- **Display emailed observations as live health:** misrepresents the evidence's age and completeness.

## Consequences and verification

P2 requires separately implemented Whumpo producers; P3 requires narrow provider/API adapters and acceptance gates. They are designed capabilities, not deployed integrations. Test invalid/replayed/conflicting reports, revoked keys, late events, missed-webhook reconciliation, grant revocation, protected operator text exclusion, exact-effect review and uncertain result readback. [SDD](../sdd.md) specifies each operational view and connector behavior.
