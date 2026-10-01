# FRD — Functional Requirements Document

Version 1.0 · October 1, 2026 · Selected observable behavior; implementation pending.

## Authority and common rules

The [SRS](srs.md) owns normative FR/NFR requirements and the sole traceability matrix. This document specifies user-visible workflows for those IDs. The [baseline](baseline.md) owns numeric defaults, policy precedence, terminology, and phase definitions; [LLD](lld.md) owns wire contracts, persisted records, and algorithms. Numeric defaults are intentionally not copied here.

Email/source state, inference/extraction state, concern state, and external operation state are separate. Every response and screen distinguishes reported facts from inferred conclusions. An accepted capability assigned to P2 or P3 remains in the full product scope. Unsupported controls render a readable explanation with no enabled execution affordance.

<a id="identity"></a>
## 1. Identity and native app

**Requirements:** FR-001–002. **Phase:** P1. **Acceptance:** AT-01, AT-10, AT-15, AT-17.

1. On first use, authenticate the single owner with the configured passkey relying-party identity. Complete independent recovery setup before declaring enrollment complete. The recovery artifact must be usable without the enrolled phone; it is not an emailed login link or an AI-readable secret.
2. Persist only the current owner's session and permitted cache. Keep refresh material in protected device storage. Show the device list and allow revocation. A revoked or expired session cannot sync, download files, submit inference results, or commit pending edits.
3. Recovery enrolls replacement credentials through a restricted workflow, revokes prior sessions, and requires normal owner authentication before granting archive or action access. Recovery never resumes external effects automatically. Disaster restore additionally discards restored passkeys, sessions, recovery hashes, and push registrations, then requires fresh operator-verified passkey/recovery enrollment before archive access resumes.
4. Before signing out while authenticated, warn if device-only pending edits would be discarded. Sign-out revokes the session family, wipes protected local application content and delivery registration, and cancels local work. Signing in again retrieves authoritative server state; an old registration token is not identity proof. An offline phone cannot prove server revocation until connectivity permits it.
5. The native UI reads Room state first, shows the last successful sync, and exposes pending changes. Users can inspect cached evidence and make supported routine edits offline. Authentication setup, recovery, fresh downloads, consequential review/commit, and direct service reads require a connection.

<a id="intake"></a>
## 2. Email capture, blocking, and quarantine

**Requirements:** FR-003–006. **Phase:** P1. **Acceptance:** AT-02, AT-17.

The receipt workflow applies policy in the baseline order. Explicit blocks run before durable body/attachment archival. A block names its sender or domain scope and shows what future mail it will affect. Matching uses canonical addresses and explicit domain boundaries; a substring match cannot block an unrelated domain. The receipt audit retains only the bounded aggregate/category needed for diagnostics, never blocked message bodies or attachments. Adding a block does not delete prior accepted mail.

For mail that is not blocked:

1. Durably stage the original MIME, record a stable ingest identity, then normalize. A delivery replay resumes the same capture and cannot create duplicate attachment storage or duplicate concerns. Message-ID alone is insufficient duplicate proof.
2. Apply deterministic spam rules and trusted ingestion/authentication signals. A suspicious classification quarantines the accepted source. If the detector fails, retain the message with a visible processing failure; do not silently discard it or claim it passed.
3. Quarantine retains normalized evidence and attachment bytes, but withholds the message from normal triage, chat retrieval, automatic notifications, extraction, and OCR. Quarantine has its own count, search/filter, reason, and inspectable source. Parsing required to preserve readable text is allowed.
4. The owner can **Release**, **Delete**, or **Block future mail**. Release records the override and runs eligible downstream stages once under the current privacy policy. Protected report aliases/envelopes establish a privacy floor before model work: their raw content remains model/OCR-ineligible even after release, and only a validated sanitized projection can enter inference. Mark not spam offers a separately confirmed future sender rule; release itself does not trust every later message from that sender.
5. Retain the full decoded textual content and meaningful HTML link destinations/structure. If plain-text and HTML alternatives differ substantively, retain both in the normalized evidence. A compact display may collapse quoting; it cannot replace the archive with a summary.
6. Preserve available envelope/header addresses, timestamps, authentication evidence, thread references, and original attachment bytes. Malformed mail remains recoverable from staging and appears in Health with its failure category. A parse failure must not be presented as a successfully empty email.

