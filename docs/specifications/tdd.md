# TDD — Technical Design Document

Version 1.0 · October 1, 2026 · Selected implementation design; no application or infrastructure has been built by this document.

This document selects the implementation stack, development workflow, operating procedures and capacity model. Requirements and acceptance IDs are owned by the [SRS](srs.md); exact storage/API contracts by the [LLD](lld.md); component responsibilities by the [SDD](sdd.md). The [baseline](baseline.md) owns common defaults, and [ADRs](adr/README.md) record architectural tradeoffs. Here, TDD means **Technical Design Document**. The implementation workflow also uses tests before code for consequential behavior.

## Technology and build inputs

### Android

| Concern | Selected implementation | Constraint |
| --- | --- | --- |
| App/UI | Kotlin, Jetpack Compose, Material 3, Navigation Compose, ViewModel, coroutines/Flow | Native app; minimum API 31. Compile/target SDK and plugin versions are frozen with the release toolchain, then checked against current private Play requirements. |
| Local state | Room with FTS4, DataStore for small nonsecret preferences | Room is the UI's read source; server-backed entity updates and outgoing mutation records commit together. FTS parity tests cover the public query subset. |
| Network/schema | OkHttp and kotlinx.serialization, explicit DTO-to-domain mapping | JSON contract versioning; bounded responses; no email-supplied URL is fetched by the inference adapter. |
| Background work | WorkManager `CoroutineWorker` with unique work and checkpoints | Network, charging and battery constraints; work is resumable, not assumed to run at exact times. |
| Identity/secrets | Credential Manager passkeys; Android Keystore protects local session material | Backend owns verification/revocation. Do not embed Cloudflare, Firebase service-account or connector credentials in the app. |
| Push | Firebase Messaging Android SDK | Three notification channels: critical, review reminders and processing. Permission/channel status appears in diagnostics. |
| Inference | `com.google.ai.edge.litertlm:litertlm-android`, Gemma 3 1B IT int4 `.litertlm` artifact | CPU baseline; GPU enabled only after measured acceptance on the actual S24+ variant. Model loads off the UI thread. |
| Extraction | Platform PDF APIs and bundled `com.google.mlkit:text-recognition` Latin OCR | API 35+ reads PDF text first; API 31–34 and scanned pages render then OCR. Do not silently substitute cloud OCR. |
| Wiring/tests | Constructor injection through an application container; JUnit, coroutine test tools, Room migration tests and Compose instrumentation tests | Domain tests need no device or real model. Device tests validate permissions, background behavior and native inference. |

