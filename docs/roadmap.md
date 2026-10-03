# Implementation roadmap

Tracks delivery of the [baseline phases](specifications/baseline.md#delivery-phases) in small reviewable slices. Status words follow [AGENTS.md](../AGENTS.md): **planned**, **implemented**, **locally verified**, **staging verified**, **production accepted**. Nothing below is deployed, and no slice is staging verified or production accepted.

## P1 core app

| Slice | Requirements | Status | Evidence |
| --- | --- | --- | --- |
| 1. Worker scaffold, D1 migrations, shared contract fixtures, backend CI | NFR-010; LLD storage, conventions, queue envelope, triage output | Locally verified | `cd backend && npm ci && npm run check`: typecheck, Vitest tests (Workers runtime with local D1, Node contract fixtures), dry-run bundle |
| 2. Email admission: block rules, raw R2 staging, registry and parse job | FR-003, FR-004, AT-02 (blocking and staging cases) | Locally verified | `npm run check`: exact-domain/address and match-field rules, rejection before the body is read with only per-rule daily counters, hash-keyed R2 staging, recipient-scoped dedupe, registry + parse job + outbox in one batch, and R2, D1 and queue failure cases. Live routing and actual sender behavior on bounce are not tested. |
| 3. MIME parsing, normalization and archive publication | FR-006, FR-007, FR-008, AT-02, AT-03 | Locally verified | Golden fixtures in `contracts/fixtures/mime/` (plain, HTML-only, Unicode QP, agreeing and conflicting alternatives, inline and attached files, missing Message-ID, over-nested MIME). Parse job publishes R2 objects, archive rows, chunks, FTS and receipt, then a fenced `ready` with change record; covers retry idempotency, crash recovery, `parse_failed`, no writable partition and bodies over 256 KiB in R2. |
| 4. Spam heuristics `spam-rules-v1`, quarantine and release | FR-005, AT-02 | In progress: scoring and quarantine at publication locally verified; release, overrides API and transport authentication planned | Exact LLD weights and threshold; quarantined sources keep full text and get no downstream jobs. SPF/DKIM and confirmed-spam samples score 0 until trusted authentication metadata is established in preflight. |
| 5. Durable jobs, outbox, leases and cron reconciler | NFR-001, NFR-004 | Locally verified for replay-safe jobs | Fenced claims, stale-claimant discard, exponential retry to dead state, unknown kinds fail visibly, outbox and due-job re-publication. Uncertain dispatched work arrives with inference and effects. |
| 6. Owner identity: bootstrap, passkeys, sessions, recovery | FR-001, AT-01 (server side) | Locally verified; device revocation waits for reviews (slice 10) | Software ES256 authenticator in tests: one-time bootstrap (`tools/bootstrap-token.mjs`), origin and user-verification checks, challenge replay, 15-minute access, rotating 30-day absolute refresh with family revocation on reuse, recent-auth on the initiating session only, recovery that replaces passkeys, bumps the auth epoch and revokes all sessions, single-use recovery under concurrency, rate limits. Real passkeys, Digital Asset Links and the Play signing origin need the owner's domain and device. |
| 7. Source, attachment and search APIs | FR-013, AT-03 | Planned | |
| 8. Rules, concerns, lifecycle and briefings | FR-014 to FR-020, AT-05, AT-06 | Planned | |
| 9. Server text extraction (text, CSV, JSON, HTML, DOCX) | FR-009, AT-04 | Planned | |
| 10. Sync, idempotent mutations and deletion/export | FR-011, FR-012, FR-033, AT-10 | Planned | |
| 11. Inference routing, budgets and cloud adapter | FR-021, FR-022, FR-024, AT-07 | Planned | |
| 12. Grounded chat | FR-025 to FR-028, AT-08 | Planned | |
| 13. Notifications and diagnostics | FR-029 to FR-032, AT-09 | Planned | |
| 14. Android app: Gradle toolchain, Room, sync, Compose screens | FR-002, FR-010, FR-023, AT-10, AT-15 | Planned | |
| 15. Release preflight, recovery drill and private Play build | NFR-009, AT-15, AT-16 | Planned; needs owner accounts and the physical S24+ | |

## P2 Whumpo reports and P3 opt-in extras

Planned after P1. Each P2 producer and P3 connector stays disabled until its own acceptance gate passes ([SRS](specifications/srs.md#acceptance-scenarios)).