Provider refusal before the Worker receives mail cannot be guaranteed into the archive. The app explains this boundary in source setup and exposes known provider limits. A storage/write failure cannot be reported as completed archival. Recovery status identifies whether capture is staged, normalized, quarantined, ready, or failed without confusing that status with concern resolution.

<a id="archive"></a>
## 3. Archive, search, export, and deletion

**Requirements:** FR-007–008, FR-011–013. **Phase:** P1. **Acceptance:** AT-03, AT-17.

The archive includes accepted low-priority mail as well as inbox sources. Ordinary archive results exclude quarantine unless the owner enables that filter. Search supports normalized text, sender/recipient, date range, service/project label, source type, attachment name, and available extracted text. It works without inference; offline results clearly identify the cached coverage and cannot claim a complete server search.

Each source detail displays original received metadata, full normalized text, linked concerns, attachment inventory, parse/extraction status, and source freshness. Original attachments are downloaded only through authorized access. Files are never made public to obtain a convenient link. Large or unsupported files remain stored and downloadable even when not processed.

**Export:** The owner selects the archive or a filtered subset and requests an export. Persist the export job and show preparing/completed/failed status. A completed export includes normalized records, original attachment files, source relationships, and a checksum manifest in the selected archive format from the baseline. Export excludes credentials, device tokens, raw authentication challenges, and hidden provider secrets. Quarantine is included only when explicitly selected. Expired export downloads can be regenerated from retained evidence.

**Delete:** Show the selected sources/files, number of affected records, and loss of retrieval/citation availability in an exact reviewed operation. Before committing deletion, append the content-free record to the independent private R2 suppression ledger and advance its verified head; a failed ledger write leaves deletion pending. Commit then hides content from server/online reads immediately, issues tombstones, and schedules content/derived-object cleanup within the baseline window. Offline phones purge when they learn the change at their next authenticated sync or rebootstrap. Delete search/semantic projections, invalidate affected exports, and prevent pending inference from recreating them. Existing concern/operation history can retain content-free references, but cannot keep deleted excerpts. Citation navigation says the source was deleted. Backup recovery verifies and reapplies the newest independent suppression ledger before restored content becomes accessible; unavailable deletion evidence blocks restored archive access. Deletion is not the same as Done, archive suppression, blocking, or routine undo.

<a id="extraction"></a>
## 4. Attachment extraction and OCR

**Requirements:** FR-009–010. **Phase:** P1. **Acceptance:** AT-04, AT-07, AT-17.

Canonical extraction states are `stored`, `queued`, `extracting`, `ready`, `unsupported`, `failed`, and `deferred`; secondary detail records `encrypted`, `limit`, `device_unavailable`, `policy_blocked`, or a fixed parser error. Storage integrity is tracked independently. The selected format/runtime and processing bounds are defined in the baseline and technical design. The UI always offers the retained original when authorized.

Supported bounded text formats are parsed on the backend without executing macros, scripts, links, or embedded code. PDFs and supported images wait for the device extraction worker when required. It records text positions/page references so search and chat can cite an attachment excerpt. OCR-derived text is labeled as extracted and can be incorrect; the owner can open the original to verify it.

Extraction pauses for quarantined sources, deleted content, missing device authorization, or unsuitable device readiness. The `no_ai` policy prohibits OCR and generative processing; deterministic text parsing remains allowed. Protected raw report content stays model/OCR-ineligible after release. A phone that is offline does not cause a paid server OCR fallback. Retry is bounded and preserves attachment identity and hashes. Unsupported archives, encrypted documents, audio, and video remain retained without an understanding claim. Automatic recursive unpacking and arbitrary URL fetches are excluded.

Extraction writes immutable generation batches. Only a current fenced control transaction can publish the active generation after validating the input hash, policy revision, device authority, and complete batch manifest. Readers use that published generation; late or incomplete writes cannot replace visible text. Resume bounded page work from its checkpoint, and show deferred remaining pages with the supported resume control. Retrying a failed attempt does not overwrite original evidence or duplicate published chunks.

