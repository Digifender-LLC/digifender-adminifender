# Staging reconciliation and redeployment evidence

Date: 2026-10-09. Scope: bringing two independent lines of work together and redeploying the staging Worker. Status: **deployed to staging and smoke-checked; not acceptance-tested.** No production resource was created or touched. The Cloudflare account is "Enders Account"; operations used the account API token from the environment (never printed or written down).

## What was found

- Staging already existed (provisioned 2026-10-04): the Worker `adminifender-staging`, D1 `adminifender-control-staging` and `adminifender-archive-0001-staging`, R2 `adminifender-archive-staging` and `adminifender-deletion-ledger-staging`, Queues `adminifender-work-staging` and `adminifender-dead-staging`, and Email Routing on `digifender.com`, `chapel.cloud`, `labthrift.com`, `openhost.pro` and `whumpo.com` sending their catch-alls (and some literal addresses) to that Worker. `kaina.one` has routing enabled but misconfigured; the other six zones have it off. Routes and resources were only read, never changed.
- The staging Worker had been built from the branch `origin/claude/complete-roadmap-ux66us` (34 commits from 2026-10-04), which diverged from `main` at slice 14f and implemented slices 14g to 14j, P2 reports, recovery mode and cloud-inference changes on its own. A third branch, `origin/codex/roadmap-progress-20261007`, also diverged at 14f and was not merged. The staging database held 10 sources, 2 devices and migrations up to `0012_reports`; the two lines used the same names for different tables (`extraction_leases`, `extraction_batches`) and different numbers for different migrations.
- Merging the branches wholesale would have conflicted in 36 files and silently mixed incompatible code in others, so `main` stayed the base and the other branch's server-side work was ported onto it (branch `reconcile/main-plus-staging`).

## What was ported, and what was not

Ported: P2 signed Whumpo reports (disabled by default) and their migration, recovery mode and its migration and runbook, the cloud-inference changes (quote citations, paid ceiling with unattested headroom, repair errors, Llama 4 Scout settings), the enrollment page, passkey failure logging, the staging wrangler environment, the ops runbooks and `tools/release_preflight.py`, the CI workflow additions (staging APK artifact, shared debug key step; upstream action-version bumps kept), and the Android staging build type, upload-key signing and version properties.

Not ported (decisions, not omissions): that branch's Android code for slices 14g to 14j and its server-side device extraction (`main`'s versions are kept; the extraction API moved to `/device-work/extraction/*`); the Calm Ledger redesign and its JVM screenshot tests; the P2 Operations screen on Android; its Robolectric versions of the Room tests; local model and LiteRT code; and the whole Codex branch. "Resume next pages" exists only in the other branch's design, which publishes a partial generation and extends it later, which `main`'s immutable-generation rule does not allow.

## Verification before deployment

- Backend, on Node 22.22.0, from `backend/`: `npm run check` exit 0 (typecheck, **37 files, 918 tests**, bundle 1517.78 KiB / 319.10 KiB gzip) and `wrangler deploy --env staging --dry-run` exit 0, whose binding table names only the staging resources.
- Android, pinned toolchain, one session: exit 0, `BUILD SUCCESSFUL in 34m 11s` for `:core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest :core:data:lintDebug :app:lintDebug :app:assembleStaging :app:assembleDebug :app:assembleRelease`; **446 JVM tests** (27, 352, 67), no failures. The staging APK (48 MB, package `.staging`) was built, not installed.
- `python3 tools/check_docs.py` passes.
- Two test failures during the merge were fixed, not weakened: the release-contract tests named migration 13 and now name 15.

## Deployment, in order

1. Read-only inventory (Workers, D1, R2, Queues, Email Routing rules, plan). Plans in force: Workers Paid, R2 Paid.
2. Rollback points: D1 time-travel bookmarks (control `0000015b-0000000a-000050ff-ff7e8a8eb5f700e4d59510e5620cd696`, archive `00000013-00000000-000050ff-8886b01d12d27a77722c766d69356d70`) and Worker versions `fbee07ef-…` and `826522a8-…`. Live `/api/v1/meta` before: `controlMigration: 12`.
3. R2 export lifecycle preflight steps 1 to 3 on the staging archive bucket (see `ops/staging.md`).
4. `wrangler d1 migrations apply adminifender-control-staging --env staging --remote`: `0013`, `0014`, `0015` applied (the archive database had none pending). Before this, staging held 0 extraction leases and 0 batches, which is why `0015` may drop the prototype tables.
5. `wrangler deploy --env staging`: version `3a0d83dd-fa39-4325-b7f2-c421ab7822b3`. Secrets and bindings were left as they were.

## Smoke checks after deployment (unauthenticated or read-only)

`/api/v1/meta` reports `controlMigration: 15, archiveMigration: 1`; `GET /health` 200; `GET /sources`, `POST /device-work/extraction/claim`, `GET /exports/{id}` and `GET /operations/{id}` answer 401; the retired `POST /device-extraction/claim` answers 404; `GET /enroll` 200. The scheduled pass recorded itself at 13:13:03 UTC, after the deploy (it records only when every step succeeds, including the new ones). All 42 existing jobs are `complete`. The four new tables exist.

## Not verified

- No signed-in request was made, so no authenticated route, no sync, chat, export or extraction call was exercised on staging. Owner sign-in needs the passkey on the phone, and the phone was out of scope.
- No new email was injected, so intake through the merged code is covered by the local tests only. Real mail keeps arriving through the existing routes; whether it is being processed correctly has not been checked beyond job counts.
- Behavior of the earlier-built APK against this Worker was not tested; it is expected to mismatch (see `ops/staging.md`).
- Production: nothing was created. The identity, Play and Firebase prerequisites in the roadmap are untouched.
