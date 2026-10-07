# Adminifender

Personal Kotlin/Jetpack Compose Android app for a Galaxy S24+: retain email and attachments, review important concerns, act with explicit controls, and chat over the evidence. Cloudflare supplies the backend and optional inference; automatic hybrid routing also supports local inference.

**Status:** The Worker email/archive, identity, review, sync, inference, chat, push and diagnostics services have local synthetic test coverage. Android domain/data, authentication, sync and initial inbox/archive screens are implemented, with further UI and device-work slices underway. Cloud provisioning, live integrations, private Play delivery and physical-device acceptance remain unverified. See the roadmap for each slice's evidence and limits.

Start with the [specification guide](docs/specifications/README.md). Implementation progress is tracked in the [roadmap](docs/roadmap.md). It links BRD, PRD, FRD, SRS, HLD, SDD, TDD, LLD and the ADR collection.

- [Accepted product notes](docs/product-notes.md) — owner decisions, complete accepted scope and phases.
- [Requirements and acceptance](docs/specifications/srs.md) — 54 functional requirements, 12 quality requirements and canonical traceability.
- [Whumpo evidence and accepted features](docs/feature-suggestions.md) — adjacent-project findings and integration dependencies.
- [Documentation validation](docs/specifications/validation.md) — checks actually performed and their limits.
- [Release and recovery evidence](ops/README.md) — read-only preflight inventory, synthetic recovery checks and remaining release gates.

Email remains the only external ingest source through P1/P2. All later integrations are accepted P3 requirements, activated individually after verification. Manual blocks reject before storage; suspected spam is retained in quarantine. The archive is kept until explicit deletion.
