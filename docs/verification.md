# Verification and CI plan

## Current state

Runnable checks: `python3 tools/check_docs.py` (`.github/workflows/docs.yml`) and, in `backend/`, `npm ci && npm run check`, which runs the TypeScript typecheck, the contract fixtures in Node, Worker-runtime tests against local D1 migrations and a dry-run Wrangler bundle (`.github/workflows/backend.yml`). GitHub secret scanning and push protection are repository settings, not a workflow; `.github/dependabot.yml` proposes weekly npm, Gradle and GitHub Actions updates; actions are pinned to commit SHAs. Node 22.22.0 is pinned in `backend/.nvmrc` and dependencies in `backend/package-lock.json`. Inspect the run before reporting a CI result. In `android/`, `./gradlew :core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest :core:data:lintDebug :core:data:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug :app:assembleRelease` runs the domain, data and view-model JVM tests (including the shared sync fixture), compiles the instrumentation tests without running them, runs Android Lint with warnings as errors and builds an unsigned release (`.github/workflows/android.yml`); it needs JDK 21 and an Android SDK with API 37. An emulator CI job is being added for API 31/36, but defining a job is not an executed hosted result, and API 36 is not a measurement of the owner device. No staging service or Play build exists. Do not report these checks as passing without their actual results. Slice status lives in the [roadmap](roadmap.md).

## Release-tool evidence

On 2026-10-07, `node --test tools/release_preflight.test.mjs tools/release_recovery.test.mjs` passed all 18 cases using pinned Node 22.22.0. These cover candidate revision and evidence scope/hash validation, report path confinement, signed suppression-chain tampering/staleness, valid orphan sequence gaps, restored deletion/identity checks and conservative replay selection. `node tools/release_preflight.mjs` returns exit 1 until candidate evidence is recorded; this is an expected blocked-readiness result. The [operations guide](../ops/README.md) defines the evidence boundary and exact local commands.

## Integrated checkpoint, 2026-10-07

Using JDK 21.0.12, the pinned Gradle wrapper and Android SDK 37, `./gradlew --no-daemon --max-workers=2 :core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest :core:data:assembleDebugAndroidTest :app:assembleDebug` passed: 218 JVM tests (model 12, data 170, app 36), debug app and instrumentation APK compilation. Room AppDatabase v3 and ChatDatabase v1 schemas were generated. Log: `/workspace/verification-evidence/android-integration-development-7.log`. Full integrated lint, unsigned release, emulator tests and hosted CI have not yet been observed at this checkpoint.

In `backend/`, pinned Node 22.22.0 `npm ci --offline` and `WRANGLER_SEND_METRICS=false npm run check` passed typecheck, 313 tests in 27 files and the dry-run Worker bundle. Log: `/workspace/verification-evidence/backend-integration.log`. This includes briefing/waiting and extraction protocol work through `63782f6`; later model-triage and extraction-GC work is separate.

Android integration adds JVM regressions for stale-session responses, a sign-out barrier around suspended cache commits, pending edits without deleted titles and saved navigation IDs. Room instrumentation adds populated v2→v3 migration, stale-page/rebootstrap suppression, conflict retention and deletion-after-sync cases. These Room/Keystore/device tests have compiled but have not yet executed at this checkpoint; emulator results must be recorded separately.

## Add the gates with implementation

| Layer | Tool and gate | Evidence boundary |
| --- | --- | --- |
| Shared contracts | Versioned JSON Schema and valid/invalid fixtures in `contracts/`; check Kotlin DTO and TypeScript type mapping | Local fixtures prove schema compatibility, not live provider behavior. |
| Android compile/static | Committed `./gradlew`; Kotlin compilation, Android Lint, formatting/static checks selected with the frozen toolchain | Add exact Gradle tasks once modules exist; no global Gradle fallback. |
| Android JVM | JUnit, coroutine test tools and deterministic fake adapters; policy, routing, state transitions and sync/outbox invariants | No device or real model required. |
| Android instrumentation | Room migration and Compose/OS tests on API 31 and owner's current API; process death, WorkManager resumption, permissions, offline sync, OCR checkpoints | Emulator evidence is separate from physical-device acceptance. |
| Worker static/build | Frozen Node/package-manager lockfile; TypeScript typecheck, lint, standalone Ajv schema build and Worker bundle | Reject dynamic release dependencies. |
| Worker integration | Vitest Cloudflare runtime with local D1/R2/Queue, fake clocks/model/outbound provider; MIME, jobs, auth, idempotency and failure injection | No production bindings or paid inference in routine tests. |
| Migrations/recovery | Populated prior-version D1 and Room fixtures; forward compatibility, retained pending intents, deletion/revocation replay, independent object/hash checks | Rehearsal evidence precedes production acceptance. |
| UI/accessibility | Compose semantics, keyboard/touch targets, screen-reader labels, font scaling, light/dark/system states, screenshots inspected at phone sizes | Images alone do not prove interaction/accessibility. |
| Device/model | Owner's Galaxy S24+, exact build/model hash; CPU load, accuracy corpus, cancellation, memory/thermal, passkeys, FCM and Play-installed build | Record measured results; mock or another phone model is insufficient. |

The [TDD development workflow](specifications/tdd.md#development-and-verification-workflow) gives the required failure examples. The [SRS](specifications/srs.md) owns AT-01 through AT-17 and their traceability. Before staging, require Kotlin compile/lint/unit tests, TypeScript typecheck/lint, shared fixtures, Worker-runtime tests, migration tests and a deployable bundle. Do not silently skip a gate because a runner lacks a device or provider credential; mark it pending and keep release acceptance blocked where applicable.

## CI shape after source exists

Use ordinary read-only PR checks and a main-branch check with pinned actions/toolchain versions, frozen dependencies and no production secrets. Keep Android and Worker jobs separate so failures identify their boundary. Cache dependencies, not mutable test state or secrets. Run instrumentation on a supported emulator runner or a controlled device service; publish sanitized test reports only. Keep deploy, remote migrations, Play upload, provider activation and purchases outside automatic quality CI. Add an explicit release workflow only after release contracts, credentials and owner authorization are defined.

For each change, run focused checks locally, then the full relevant PR gates. For docs-only changes, use the link/parity checker and inspect cross-document consistency. For feature changes, cite the affected requirement and acceptance IDs, including negative cases. Report commands and failures exactly. Do not lower thresholds or replace real-device/model evidence with fixture claims.

## Release evidence

Use the [TDD preflight and release sequence](specifications/tdd.md#release-migration-and-recovery). Check exact package/domain/signing association, synthetic staging email round-trip, Worker and Room migrations, recovery drill, model artifact/license/hash and physical S24+ measurements, FCM/passkey behavior on a Play-installed build, and SRS acceptance results. P2/P3 producers/connectors each have their own activation evidence. Record unresolved account configuration and cost limits as pending rather than changing the selected architecture silently.