Local-only inference governs where model interpretation occurs; it does not change the selected Cloudflare archive location. Device-extracted text is synchronized as a derived archive record unless a source policy excludes that processing. The UI makes this distinction clear before enabling local-only mode.

<a id="inbox"></a>
## 5. Triage, concerns, and review controls

**Requirements:** FR-014–020. **Phase:** P1. **Acceptance:** AT-05–06.

### Triage and explanation

After capture, explicit source/sender/service rules determine policy and importance before optional inference. A triage result retains its rule/model version, route, supporting source spans, explanation, proposed category/service, extracted deadlines, and uncertainty. Failed or unavailable interpretation leaves a visible awaiting-classification item and a retrievable source. Low importance suppresses an inbox card only; it never removes archive evidence.

The owner can set importance, correct a service/category, and choose **Always surface this** or **Less important**. A one-item correction applies to that item. Saving a reusable rule presents its exact match scope and effect before confirmation. A correction does not automatically authorize a blocking rule, action, or broad learned policy. Conflicting rules use the baseline precedence and expose the winning rule.

### Grouping

Group first by a verified producer's stable concern key, then by valid References/In-Reply-To relationships, then by an owner-confirmed association. Service identity can suggest a merge but identical subjects or temporal proximity alone cannot merge concerns automatically. A sender-provided report key without verified report provenance is ordinary untrusted text. The owner can split or merge concerns while retaining every source and prior state change.

New evidence is meaningful when it changes the reported condition, importance, due date, requested decision, or operation outcome. Meaningful evidence marks a concern changed and reopens a resolved concern. Repeated identical evidence updates receipt history without repeatedly reopening or alerting. An active snooze remains in force unless rule-confirmed critical evidence escalates; the owner can inspect new evidence in the snoozed view.

### State model

| State/control | Observable behavior |
| --- | --- |
| Open | An unresolved concern that is eligible for the main actionable backlog. |
| Waiting | Unresolved work awaiting a named person, service, or event; preserve the reason and optional follow-up date. |
| Snoozed | Hide from ordinary actionable ordering until the chosen time; retain unresolved status and evidence. |
| Resolved | Work is marked done; keep history and sources; meaningful new evidence can reopen it. |
| Read | Opening detail records that the current source/revision was viewed; it does not resolve or acknowledge the concern. |
| Acknowledge | Records that the owner reviewed the current concern revision; later meaningful evidence is unacknowledged. |
| Done/Reopen | Changes concern resolution only; it is not a provider action or source deletion. |
| Snooze | Choose a time in the configured timezone; store an unambiguous instant and the selection timezone. |
| Wait | Choose the awaited party/event and optional follow-up time; due follow-up returns the concern to Open. |
| Due date | Preserve explicit owner date/time, or model-proposed wording and uncertainty; ambiguous dates require owner confirmation before timed reminders. |
| Undo | Revert a supported routine edit only while its expected revision still matches; otherwise explain the conflict and reload. It never undoes an external effect. |

Routine state changes return an authoritative revision and receipt. Buttons are disabled while the same edit is pending, but lost-response recovery uses the original identity. Offline optimistic changes are visibly pending. A later conflict never overwrites a newer server decision silently.

### Briefing and meaningful review

Generate a deterministic set of new, changed, overdue, and unresolved concerns since the last meaningful review; optional inference summarizes that set with citations. A model outage leaves a readable ordered list. Unchanged acknowledged information is not labeled new. A meaningful review is an explicit acknowledgment, Done/Reopen/Snooze/Wait action, due-date edit, or **Mark briefing reviewed**. Marking a briefing reviewed advances only through its displayed snapshot, never through unseen later changes. Opening the app, reading a card, background sync, push delivery, and model completion do not advance the inactivity timestamp. Server receipt time is authoritative when offline review finally synchronizes.

<a id="inference"></a>
## 6. Hybrid inference and cost controls

**Requirements:** FR-021–024. **Phase:** P1. **Acceptance:** AT-07, AT-16–17.

Before every inference request, evaluate the baseline precedence, selected task/sensitivity policy, device readiness, existing task claim, source availability, and cost allowance. Rules can complete a task without invoking a model. Auto mode prefers eligible foreground local work for bounded text; background work uses allowed cloud triage unless a ready device has already claimed it. The shared result schema and validation are the same for both routes.

