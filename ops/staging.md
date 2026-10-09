# Staging environment

**Status (2026-10-09): provisioned 2026-10-04; redeployed twice on 2026-10-09: from the reconciled branch (Worker version `3a0d83dd-fa39-4325-b7f2-c421ab7822b3`, control migration 15), then from the tip of pull request #26 (commit `36a1a91`, Worker version `222e2074-6915-4fd1-938a-79da3eeea2f9`, control migration 16).** Nothing in staging is production accepted. The runs recorded below as made on 2026-10-04 were on the earlier build; see the [reconciliation note](../docs/evidence/staging-reconciliation.md) for what changed and what has not been repeated.

| Item | Value |
| --- | --- |
| Cloudflare account | Enders Account |
| Worker | `adminifender-staging`, custom domain `https://adminifender-staging.digifender.com` (`wrangler.jsonc` `env.staging`) |
| Passkey RP ID | `digifender.com` (the root domain; staging and production use separate backends and Android packages) |
| D1 | `adminifender-control-staging`, `adminifender-archive-0001-staging` (IDs in `backend/wrangler.jsonc`) |
| R2 | `adminifender-archive-staging`, `adminifender-deletion-ledger-staging` |
| Queues | `adminifender-work-staging`, dead letters `adminifender-dead-staging` |
| Workers AI | Bound as `AI`. Inference settings default to an unattested account and a paid ceiling of 0, so cloud stays denied (`account_headroom_unknown`) until the owner attests headroom in settings. |
| Secrets | `CURSOR_SECRET`, `LEDGER_SIGNING_KEY` and `PUSH_TOKEN_KEY` were generated randomly at first deploy and exist only as Worker secrets. Rotate them with `wrangler secret put --env staging`. |
| Android | Build type `staging`: package `com.digifender.adminifender.staging`, backend URL above |

## Deploy and migrate

Run from `backend/` with a Cloudflare credential scoped to this account:

```sh
npm ci && npm run check
npx wrangler d1 migrations apply adminifender-control-staging --env staging --remote
npx wrangler d1 migrations apply adminifender-archive-0001-staging --env staging --remote
npx wrangler deploy --env staging
curl -s https://adminifender-staging.digifender.com/api/v1/meta   # contract must name the newest migrations
```

Apply migrations before deploying code that needs them. Staging must never bind production resource IDs.

## Sideloaded staging APK (no Play signing yet)

Until Play App Signing exists, staging builds without `ADMINIFENDER_UPLOAD_*` are signed with the builder's local Android debug key so they install by sideloading. Every machine has its own debug key, so build on one machine and register its fingerprint once:

```sh
cd android && ./gradlew :app:assembleStaging            # app/build/outputs/apk/staging/app-staging.apk
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA256
python3 ../tools/release_preflight.py apk-origin --sha256 <that fingerprint>       # add to EXPECTED_ORIGINS
python3 ../tools/release_preflight.py assetlinks --package com.digifender.adminifender.staging --sha256 <that fingerprint>
```

Each Android CI run on a pull request also uploads an `adminifender-apks` artifact with the staging APK and the unsigned release APK. CI signs the staging APK with the owner's registered debug key from the `ADMINIFENDER_STAGING_DEBUG_KEYSTORE` Actions secret (the base64 of `debug.keystore`), and the run notes the certificate SHA-256 it used. Without the secret, as on forks, the APK gets a throwaway key: it installs but cannot sign in. The debug key's password is the public default, so the secret alone protects it, and it is for staging only. To add or replace it:

```sh
base64 -w0 ~/.android/debug.keystore    # Linux; macOS: base64 -i ~/.android/debug.keystore
# Windows PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore"))
```

Paste the output as the repository secret `ADMINIFENDER_STAGING_DEBUG_KEYSTORE` (Settings, Secrets and variables, Actions). A new key also needs its origin and asset-links fingerprint registered as above.

**Registered:** the owner's debug key (SHA-256 `FA:8D:6B:9C:…:3E:09:BB`) is in staging `EXPECTED_ORIGINS` as `android:apk-key-hash:-o1rnKJPY5wdBS3AUHeGPt1biuSl6EAiKbs2AnQ-Cbs`. A staging APK built on that machine, or by CI with the secret below (added 2026-10-04), can sign in with a passkey.

**Passkey origin on the S24+ (2026-10-04):** after asset-links verification, the phone's passkey provider reported the origin `https://digifender.com` for the staging app's sign-in, not `android:apk-key-hash:…`, so staging rejected it (`passkey_verify_failed` with `unexpected_origin` in the Worker logs). With the owner's approval, staging `EXPECTED_ORIGINS` also accepts `https://digifender.com`. Any page served from digifender.com could then complete a staging passkey sign-in. Production needs its own decision in the release preflight.

The asset-links statement is published on `https://digifender.com/.well-known/assetlinks.json` (the `digifender-website` repository). A debug key is only for staging: production uses Play App Signing.

## Not configured yet

