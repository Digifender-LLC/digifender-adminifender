# Private Play release

Builds go only to the Play internal test track for the owner ([TDD private Play delivery](../docs/specifications/tdd.md#private-play-delivery)).

## One-time setup (owner)

1. Create the app `com.digifender.adminifender` in Play Console and enrol in Play App Signing.
2. Create an upload keystore outside the repository; store it and its passwords in the operator vault.
3. Copy the **app-signing** certificate SHA-256 from Play Console. The upload certificate alone is not enough. Publish Digital Asset Links on `https://digifender.com/.well-known/assetlinks.json`:
   `python3 tools/release_preflight.py assetlinks --package com.digifender.adminifender --package com.digifender.adminifender.staging --sha256 <app signing> --sha256 <staging signing>`
4. Add `android:apk-key-hash:<base64url of that certificate's SHA-256>` to the environment's `EXPECTED_ORIGINS`.
5. Create the Firebase Android app for each environment, then pass its options as `-Padminifender.firebase.*` (see `android/README.md`).

## Each release

```sh
export ADMINIFENDER_UPLOAD_STORE_FILE=... ADMINIFENDER_UPLOAD_STORE_PASSWORD=... ADMINIFENDER_UPLOAD_KEY_ALIAS=... ADMINIFENDER_UPLOAD_KEY_PASSWORD=...
cd android && ./gradlew :app:bundleRelease -Padminifender.versionCode=<next> -Padminifender.apiBaseUrl=https://<prod host>/ \
  [-Padminifender.modelManifest=../models/<manifest>.json] [-Padminifender.firebase.appId=... ...]
cd .. && python3 tools/release_preflight.py check --aab android/app/build/outputs/bundle/release/app-release.aab \
  --version-code <next> --env <environment> --out release-manifest.json
```

Upload the bundle to the internal track with the manifest as release notes. Then run `python3 tools/release_preflight.py record --manifest release-manifest.json` and commit `ops/releases.json`.

Acceptance needs the Play-installed build on the S24+: install and update, passkey sign-in, push, a real email-to-inbox, search and chat path, and the recovery drill. A sideloaded build is not evidence.

**Rollback:** ship a new, higher `versionCode` built from the last known-good commit in `releases.json`. A lower code cannot replace an installed app. For the backend, redeploy the previous Worker version (`wrangler rollback --env <env>`) only if it is compatible with the current schema; migrations are expand-only.