**Local-only** means no source content or prompt is sent to cloud inference. It permits eligible device OCR and can wait for a ready phone; it cannot quietly fall back. **No AI** (`no_ai`) skips both inference routes and OCR while retaining deterministic parsing, archive, search, controls, and rule-based alerts. An unavailable local model, battery/thermal restriction, missing model artifact, or expired device claim produces an explicit queued/unavailable reason. The owner can retry or change policy, but policy changes are not inferred from repeated failure. Protected report privacy floors cannot be lowered through these settings or quarantine release.

Cloud inference requires both request/token eligibility and monetary eligibility. Unknown/stale shared-account headroom or uncoordinated account writers prevent cloud dispatch under the default included-only allowance. Application counters cannot enforce a provider-wide cap on other applications. At a cap, stop automatic paid dispatch and use permitted local/queued behavior. Do not silently upgrade an account, buy capacity, switch providers, or truncate retained source content. Show counts, estimates, cap status, and route per task. Long evidence is packed under the selected model's token bounds as well as source chunk bounds; summarization never replaces complete archived text.

Model output that fails the versioned schema is not an action or a completed classification. Apply bounded retries to replay-safe computation and show failure after exhaustion. Duplicate/late results use task version and attempt fencing so a stale route cannot overwrite a correction or a more recent result.

<a id="chat"></a>
## 7. Contextual chat and evidence

**Requirements:** FR-025–028. **Phase:** P1. **Acceptance:** AT-08, AT-17.

The owner can start global Chat or a conversation scoped to an email, attachment, concern, or service. Display the scope before submission. Retrieve only currently authorized, retained, non-quarantined evidence in that scope and under its inference policy. P1 uses text retrieval; P3 may add semantic candidates without changing evidence authorization.

Answers cite stable source/chunk references with inspectable excerpts and attachment page references when available. Separate an observed report, inference, possible cause, and missing evidence. An unanswered question produces an explicit evidence gap rather than an invented current service state. Deleted sources become unavailable citations and are removed from future retrieval. Reports show their as-of time; a past success cannot prove present health.

Persist a client request ID, exact submitted content/scope, policy, and durable turn status before dispatch. On interruption, fetch the existing turn/receipt; resume or retry using the same identity when allowed. Changing content creates a new request. Backgrounding is not cancellation; explicit cancellation stops undispatched work and records when already-dispatched inference could still settle.

Emails, attachments, retrieved webpages, connector payloads, and model output are untrusted evidence. They cannot change policy, reveal credentials, register tools, grant consent, or bypass exact review. Action suggestions are closed typed proposals with validated targets and parameters. Unsupported requests remain explanation-only. The model has no general shell, arbitrary network-fetch capability, or provider credential access.

<a id="notifications"></a>
## 8. Notifications and pipeline health

**Requirements:** FR-029–032. **Phase:** P1. **Acceptance:** AT-09, AT-15.

Critical and inactivity notifications are separate policies. A rule-confirmed critical concern creates a durable critical notification intent immediately after the concern commit. AI-only critical interpretation appears in the urgent inbox but does not gain critical quiet-hour bypass. Critical notification identity is the concern plus `criticalSignalId`, which changes only for materially changed critical evidence or escalation. Read, acknowledgment, snooze, and ordinary revision changes do not produce another critical push. Due reminders use `dueRevision`, changed only for the due date or its relevant policy. A push never reruns the underlying action.

Inactivity requires elapsed time since meaningful review and an actionable backlog: eligible unsnoozed open concerns or waiting concerns whose follow-up is due. Purely routine, resolved, and actively snoozed concerns do not qualify. A successful review resets reminder eligibility; an empty backlog suppresses it. Deferred noncritical notifications are revalidated when quiet hours end rather than sent for already-resolved work. A changed due date or relevant due policy invalidates the old due reminder.

Use generic lock-screen text and opaque destination IDs. System-notification actions are limited to opening authenticated context or supported low-impact acknowledgment/snooze controls; they cannot approve external operations or disclose source content. On open, fetch current state and handle deleted, revoked, expired, or resolved destinations safely. Request notification permission in context, expose registration/permission status, and keep all app workflows functional if denied. FCM acceptance, phone receipt, and visible display remain distinct evidence; no exact delivery time is promised.