- **Owner enrollment (done 2026-10-04; repeat only after a reset):** the operator runs `node tools/bootstrap-token.mjs`, applies its SQL to the staging `CONTROL_DB` (`npx wrangler d1 execute CONTROL_DB --env staging --remote --file <sql>`) and gives the owner the printed `afb_` code, which works once within 15 minutes. The owner opens `https://adminifender-staging.digifender.com/enroll` in Chrome on the phone, pastes the code, creates the passkey (saved for `digifender.com`) and stores the recovery secret offline. The staging app's **Sign in with passkey** then uses that passkey.
- **Digital Asset Links (production package):** `https://digifender.com/.well-known/assetlinks.json` lists only the staging package so far ([digifender-website#3](https://github.com/Digifender-LLC/digifender-website/pull/3), live since 2026-10-04). `com.digifender.adminifender` gets its own statement once Play App Signing exists.
- **Cloud AI (on since 2026-10-04, owner decision):** staging settings (revision 2) use `@cf/meta/llama-4-scout-17b-16e-instruct` at 24,545 and 77,273 neurons per million input and output tokens and $0.011 per 1,000 neurons ([sources](../docs/specifications/sources.md#cf-23)). Llama 3.2 3B, the first choice, failed the citation and JSON checks; a chat answer now costs about 25 neurons. Because the owner can't rule out other Workers AI use on the account, it is `unattested`: the shared 10,000-neuron daily allocation isn't counted on, and all cloud work counts against a $5/month paid ceiling (about 454,000 neurons, roughly 18,000 chat answers). That ceiling is this app's dispatch limit, not a Cloudflare billing cap. The operator wrote it directly as settings revision 1 in staging `CONTROL_DB`, since there was no owner settings row yet; later changes go through `PATCH /settings`.
- **Push:** no `FCM_SERVICE_ACCOUNT` secret and no staging Firebase project, so delivery reports `push_unavailable`.
- **P2 reports:** `REPORTS_ENABLED` is not set, so all P2 routes answer `feature_disabled`. Enabling it on staging needs a `REPORT_KEY_WRAP` secret and a registered alias with a key, as part of the P2 acceptance run.
- **Email (routed 2026-10-04, owner decision):** Cloudflare Email Routing sends every address at digifender.com, whumpo.com, openhost.pro, labthrift.com and chapel.cloud (catch-all plus the former `admin@openhost.pro` and chapel.cloud `hello@`, `dmarc@` and `tlsrpt@` rules) to the `adminifender-staging` Worker. This replaced the earlier forwards to the owner's personal inboxes, so mail to these domains is no longer delivered anywhere else. labthrift.com previously refused all mail; its null MX was removed, Email Routing turned on and its SPF changed to `v=spf1 include:_spf.mx.cloudflare.net ~all`. The owner accepted staging receiving this mail because no real mail arrives at these domains yet. Before production, route these domains to the production Worker (or back to forwards) and keep staging on a synthetic address. To undo, set each zone's rules back to forwarding in the Cloudflare dashboard under Email, Email Routing, Routing rules.

## Reconciled deployment (2026-10-09)

The staging Worker was first deployed from a branch that implemented slices 14g to 14j and P2 separately from `main`. On 2026-10-09 `main`'s code, with that branch's server-side work ported onto it, was deployed in its place. What this means for anything that talks to staging:

- **Device extraction paths changed.** Phones now use `POST /api/v1/device-work/extraction/{claim,<id>/heartbeat,<id>/batch,<id>/outcome}`; the earlier `/api/v1/device-extraction/*` and `/attachments/{id}/resume-extraction` routes are gone (404). "Read more pages" (`POST /api/v1/attachments/{id}/read-more`), byte ranges on attachment and export downloads and `GET /api/v1/exports` were added in the second deployment.
- **Migrations:** `0013_recovery_runs`, `0014_export_scope` and `0015_device_extraction` were applied; `0015` first drops the earlier prototype's `extraction_leases` and `extraction_batches` (empty on staging) and creates the current ones under the same names. `/api/v1/meta` reports `controlMigration: 15`.
- **An APK built from the earlier branch does not match this Worker** (different chat, briefing, export and extraction contracts). Build a new staging APK from this branch (`./gradlew :app:assembleStaging`) before using the phone against it. That APK has not been installed or tried on a phone.
- **R2 export lifecycle preflight, steps 1 to 3 (2026-10-09):** on `adminifender-archive-staging` the rule `abort-stale-export-uploads` (prefix `exports/`, abort incomplete multipart uploads after 1 day) was added next to the unchanged `Default Multipart Abort Rule` (7 days), and `lifecycle list` was compared before and after. Step 4 (an open upload through the S3 API, checked at least two days later) is not done, so the one-day bound is not claimed.
- **Rollback points taken before the change:** D1 time-travel bookmarks `0000015b-0000000a-000050ff-ff7e8a8eb5f700e4d59510e5620cd696` (control) and `00000013-00000000-000050ff-8886b01d12d27a77722c766d69356d70` (archive), and the previous Worker versions `fbee07ef-ed5b-4845-a2d9-69523910e01f` and `826522a8-34ff-4c20-b740-0379503f8a4d` (`wrangler versions list --env staging`). Restoring the control database to its bookmark also undoes the three migrations above.

### Second deployment (2026-10-09, from an unmerged branch)

Pull requests #24 to #26 could not run CI (GitHub refused to start jobs for a billing reason), so none of them is merged. The Worker on staging was deployed from the tip of the stacked branch `feat/range-downloads-and-export-list` (commit `36a1a91`, which contains #25 and #26; #24 changes only Android tests). Before: D1 control bookmark `00000161-0000003a-000050ff-69e12f2e7e41878a35274ec228cc1993`, previous Worker versions `3a0d83dd-fa39-4325-b7f2-c421ab7822b3` and the earlier ones listed above. Migration `0016_read_more_pages` (additive columns on `attachment_processing` and `extraction_leases`) was applied, then `wrangler deploy --env staging`. Checks after: `/api/v1/meta` reports `controlMigration: 16`; `GET /health` 200; `GET /exports`, `GET /exports/{id}/content`, `GET /attachments/{id}/content`, `POST /attachments/{id}/read-more` and `POST /device-work/extraction/claim` answer 401 without a session; the retired `/device-extraction/claim` answers 404; the scheduled pass recorded itself after the deploy; the 42 existing jobs are still complete. Nothing was exercised signed in. Staging therefore runs code that is not on `main`; when the pull requests merge (squashed), `main` will contain the same content.

