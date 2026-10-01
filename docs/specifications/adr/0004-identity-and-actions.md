# ADR-0004 — Passkey owner identity and exact-effect reviews

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

This is a personal application, but it holds private mail and may later send messages or modify infrastructure. Losing the only phone must not permanently lock out the owner. An AI suggestion, received email or old incident runbook cannot confer authority to act.

## Decision

Enroll the owner through an offline-provisioned short-lived single-use bootstrap secret and a passkey. Use revocable device/session records and rotating refresh credentials. Independent recovery uses a one-use offline 256-bit recovery key, a server-side one-way verifier, replacement passkey enrollment, session revocation and recovery-key rotation. Email is not the recovery channel. Disaster restore discards all snapshot passkeys, sessions, recovery hashes and FCM registrations; fresh independent operator-verified enrollment is required rather than trusting potentially revoked credentials from the backup. Android credentials use Credential Manager and device credential storage uses Keystore.

Routine inbox controls are authenticated, revision-checked mutations and can queue offline. Consequential actions require online preparation against current state, exact parameters/target/effects, fresh passkey verification and an expiring review bound to the immutable intent digest. Consume a review once. Changed target, parameters, authority or resource revision requires a new preparation/review. Session/review durations are the [baseline](../baseline.md) values.

Backend adapters hold narrowly scoped provider credentials. Received content and model responses are evidence/proposals only; no arbitrary executable tools or URLs are accepted.

## Alternatives rejected

- **Email-only login/recovery:** couples account recovery to the same mail pipeline being administered.
- **Single unrecoverable phone secret:** fails the explicit independent recovery requirement.
- **Long-lived blanket “AI may act” permission:** does not bind authority to the actual effect or current target.
- **Cloudflare tokens on the phone:** expands compromise impact and bypasses backend review policy.

## Consequences and verification

The owner must save independent recovery material; onboarding verifies that step before completion. Offline device caches cannot be remotely erased until connectivity returns. Provider identity/scopes and passkey Android origin association need deployed verification. Test challenge replay, wrong origin, refresh reuse, recovery key reuse, revoked devices, expired/stale reviews, duplicate mutation identities and prompt injection attempts. See [LLD](../lld.md).