Health displays last accepted receipt, oldest incomplete capture/job, malformed mail, extraction/AI failures, quarantined count, retry exhaustion, sync age/pending edits, push registration/last delivery outcome, and expected-report freshness. Unknown/unobserved status is not green health. Offer readback, safe job retry, and diagnostics export only for their authorized scopes. Operational logs avoid message/attachment content and secrets.

<a id="sync"></a>
## 9. Synchronization and interruption

**Requirements:** FR-033. **Phase:** P1. **Acceptance:** AT-10, AT-15, AT-17.

The server is authoritative for sources, concern revisions, and operation receipts. The phone caches the baseline window plus pinned text and bounded downloaded attachments. Synced records carry a monotonic change cursor and deletion tombstones. A cursor older than the supported tombstone window triggers rebootstrap before further mutations. Cached records carry freshness and do not impersonate current provider status.

Routine offline edits are saved with their original payload, expected revision, and idempotency key. Reconnection refreshes authority and server state before submission. A conflict exposes the current value and the pending intent for owner reapplication; the app does not silently choose last-writer-wins. Queue ordering preserves dependencies such as creating a note before linking it. Process death retains pending work. Revocation blocks submission and purges accessible content when recognized.

Push is a synchronization hint, not the source of truth. Foreground refresh and scheduled reconciliation recover missed pushes. Downloads verify integrity before becoming complete; interrupted transfer preserves a recoverable state. Expired credentials, offline transport, storage pressure, and conflict appear distinctly. The phone never replays a consequential effect simply because connectivity returned.

<a id="actions"></a>
## 10. Reviews, receipts, and uncertain outcomes

**Requirements:** FR-034–035. **Phase:** P1 foundation, P3 external operations. **Acceptance:** AT-11, AT-17.

Routine inbox edits are reversible app state. Source deletion, outbound communication, infrastructure changes, spending, publication, and other consequential actions use an exact review. Preparing a review performs validation/read-only checks and displays target, parameters, effects, current resource revision, relevant evidence, and expiry. Fresh passkey verification, current authority, unchanged parameters, and current preconditions are checked at commit.

Use the durable progression **prepared → dispatching → succeeded / failed / uncertain**, with cancelled or expired preparation leaving the effect undispatched. A submission receipt proves durable intent, not completion. A successful outcome requires the adapter's authoritative response/readback contract; an asynchronous provider acceptance alone is insufficient. A failed outcome means proven rejection or no effect, and any replacement intent needs a fresh review. Provider-supported idempotency preserves the original operation identity; it does not authorize a changed replacement. An uncertain operation is neither success nor proven failure.

After response loss, reuse the operation identity for status readback. Never send a replacement action as a recovery guess. Provider readback can resolve an uncertain result; if it cannot, keep it uncertain and present operator instructions. Expired reviews, changed targets, revoked sessions, and stale revisions require a new preparation. Owner notes can document an investigation but cannot manufacture provider-confirmed success. Runbooks, recovery jobs, model suggestions, and push actions cannot bypass this flow.

<a id="operations"></a>
## 11. Whumpo operational views and report producers

**Requirements:** FR-036–044. **Phase:** P2; email remains the only external ingress. **Acceptance:** AT-12, AT-17.

### Service catalog and report identity

Maintain a small owner-edited catalog of services/projects/domains, aliases, dependency links, trusted destinations, expected reports, and sensitivity policy. Catalog edits are app configuration. Show the evidence supporting an inferred association and allow correction. Dependencies provide context; they do not prove a failure propagated.

Each separately implemented Whumpo publisher emits a versioned sanitized report by email to a dedicated registered alias. The agreed payload carries service/report identity, report kind, observed time, monotonic sequence, stable concern key where applicable, bounded facts, and approved evidence/recovery links. Verify the HMAC over the exact signed payload bytes and require its key, service ID, and receiving alias to match the configured service before accepting trusted operational state. Preserve transport authentication as evidence; an SPF/DKIM pass is not an additional requirement for an otherwise valid service signature. The [LLD](lld.md) defines the closed signed payload and producer onboarding.

