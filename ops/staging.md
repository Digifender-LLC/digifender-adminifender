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
- **Push:** no `FCM_SERVICE_ACCOUNT` secret and no staging Firebase project, so delivery reports `push_unavailable`.
- **P2 reports:** `REPORTS_ENABLED` is not set, so all P2 routes answer `feature_disabled`. Enabling it on staging needs a `REPORT_KEY_WRAP` secret and a registered alias with a key, as part of the P2 acceptance run.
- **Email:** no staging email route or address, so capture is untested live. Synthetic mail needs a dedicated staging address, never the production route.