Room FTS4, scheduling, passkey association, PDF text APIs and bundled OCR are documented capabilities; device latency and accuracy are not established. [Room FTS4](sources.md#and-04), [WorkManager](sources.md#and-05), [Credential Manager prerequisites](sources.md#and-02), [PDF API](sources.md#and-08), [ML Kit](sources.md#and-07).

### Backend and contracts

Use one TypeScript ES module Worker deployment with `email`, `fetch`, `queue` and `scheduled` handlers. Use ordinary modules and prepared SQL, without an HTTP framework or ORM. Bind the control D1 database, archive D1 partitions, private R2 buckets, work/dead-letter queues and Workers AI explicitly per environment. One-minute Cron reconciles due work in bounded batches; it does not perform bulk extraction inline.

| Concern | Selected implementation |
| --- | --- |
| HTTP/schema | Versioned JSON HTTP API; JSON Schema compiled to standalone Ajv validators during build. Kotlin DTOs and TypeScript types are checked against the same sanitized contract fixtures. |
| MIME | `postal-mime`, with application limits around nested MIME, headers, decoded size and retained parts. Stage the raw stream before parsing. |
| HTML | `parse5` and an application-owned DOM-to-text projection preserving links, paragraph/list boundaries and table cell relationships. No browser execution or remote images. |
| DOCX/export | `fflate` streaming ZIP primitives; `fast-xml-parser` for allowlisted DOCX text parts, with DTD/entity expansion disabled. No macros or embedded-object execution. |
| Passkey verification | `@simplewebauthn/server` verification helpers; explicit expected RP ID, origin, challenge, user-verification and credential ownership checks. |
| Cryptography | Workers Web Crypto for randomness, hashes, signatures and token verification. Build-time validators avoid runtime `eval`/code generation. |
| Tests | Vitest with current `@cloudflare/vitest-plugin` and local D1/R2/Queue bindings; fake clocks, inference and outbound provider adapters. |

These selections need bundle/runtime smoke tests when dependencies are frozen. Library availability does not prove unrestricted-resource parsing or correct application policy. [Upstream libraries](sources.md#selected-upstream-libraries), [Workers Vitest integration](sources.md#cf-21).

Use Workers Paid for the deployed acceptance baseline: it avoids the Free CPU ceiling for MIME/authentication work and provides paid D1 capacity/recovery. This is a deployment design, not authorization exercised by this documentation to purchase a plan. Paid Workers still has 128 MB per isolate; increasing the account plan does not increase that memory limit. [Workers pricing](sources.md#cf-06), [Workers limits](sources.md#cf-07).

### Version freeze and model manifest

Before writing deployable adapters, record exact Gradle wrapper/checksum, JDK, Android Gradle Plugin, Kotlin/compiler, Compose BOM, Android SDK, Node/npm, TypeScript, Wrangler and dependency versions in the implementation's version catalog and lockfiles. Select supported stable versions with compatible declared ranges, commit the resolved set, and reject dynamic versions such as `latest.release` in release builds. This document deliberately does not invent future package version numbers. Use the implementation freeze date as the Worker compatibility date; later changes require a tested release. [Compatibility dates](sources.md#cf-22).

The model manifest contains logical model ID, upstream repository/file/revision, SHA-256, byte count, license reference, tokenizer/template version, supported runtime version, maximum context, output cap and accepted CPU/GPU backends. Obtain the selected artifact through its documented authorized download, verify before activation, and keep it outside source control. Store a private release copy in R2 for authenticated resumable download; never ship a repository access token in the app. A changed hash is a new model version requiring evaluation, not a transparent update.

Select the 2,048-context int4 artifact profile for local v1, with at most 1,536 input tokens including prompt/history/evidence and 256 output tokens. Keep the remaining capacity as a guard. Use the artifact's tokenizer; character count is only an early bound. Split long sources into smaller jobs and reduce the retrieved excerpt set until it fits; never truncate the retained archive. The shared limit of four 6,000-character source chunks is an upper bound for retrieval, not a promise that all fit a local request. The cloud adapter enforces the same application schema with its separately bounded input and 1,000-output-token ceiling. [LiteRT-LM Kotlin](sources.md#ai-01), [selected artifact variants](sources.md#ai-03), [cloud model](sources.md#cf-18).

## Repository organization and interfaces

The following are intended implementation locations, not existing source files:

```text
android/
  app/                    application wiring, navigation, release configuration
  core/model/             domain types and policy functions
  core/data/              Room, network, repositories, synchronization/outbox
  core/platform/          passkeys, Keystore, notifications, WorkManager
  core/inference/         LiteRT-LM adapter, model lifecycle, validated results
  core/extraction/        PDF rendering/text extraction, OCR checkpoints
  feature/                inbox, archive, chat, operations, settings
backend/
  src/                    handlers and domain/adapters grouped by subsystem
  migrations/control/     identity, concerns, jobs, cursors, policy, operations
  migrations/archive/     sources, attachments, chunks, FTS partition schema
  tests/                  runtime integration and fault-injection fixtures
contracts/
  schema/                 versioned request/response and event JSON Schemas
  fixtures/               shared valid/invalid examples and golden text cases
models/                   manifest and evaluation definitions, no model bytes
ops/                      preflight, release, backup/restore and capacity procedures
```

Feature code uses domain/repository interfaces; only platform adapters depend on Android APIs. Worker handlers authenticate/validate then call domain services; only adapters access bindings. Pure code owns spam rules, inference routing, concern state transitions, policy precedence and cost reservations. One release contract manifest records HTTP schema version, event schema version, control/archive migration versions, model/prompt/extractor versions and oldest supported mobile release.

LLD contracts cover owner authentication, source/archive search, concern mutations, quarantine/blocking, device jobs, chat, sync, notification settings, exports and reviewed integrations. Cross-service consistency is implemented with durable intent plus reconciliation. D1 `batch()` can make its own statements atomic; it cannot commit R2 writes, queue publication or FCM delivery atomically with those statements. [D1 API](sources.md#cf-09), [Queues delivery](sources.md#cf-16).

## Environments and preflight

Use three named environments: local development with synthetic fixtures; remote staging with synthetic mail and separate bindings; production with the owner's archive. Android debug/staging/prod variants use different package suffixes, backend origins and Firebase projects. Staging cannot read production storage or send through production integration credentials. Tests disable real model/provider calls by default.

Use separate scoped deploy/runtime credentials. Wrangler configuration contains only binding names/IDs and nonsecret policies. Worker secrets contain FCM signing material, server token signing keys and enabled connector credentials; local secret files are ignored. Use a Firebase service account restricted to the designated messaging project. Mint short-lived OAuth access tokens using that account, cache them only in memory until shortly before expiry, and log neither them nor FCM registration tokens. [FCM HTTP v1](sources.md#fcm-01).

### Preflight and release gates

| Gate | Concrete evidence required | Failure behavior |
| --- | --- | --- |
| Identity | Chosen API/passkey domain; real package; Play app-signing SHA-256; HTTPS association JSON; owner bootstrap and recovery drill on a second device/browser | Do not enable owner sign-in or production route until association and recovery pass. No placeholder domain/fingerprint is deployed. |
| Email | Domain/MX inspection; dedicated route/address; approved paid plan and bindings; actual email round-trip including blocked sender and near-limit MIME | Keep the live route unchanged until acceptance. Worker invocation failure is not assumed to trigger sender retry. |
| Device | S24+ Android/API, SoC, free space, Play services, native ABI, notification permission; release installed through internal testing | Record measured properties. Required local inference/OCR gate stays unaccepted if the actual device fails. |
| Models | Authorized artifact, license/hash/manifest; CPU model load; 50 fixed triage/chat cases; thermal/memory/cancellation checks; cloud model smoke test | Preserve ordinary inbox/archive. Queue affected inference with a precise reason; a different model requires a superseding ADR. |
| Costs | Account's existing paid plan/allowances and shared workloads; recorded model prices; app caps; account-wide inference accounting arrangement | With unknown free-inference headroom and no paid ceiling, cloud work stays paused/local. |
| Recovery | D1 restore rehearsal, object/hash verification, current independent suppression-ledger verification, fresh identity enrollment and export restore | No production acceptance until recovery results are recorded. |
| P2/P3 | Report producer examples or connector permission/review/readback evidence for each enabled integration | Feature stays disabled; do not label a repository plan as a live producer/API. |

Cloudflare provides permanent rejection through `setReject()` and rejects oversized inbound messages before the application. The application must not interpret this as a catch-all guarantee of SMTP retry on storage outage. Intake is complete only after durable staging and registration; reconcile orphan staging objects and expose failures. The [LLD](lld.md) defines the capture receipt boundary. [Email API](sources.md#cf-02), [inbound limit](sources.md#cf-04).

## Execution and resource controls

### Capture and extraction

Manual sender/domain blocking occurs before storage or AI; retain only aggregate blocked counters. Cheap automatic classification routes suspected spam to retained quarantine; no AI, OCR or document extraction is dispatched while quarantined. Do not copy the sample documentation's automatic permanent-rejection heuristic: the accepted product uses owner release for suspected spam. [Worker filtering example](sources.md#cf-03).

MIME capture streams to private R2. Parser jobs process one source at a time, release byte buffers promptly, and checkpoint independently of AI. Test near the 25 MiB provider ceiling with text-heavy, attachment-heavy and nested multipart fixtures under the deployed memory limit. Any parse failure retains its original object and becomes a visible repair job; the system cannot claim normalized completion from raw capture alone.

For DOCX, inspect archive entries before processing, allowlist text-bearing XML parts, reject absolute/traversal paths, ignore macros/embedded executables, and bound total expanded XML to 20 MiB across at most 1,000 entries and a 100:1 expansion ratio. Exceeding the extraction cap produces canonical `unsupported` state with `limit` detail, preserving the original attachment. Stream exports without assembling the whole ZIP in memory. The 20 MiB extraction/100-page PDF defaults in the baseline limit automatic work rather than accepted evidence retention.

Android extraction uses one page at a time and saves the next-page checkpoint before taking more work. Submit at most 128 KiB of text and ten pages per result batch within the 256 KiB JSON request limit; split larger pages into numbered parts. Store immutable generation chunks, then publish the active generation through a fenced CONTROL_DB transaction; stale generation writes cannot replace visible results. On API 35+, preserve native PDF text with page references; OCR only pages without usable text. On earlier supported versions, OCR the rendered page and label it as OCR-derived. `no_ai` excludes OCR while allowing deterministic text parsing; `local_only` permits device OCR. Bundle Latin recognition so it works after installation without a secondary model fetch. Unsupported scripts/formats/encrypted files remain retained with explicit status. [PDF API](sources.md#and-08), [OCR model options](sources.md#and-07).

### Inference and budgets

The shared policy order in the baseline is applied before creating any inference request. A ready foreground device is preferred for bounded text; background triage may use allowed cloud inference. Charging background work remains subject to OS scheduling, battery/thermal rules and job leases. A paused device or local-only source must not quietly switch to cloud. Avoid long-running background inference: checkpoint short tasks, honor cancellation, and allow user-initiated foreground continuation. [Persistent work](sources.md#and-05), [long-running restrictions](sources.md#and-06).

Reserve worst-case input/output usage in D1 before dispatch using an atomic compare-and-update. Only one job can spend a reservation. Set the provider output limit, reconcile reported usage after a proven response, and hold the reservation after an uncertain response until accounting resolves it. Timeouts and malformed output do not authorize an unmetered repeat. Use bounded repair attempts only inside the job/usage caps.

The default zero incremental paid inference setting is an application dispatch policy, **not a provider billing cap**. Included neurons are shared across an account, and delayed account metrics cannot prevent another application consuming the allowance concurrently. Enable included-only cloud dispatch only when all account AI writers use a shared reservation ledger or the account is dedicated to this allocation. Otherwise use local/queued processing until the owner sets an explicit paid ceiling. Reservations, daily request caps and the ceiling bound this app's intended spending; they cannot retroactively cancel a request already sent or cap unrelated services. [Workers AI billing](sources.md#cf-17).

P3 semantic retrieval uses 384-dimensional BGE small embeddings, tokenized into at most 512-token segments with stable source spans. Do not embed quarantined, deleted, restricted/no-AI or local-only evidence. Retrieve candidates from Vectorize, then recheck their current D1 authorization, visibility and deletion status before exposing text. Rebuild indexes by model version and switch only after completeness/deletion checks. [Embedding limits](sources.md#cf-19).

### Storage, partitions and retention

CONTROL_DB owns identity, current concern/action state, source registry, jobs and synchronization cursors. ARCHIVE_0001 and later monotonic partitions own source records, attachment metadata, text chunks and FTS. Each source stays in one partition, mapped by the control registry. Normalized content above the LLD's inline threshold is stored in R2 with bounded indexed D1 chunks; no database row approaches the provider's 2 MB ceiling. Archive partitions do not duplicate mutable concern/job state. [D1 limits](sources.md#cf-10).

At 7 GiB in an archive partition, issue a capacity concern and prepare the next partition deployment. At 8 GiB, stop new source assignment to that partition while preserving reads and deletions; permit additions needed to finish already-reserved sources only within the reserved headroom. Through a reviewed infrastructure change, create the next D1 database, apply the archive schema, bind and verify it, register its monotonically increasing partition ID, then activate it as the insertion target. The control registry is updated after the new binding/schema checks pass. All existing sources retain their partition. Search fans out over at most four partitions per API page with an opaque continuation; never silently search only the newest partition.

If no writable partition is available, raw capture remains in R2 with `pending_index` state. Do not evict retained content or mark such sources fully archived. CONTROL_DB capacity has its own 7/8 GiB warning/gate: checkpoint and export historical change/audit rows into versioned R2 history segments, require older clients to rebootstrap, retain current identities/receipts/concerns, and rebuild compact control tables only after verified export. Keep the 90-day idempotency/sync windows intact. This is scheduled operator maintenance, not automatic paid provisioning.

Storage grows until the owner deletes it; no low-cost promise overrides retention. Successful raw MIME and export expiry affect only temporary copies according to the baseline. Failed capture objects remain until repaired or explicitly deleted. D1 Time Travel is a database recovery mechanism and does not restore a missing attachment object. [Time Travel](sources.md#cf-13).

Run an incremental backup daily into a separate private R2 bucket. Copy each immutable original/text object once, append changed normalized records, and write a complete daily manifest of object references, versions, hashes and source/partition IDs. Retain manifests for 30 days and garbage-collect backup objects only when no retained manifest references them. This duplicates the retained archive once, plus changed/deleted versions during the retention window; it does not create 30 full copies. Encrypt each backup object with a fresh AES-GCM data key wrapped by the owner's offline RSA-OAEP-SHA256 public key, as specified in the LLD. The backend has the wrapping public key only; independent recovery holds the private key. Before a control deletion commits, write its immutable suppression record to the independent private R2 ledger and conditionally advance the signed head. Restore verifies the current head/chain and reapplies that ledger, rather than trusting an older copy inside the backup. Same-account R2 backup protects against application/data mistakes, not loss of access to the entire Cloudflare account.

## Cost and capacity model

### Verified rates

Rates below were inspected October 1, 2026. Included use may already be consumed by Whumpo or another account workload. Dollar amounts are planning inputs, not an approved budget or measured bill.

| Service | Included / fixed charge | Marginal rate used |
| --- | --- | --- |
| Workers Paid | $5/account/month; 10M requests; 30M CPU-ms | $0.30/M requests; $0.02/M CPU-ms. [Source](sources.md#cf-06) |
| D1 Paid | 25B read rows; 50M written rows; 5 GB across databases/month | $0.001/M reads; $1/M writes; $0.75/GB-month. [Source](sources.md#cf-11) |
| R2 Standard | 10 GB-month; 1M Class A; 10M Class B operations/month | $0.015/GB-month; $4.50/M A; $0.36/M B, with published billing-unit rounding. [Source](sources.md#cf-14) |
| Queues Paid | 1M operations/month | $0.40/M; retries and >64 KB messages add operations. [Source](sources.md#cf-15) |
| Workers AI | 10,000 neurons/day across account | $0.011/1,000 neurons above allocation. [Source](sources.md#cf-17) |
| Inbound routing / FCM | No separate inbound-routing per-message fee / FCM no-cost | Worker execution, storage and other services still count. [Routing](sources.md#cf-05), [FCM](sources.md#fcm-03) |

For the selected Llama model, the planning estimate before included neurons is:

```text
gross_model_dollars = input_tokens × 0.051 / 1,000,000
                    + output_tokens × 0.335 / 1,000,000
neurons             = input_tokens × 4,625 / 1,000,000
                    + output_tokens × 30,475 / 1,000,000
paid_AI_dollars     = sum over UTC days(max(0, account_neurons - 10,000)) × 0.011 / 1,000
```

Daily unused allowance does not roll over. The dollar/token table is rounded; the neuron calculation and current provider accounting determine billing. Measure actual tokens, CPU, D1 row counts and storage separately rather than inferring them from message count. [AI pricing](sources.md#cf-17), [model card rounding](sources.md#cf-18).

### Workload scenarios

These are synthetic low/typical/high scenarios, **not estimates of the owner's actual mail**. Assume 30-day months; accepted mail includes quarantine; manually rejected mail creates no archive. Average new permanent R2 bytes include attachments/normalized recovery objects. Assume 50 KB per accepted email in D1 including text, metadata and indexes, four small queue jobs/email, 40 written/200 read rows/email, and 2,000 foreground API requests/month. Real large documents, corrections and FTS can change these ratios materially.

| Input | Low | Typical | High stress |
| --- | ---: | ---: | ---: |
| Accepted emails/day | 20 | 100 | 1,000 |
| Mean permanent R2 bytes/email | 0.25 MB | 1 MB | 2 MB |
| Automatic cloud requests/day after rules/local/quarantine/caps | 10 | 50 | 100 |
| Interactive cloud requests/month | 50 | 300 | 1,000 |
| New R2 retained data/month | 0.15 GB | 3 GB | 60 GB |
| New indexed D1 data/month | 0.03 GB | 0.15 GB | 1.5 GB |
| Queue operations/month before retries | 7,200 | 36,000 | 360,000 |
| Gross Llama usage before included allocation | $0.07 | $0.38 | $0.96 |

For those inference estimates, automatic requests average 1,200 input/300 output tokens and interactive requests 4,000 input/800 output tokens. The high scenario deliberately respects the 100/day automatic throttle; it does not promise cloud classification of every accepted message. Additional eligible work waits for local capacity or a changed owner limit. Rule-based triage and archival continue.

Approximate R2 GB-month includes two copies of permanent daily growth (primary plus incremental backup), a 5% allowance on that combined volume for backup metadata/manifests, and a conservative full seven-day MIME staging window at 1.4 times the incoming permanent-byte rate. At month 12, retained storage includes the prior eleven months. D1 GB-month is average cumulative indexed data across partitions. This table uses otherwise unused included allocations and excludes ad hoc exports, unusually large metadata/manifests, retained failed captures, deleted/replaced-object backup lag, model downloads, remote staging and P3 connector calls. Charge those explicitly using measured additional bytes/operations; never treat the table as an invoice cap.

| Routine monthly estimate | Low | Typical | High stress |
| --- | ---: | ---: | ---: |
| Month 1 R2 GB-month | 0.21 | 4.24 | 84.70 |
| Month 1 R2 storage charge, rounded units | $0 | $0 | $1.13 |
| Month 1 D1 storage charge | $0 | $0 | $0 |
| Month 1 paid-account base + listed storage | **$5.00** | **$5.00** | **$6.13** |
| Month 12 R2 GB-month | 3.68 | 73.54 | 1,470.70 |
| Month 12 R2 storage charge, rounded units | $0 | $0.96 | $21.92 |
| Month 12 D1 GB-month | 0.35 | 1.73 | 17.28 |
| Month 12 D1 storage charge | $0 | $0 | $9.21 |
| Month 12 base + listed storage | **$5.00** | **$5.96** | **$36.13** |

At the assumed high workload, archive partitions must roll over; the table does not place 17 GB in one 10 GB database. Totals sum rounded component estimates. Paid base may already be paid for another workload, in which case its *incremental* charge is zero. Conversely, if other workloads consume allowances, this app's usage becomes marginal billable usage sooner. Cron contributes 43,200 invocations per 30 days; include that idle baseline when measuring. Allow ten Class A and twenty Class B R2 operations per accepted source for capture/extraction/backup plus daily manifest operations; even the shown high scenario stays below otherwise-unused R2 operation allowances before retries. With one invocation/job and an assumed 50 ms CPU average, the shown scenarios stay within the listed compute allowances, but pathological MIME, large daily backup manifests or chat bursts can invalidate that assumption.

P3 semantic storage/inference is separate: BGE small consumes 1,841 neurons/million input tokens; Vectorize bills dimensions, with 384 per embedding. For illustration, 20,000 stored chunks occupy 7.68M dimensions. Do not activate bulk backfill until its token count, storage and query dimensions fit the owner's configured limit. Outbound email has its own account allowance/rate and onboarding gate. [BGE pricing](sources.md#cf-17), [Vectorize](sources.md#cf-20), [outbound pricing](sources.md#cf-05).

### Cost controls and measurement

- Display retained bytes, 30-day growth, oldest failed capture, D1 partition headroom, processing queues, estimated/actual inference use and last pricing refresh.
- Index due-job, source, partition, timestamp and owner lookup paths. Observe D1 read/write metadata to detect accidental scans; FTS/index maintenance is not free.
- Bound concurrency, retry count, prompt/output size and scheduled batch size. Pause optional AI/embeddings/backfill first when limits approach; preserve accepted evidence.
- Sample content-free success telemetry; record every failure class and state transition without message bodies, attachment text or credentials. Keep billing estimates labeled as estimates until reconciled.
- Review a measured seven-day production usage sample before increasing automatic model limits. Recompute a 12-month retained-storage projection; do not delete quarantine automatically to meet a cost target.

## Release, migration and recovery

### Development and verification workflow

For each vertical slice, first add its acceptance fixtures and failure cases; implement the smallest complete behavior; then run the relevant checks. Domain tests assert outcomes and invariants, not internal call sequences. A deterministic fake model establishes routing/schema behavior; a separately recorded model evaluation establishes actual usefulness. Do not make unit-test success depend on live paid APIs.

Required checks before staging are Kotlin compilation/lint/unit tests, TypeScript typecheck/lint, shared contract fixtures, Worker-runtime integration tests, migration tests and a deployable bundle build. Android instrumentation runs against minimum API and the owner's current API; release acceptance runs on the physical S24+. Maintain a small fixed corpus of synthetic MIME/attachments, fabricated Whumpo reports and adversarial instructions. The SRS owns complete acceptance IDs, including AT-01 through AT-17.

| Verification layer | Required examples |
| --- | --- |
| Policies | Manual block outranks trusted sender; quarantine suppresses processing; explicit local-only cannot fall back; corrected importance survives reprocessing. |
| Storage/jobs | R2 success/D1 failure; D1 intent/queue publication failure; duplicate queue delivery; stale lease result; parser/extractor crash; partition rollover; restore/deletion race. |
| Contract/state | Schema rejection; same idempotency key/different body; stale concern revision; expired review; lost operation response without automatic replay. |
| Mobile | Process death with pending mutation; work resumption; 90-day cursor expiry/rebootstrap; OCR page checkpoint; permissions denied; thermal cancellation and offline cache boundaries. |
| Intelligence | Unsupported citation IDs rejected; prompt injection remains evidence; summaries never replace full text; context budget bounds; uncertain provider usage remains reserved. |
| Notification | Rule-confirmed critical; AI-only urgent; quiet-hours bypass rules; FCM token rotation; delayed/duplicate delivery; review marker unaffected by background sync. |
| Operations | Sanitized report schema; stale heartbeat; sensitive support statements excluded; connector scope revoked; exact review versus changed provider state. |
| Scale | Near-limit MIME and attachment fixtures, archive partitions, 90-day offline text, concurrent job claims, 24-hour throttle reset and burst accounting. |

Record test data size, device/build/model hashes and pass/fail evidence. Passing a mock is not acceptance of a provider write. Passing an S24 Ultra sample is not acceptance on S24+. A documentation-only change uses link/consistency/traceability checks, not invented app-test results.

### Private Play delivery

Generate a release App Bundle, sign it with the upload key, and deliver only through the configured internal test track to the owner. Use Play App Signing's distributed certificate in Digital Asset Links and Firebase configuration; the upload certificate alone is insufficient. Validate passkey sign-in and FCM on the Play-installed build, not just a sideloaded debug app. [App signing](sources.md#and-10), [internal testing](sources.md#and-11).

Increase versionCode monotonically, attach release notes and the release contract manifest, and retain the prior known-good bundle metadata/build inputs. A mobile rollback is normally a new higher-versionCode build containing the compatible known-good code; do not assume a lower versionCode can replace the installed app. Model updates are independently hashed and must not silently change inference behavior.

### Migration sequence

1. Validate control and archive SQL against populated fixtures and the prior supported version; test Room migrations with pending operations. No destructive fallback for unsynchronized local data.
2. Record deployment version, D1 restore bookmark, source-object hash inventory, release manifest and current revocation/deletion ledger. Pause external effect dispatch during a risky migration.
3. Apply additive schema expansion to control and every active archive partition; deploy a backend compatible with both current and immediately previous mobile contracts.
4. Perform bounded, resumable backfill jobs; verify counts, text/hash identity and deletion exclusions. Mark each partition schema version individually.
5. Release the mobile internal build and exercise a real email-to-inbox/search/chat path plus recovery/notifications. Keep migration progress and errors visible.
6. Remove obsolete fields only after the previous client is retired, pending operations are drained and a fresh restore test passes. Unknown future schema versions produce a clear update-required response.

Use D1 versioned SQL migrations; changing a partition binding requires a new deployment. For local storage, Room migrations preserve the outbox before cached data can be rebuilt. Search indexes are rebuildable derived data; the retained source and original attachments are not. [D1 migrations](sources.md#cf-12).

### Recovery and operational controls

Keep a small operator procedure with independent Cloudflare/Play/recovery access and the offline backup decryption key; it must be usable when the phone or app is unavailable. Independent access is not a second in-app user. Export/recovery manifests list source IDs, partition versions, byte counts and hashes; validate attachments as well as database rows. Exercise a restore from the encrypted R2 backup as well as D1 Time Travel. An owner-downloaded offline export can provide separate-provider recovery evidence when one actually exists; no whole-account-outage recovery time is promised from the same-provider backup alone.

To recover: disable external-effect dispatch; obtain and verify the current independent suppression ledger; restore/rebuild affected data using the release manifest; reapply deletions before serving data; discard restored passkeys, sessions, recovery hashes and FCM registrations; require fresh operator-verified passkey/recovery enrollment; reconcile source registry against retained objects; rebuild FTS; replay only safe internal jobs; mark dispatched external operations uncertain until readback proves their outcome; then resume ordinary service. Missing current suppression evidence blocks restored archive access. Push submission is never proof of device delivery. D1 Time Travel cannot reverse a sent email, DNS mutation or missing R2 object. [D1 recovery boundary](sources.md#cf-13), [FCM delivery behavior](sources.md#fcm-02).

Provide controls to pause cloud AI, queue consumers, a connector or external effects independently while capture/authenticated archive access stays available where its dependencies are healthy. Monitor oldest pending job, oldest staged-only source, queue dead letters, failed extraction count, archive capacity, sync age, last push attempt/readback and last expected report. Emit only a meaningful concern/state change; do not manufacture a healthy status when the control plane is unavailable.

## Implementation completion evidence

The build is ready for P1 use only when the SRS P1 acceptance cases pass, the private Play build is installed on the owner device, both selected inference routes have recorded results, email capture survives tested interruption boundaries, and independent recovery succeeds. P2/P3 activation is per producer/connector, with its exact contract and acceptance evidence. Unavailable account configuration or device performance blocks the affected activation and is recorded explicitly; it does not justify silently replacing the selected architecture or claiming tests that were never run.