The receiver preserves the source privately and creates a separate validated sanitized projection for operational views and eligible inference. The raw protected report never becomes model/OCR-eligible, including after owner release. Known protected aliases and recognized envelopes impose this floor before validation. Unknown versions, invalid signatures, mismatched identities, and forbidden/unknown sensitive fields remain restricted quarantine with a verification issue and no cloud fallback. Duplicate report identities do not create duplicate events; conflicting payloads are rejected as trusted state; out-of-order observations remain history without overwriting a newer sequence. Ordinary unrecognized email remains ordinary retained evidence and cannot inject trusted status or a grouping key. Producers must redact before sending: downstream storage cannot undo disclosure of private release archives, broad database output, customer statements, or credentials.

### Operational behavior

| View | Behavior and allowed next steps |
| --- | --- |
| Maintenance/recovery | Display reported schedule state, backlog counts/ages, unresolved operation IDs, observation time, and report completeness. Distinguish unobserved, unavailable, skipped, running, overdue/stalled, failed, and completed pass. A completed pass does not prove every item succeeded. Acknowledge, snooze, inspect evidence, or open approved instructions; no blind retry. |
| Releases/incidents | Timeline uses report observation/event times, release ID, affected service versions, migration identity, command/readback state, and application-acceptance status where provided. Correlation highlights preceding changes as candidates, never as established causes. |
| Operator decisions | Show request ID, category, age, state, and a trusted destination/instruction. Exclude customer-authored statements and protected operator context from general archive/model reports. If no usable operator browser page exists, show instructions rather than inventing a URL. |
| Usage/cost | Preserve source kind: invoice, provider notice, usage counter, estimate, or unsettled operation. Display currency/unit/period/as-of time and missing evidence. Extracted invoices/cost entries remain proposed until owner confirmation. Never sum incompatible units or represent unsettled estimates as paid invoices. No automated Whumpo customer-limit or payment changes. |
| Readiness reminders | Track renewal dates, connection/reconnection needs, incomplete credential revocation, and backup verification age. A stored source-backup receipt is historical evidence; only the relevant verified restore evidence supports recoverability. Due dates from vague text require confirmation. |
| Operational memory | After resolution, propose a note containing the event, established evidence, successful/uncertain action receipts, and reusable steps. Owner review publishes the note/runbook. Later retrieval cites it and checks current context; it cannot replay an old operation automatically. |

### Missing reports and recovery

An expected heartbeat is configured explicitly with producer, cadence, and grace window. After the grace window, create one missing-report concern with last-seen evidence; new qualifying reports resolve the missing-report condition while retaining history. Do not assume that every service emits a heartbeat or that silence means health. A report marked unavailable is distinct from no report. Producer-side configuration and scheduling are separate Whumpo work, and Adminifender displays that dependency until a verified report arrives.

The underlying [maintenance](/home/ender/Documents/TellUmo/docs/maintenance.md), [release](/home/ender/Documents/TellUmo/docs/releases.md), [usage-review](/home/ender/Documents/TellUmo/docs/usage-review-requests.md), and [backup](/home/ender/Documents/TellUmo/docs/integrations/github-backups.md) contracts are contextual evidence. Adminifender is not authorized to reinterpret their uncertain outcomes as permission for another provider operation.

<a id="connectors"></a>
## 12. Additional ingest and direct integrations

**Requirements:** FR-045–050. **Phase:** P3. **Acceptance:** AT-11, AT-13, AT-17.

All connectors are individually disabled initially. Activation names the source, resource allowlist, permission scope, retention/sensitivity policy, and ingestion/cost limits. Credentials stay on the backend except Android's OS-granted capture permission. Revocation stops new work, invalidates authority, and reconciles pending requests; existing retained evidence follows normal deletion policy. Disabling a connector is not evidence that provider-side revocation succeeded.

