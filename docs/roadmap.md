# Implementation roadmap

Tracks delivery of the [baseline phases](specifications/baseline.md#delivery-phases) in small reviewable slices. Status words follow [AGENTS.md](../AGENTS.md): **planned**, **implemented**, **locally verified**, **staging verified**, **production accepted**. Nothing below is deployed, and no slice is staging verified or production accepted.

## P1 core app

| Slice | Requirements | Status | Evidence |
| --- | --- | --- | --- |
| 1. Worker scaffold, D1 migrations, shared contract fixtures, backend CI | NFR-010; LLD storage, conventions, queue envelope, triage output | Locally verified | `cd backend && npm ci && npm run check`: typecheck, Vitest tests (Workers runtime with local D1, Node contract fixtures), dry-run bundle |
| 2. Email admission: block rules, raw R2 staging, registry and parse job | FR-003, FR-004, AT-02 (blocking and staging cases) | Locally verified | `npm run check`: exact-domain/address and match-field rules, rejection before the body is read with only per-rule daily counters, hash-keyed R2 staging, recipient-scoped dedupe, registry + parse job + outbox in one batch, and R2, D1 and queue failure cases. Live routing and actual sender behavior on bounce are not tested. |
| 3. MIME parsing, normalization and archive publication | FR-006, FR-007, FR-008, AT-03 | Planned | |
| 4. Spam heuristics `spam-rules-v1`, quarantine and release | FR-005, AT-02 | Planned | |
| 5. Durable jobs, outbox, leases and cron reconciler | NFR-001, NFR-004 | Planned | |
| 6. Owner identity: bootstrap, passkeys, sessions, recovery | FR-001, AT-01 | Planned | |
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
