---
name: adminifender-release-review
description: Assess Adminifender release readiness from contracts and recorded evidence.
---

# Release review

Read `AGENTS.md`, `docs/verification.md`, SRS acceptance cases and the TDD preflight/release/recovery sections. Preserve unrelated changes and never read secret values or delegate without explicit request.

Assess exact intended release, CI results, Android/Worker contract and migration compatibility, staging email round-trip, model/license/hash and real S24+ measurements, Play signing/passkey/FCM on the Play-installed build, cost controls and independent recovery. Distinguish implemented, locally verified, staged and accepted. Give blockers and missing evidence. This workflow is readiness assessment only; it does not deploy, migrate remote data, upload to Play, activate routes/connectors, purchase capacity, merge or push.