| Connector | Selected behavior |
| --- | --- |
| Direct reads | Allowlisted Cloudflare/Whumpo resources use read-only bounded adapters. Each refresh records observed time and source version. Failure keeps the previous snapshot visibly stale. No general proxy or arbitrary URL querying. |
| Signed webhooks | Authenticate signature, timestamp, event identity, configured event type, and payload bounds before admission. Durable accepted events share normal source/concern processing. Reject unauthorized replay without creating new concerns. |
| GitHub sync | Explicit repositories and supported events; persist a cursor, deduplicate provider identities, expose revoked/insufficient scope and rate limits. No automatic repository writes. |
| Android notifications | Require explicit notification-access permission and a per-package allowlist. Always exclude Adminifender and unselected packages; sensitive app content requires separate explicit acknowledgment. Default to title-only capture. Before retaining/uploading enabled body content, redact recognizable OTP/security-code patterns using the bounded LLD rule, without claiming universal secret detection. Never serialize/invoke notification actions or capture image attachments. Revocation stops capture and pending unsent uploads; expose capture-active state. |
| Share/file intake | Android preview shows text/file, destination/context, and policy before Submit. Preserve original bytes after authorization. Sharing a URL stores it as evidence; it does not fetch it automatically. Interrupted uploads retain identity and integrity state. |
| Outbound replies | Draft from evidence, then show verified sending identity, all recipients, subject/body, attachments, and thread relation. Editing invalidates the old review. Commit through the configured outbound service, retain durable send identity/receipt, and show uncertain delivery separately from provider acceptance. Receiving mail does not establish outbound readiness. |
| Infrastructure/Whumpo actions | Closed operation types for admitted deployments/rollback, DNS edits, supported operator responses, and recovery actions. Each supplies exact target/effect, current-state preconditions, authorized credentials, status readback, and a recovery contract. No generic shell commands or automatic approval through a runbook. |

An integration may be visible as configured-but-not-ready until its account entitlements and acceptance evidence pass. Unsupported provider operations remain disabled with a precise explanation. The accepted P3 design does not grant every future operation type authority by default.

<a id="advanced"></a>
## 13. Semantic retrieval, learning, and forecasts

**Requirements:** FR-051–053. **Phase:** P3. **Acceptance:** AT-14, AT-16–17.

Semantic retrieval indexes eligible derived chunks and combines candidate results with explicit filters. Authorization, deletion, quarantine, and sensitivity checks run against authoritative records before returning evidence. Stale vectors cannot resurrect deleted content. Embeddings are rebuildable derived data, not the retained source. If unavailable or capped, ordinary text search still works.

Learned triage analyzes reviewed corrections to propose a bounded reusable rule. Present matched examples, expected scope, conflicts, and confidence/uncertainty before owner approval. No automatic promotion of a model guess into a block, critical-alert bypass, privacy-policy change, or external action. Store the accepted rule and its provenance so the owner can disable it and inspect subsequent decisions.

Forecasts use timestamped compatible evidence to estimate backlog/cost/renewal risk. Label input period, missing data, method, horizon, and uncertainty. Sparse or incomparable data yields insufficient evidence rather than an invented trend. An estimate can suggest a concern or planning step, but cannot change budgets, payment state, provider limits, or critical authority. Validate forecast usefulness on historical examples before enabling automatic forecast concerns.

<a id="settings"></a>
## 14. Settings, policy visibility, and defaults

**Requirements:** FR-054. **Phase:** All. **Acceptance:** AT-01, AT-07, AT-09, AT-12–17.

Settings exposes identity/recovery/devices; blocks and trusted hints; quarantine; inference task/sensitivity policies, local model readiness and cloud caps; notification permission, timezone, quiet hours and reminders; cache/storage/export; service catalog/report expectations; and enabled connectors. Each policy shows its current value, effect, provenance, and relevant usage/failure status. The baseline supplies the selected initial defaults.

Policy evaluation is explicit: revoked authority, deletion/privacy, manual blocks, quarantine, local-only/no-AI (including OCR), rules, eligibility/cost, then model interpretation. Protected report privacy floors remain binding after release, with only validated sanitized projections eligible for inference. A trusted sender cannot bypass a block; a newly increased cap cannot revive expired authority; a route change cannot resend already-uncertain external work. New policy versions affect undispatched work at claim time, and dispatched results are revalidated before publication.

Changing a setting never claims an external setup succeeded without readback. A successful configuration save is separate from notification permission, provider entitlement, report arrival, local model readiness, or device acceptance. Exported diagnostics use content-free operational categories; a personal support artifact with source content requires deliberate selection and review.
