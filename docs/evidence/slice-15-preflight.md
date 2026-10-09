# Slice 15 preflight: what was run, and what still needs the owner

Date: 2026-10-09. Scope: the repository-side and Cloudflare-side parts of the release gates in [tdd.md](../specifications/tdd.md#preflight-and-release-gates). Nothing here is a production acceptance. No Play, Firebase or device step was possible, and none is claimed.

## Run

| Gate | What was run | Result |
| --- | --- | --- |
| Release preflight (repository side) | `./gradlew :app:bundleRelease -Padminifender.versionCode=2 -Padminifender.apiBaseUrl=https://adminifender-staging.digifender.com/` (exit 0, 89 tasks), then `python3 tools/release_preflight.py check --aab android/app/build/outputs/bundle/release/app-release.aab --version-code 2 --env staging` | Ten checks passed: clean tree, no tracked keystores, secrets, Firebase credentials or model bytes, version code above the last recorded release (none), bundle exists and is not debug-signed, the staging environment is configured with provisioned IDs and `staging`-named resources, the passkey origins include an `android:apk-key-hash`, and the release contract names migrations 16 and 1. **Two failed, as they must without an upload key:** the bundle is not signed (`jarsigner -verify`) and no upload certificate fingerprint is readable. The tool printed no release manifest (it stops on failure); nothing was recorded in `ops/releases.json`. |
| Export storage | R2 lifecycle preflight steps 1 to 3 on `adminifender-archive-staging` | Done (see [staging-reconciliation.md](staging-reconciliation.md) and `ops/staging.md`). Step 4 (an open upload left for at least two days and checked through the S3 API) is not done: it needs an S3 token and the passage of time, so the one-day bound is not claimed. The Workers plan in force is Workers Paid; no `limits.subrequests` value is set on the staging Worker. |
| Cloud model smoke test | One call to `@cf/meta/llama-4-scout-17b-16e-instruct` through the Workers AI REST API with the prompt "Reply with exactly the word: ready" (a synthetic prompt, no mail content) | Answered `ready`; 17 prompt and 2 completion tokens, 0.57 neurons. This shows the model name still resolves on this account. It is not the 50 fixed triage and chat cases the gate asks for, and it does not test the Worker's own prompts, citations or budgets. |
| Email | Inventory of Email Routing on all 12 zones; the five domains' catch-alls already target `adminifender-staging` | Routing exists. No message was sent through it: this VM cannot reach port 25, and sending needs a sending subdomain on a domain of yours (DNS records), which was not created. No mail has arrived since the redeployment on 2026-10-09 (newest staged message 2026-10-08), so intake on the redeployed Worker is untested live. |
| Costs | Not run | Needs the owner to attest the account's Workers AI headroom; staging keeps a $5 monthly ceiling with headroom `unattested` (from `ops/staging.md`). |

## Still needs you (nothing here can be done from this machine)

- **Play**: create the app, enrol in Play App Signing, make an upload key, publish the app-signing certificate in Digital Asset Links, and add its `android:apk-key-hash` to production `EXPECTED_ORIGINS`.
- **Firebase**: `android/app/google-services.json` (deferred by you).
- **Production**: a production Worker, databases, buckets and queues were not created because the identity domain and the Play signing origin are not decided; creating empty ones would not make any gate pass.
- **Physical device**: passkey sign-in, push, notifications, on-device OCR and inference, the recovery drill's fresh enrollment on a second device, and a Play-installed build (deferred by you).
- **Recovery drill**: not run. It cannot be run safely here: the deletion ledger is signed with a Worker secret that is write-only, so a copy on scratch resources cannot be verified, and running it in place on staging would revoke your enrolled passkey and sessions.
- **GitHub billing**: Actions refused to start any job on 2026-10-09 ("recent account payments have failed or your spending limit needs to be increased"), so the pull requests after #23 have no CI result and cannot meet the required `docs` check.
