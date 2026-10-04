# Staging environment

**Status (2026-10-04): provisioned and deployed with synthetic data only.** Nothing in staging is production accepted.

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

The asset-links statement is published on `https://digifender.com/.well-known/assetlinks.json` (the `digifender-website` repository). A debug key is only for staging: production uses Play App Signing.

## Not configured yet

- **Owner enrollment:** create a bootstrap token with `tools/bootstrap-token.mjs` and enroll a staging passkey from the staging app.
- **Android passkey origin:** after the staging build is signed, add its `android:apk-key-hash:` origin to `EXPECTED_ORIGINS`.
- **Digital Asset Links:** `https://digifender.com/.well-known/assetlinks.json` must list both packages with their signing fingerprints. The `digifender-site` Worker serves that domain; generate the file with `python3 tools/release_preflight.py assetlinks`. Until then, Android passkey sign-in cannot work.
- **Push:** no `FCM_SERVICE_ACCOUNT` secret and no staging Firebase project, so delivery reports `push_unavailable`.
- **P2 reports:** `REPORTS_ENABLED` is not set, so all P2 routes answer `feature_disabled`. Enabling it on staging needs a `REPORT_KEY_WRAP` secret and a registered alias with a key, as part of the P2 acceptance run.
- **Email:** no staging email route or address, so capture is untested live. Synthetic mail needs a dedicated staging address, never the production route.
