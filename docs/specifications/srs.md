# Software requirements specification

Version 1.0 — October 1, 2026. Status: accepted requirements and selected design; implementation, provisioning and device acceptance have not occurred. This is the normative requirements source. [Shared defaults](baseline.md), [product scope](prd.md), [functional workflows](frd.md), [detailed contracts](lld.md).

## Authority and scope

Every FR/NFR below is accepted. A later phase is scheduled scope, not an optional suggestion. P1 and P2 permit only email external ingestion; P3 connectors are individually enabled only after their acceptance cases pass. All phases serve one owner. No audio/video understanding, public signup, commercial multi-tenancy or automatic consequential effects are required.

Provenance codes: **O** original owner notes; **A** owner acceptance of all suggestions; **D** subsequent owner decisions (S24+, Play, hybrid, retention, identity, attachments and spam); **E** necessary engineering defaults/derived reliability requirements. The [consolidated notes](../product-notes.md) preserve O/A/D history. Defaults can change by a versioned specification/ADR update; platform verification does not silently alter requirements.

## Functional requirements

“Shall” is normative. Acceptance descriptions below are contract expectations, not claims of tests already run.

| ID | Phase | The system shall | Acceptance |
| --- | --- | --- | --- |
| FR-001 | P1 | Authenticate the sole owner with a passkey, revoke individual devices, and recover with an independent offline recovery secret. | Revoked sessions fail immediately; used recovery secrets cannot be replayed. |
| FR-002 | P1 | Provide a native Compose inbox, archive, chat and settings with cached reads and visible freshness. | Cached inbox opens without network or AI; no remote success is fabricated. |
| FR-003 | P1 | Receive email through Cloudflare routing and durably stage each nonblocked delivery before reporting ingest success. | A staged delivery can be recovered after processing fails; unsupported external sources are disabled in P1/P2. |
| FR-004 | P1 | Apply owner-managed exact-address or exact-domain block rules before storing body/attachments or invoking AI. | Blocked test mail produces only bounded aggregate rejection telemetry; block/unblock requires an authenticated explicit owner mutation. |
| FR-005 | P1 | Quarantine suspected spam without automatic rejection; preserve accepted content and allow review, release and correction. | Suspicion alone never deletes mail; quarantined sources create no normal inbox item, push, embeddings, downstream AI or OCR. |
| FR-006 | P1 | Decode MIME and normalize all meaningful text, link targets and substantive multipart content without requiring retained presentation. | HTML-only, Unicode, multipart and materially differing alternative-body fixtures preserve their useful text; failure remains visible with staging intact. |
| FR-007 | P1 | Retain full normalized text and provenance for every accepted email regardless of triage outcome until explicit deletion. | Suppressed, unresolved and quarantined messages remain searchable in their permitted view; summaries never replace source text. |
| FR-008 | P1 | Retain original attachment bytes, names, media types, sizes and hashes, including substantive inline files. | Round-trip hashes match for extracted, unextractable and unsupported files. |
| FR-009 | P1 | Extract bounded document text and expose stored, queued, extracting, ready, unsupported, failed and deferred states. | Supported text/CSV/JSON/Markdown/HTML/DOCX/PDF fixtures index text; encrypted/unsupported files stay downloadable with truthful status. |
| FR-010 | P1 | Perform on-device OCR for supported images and scanned PDF pages, preserving source/page references. | Latin-text JPEG/PNG/WebP and scanned PDF fixtures yield searchable text; limits/deferred device work are visible. |
| FR-011 | P1 | Delete archive content only after exact review, and remove derivatives, cache copies and search results. | Deleted sources disappear immediately from server/online reads; offline devices purge at next authenticated sync; primary cleanup finishes within baseline bounds and backup restore reapplies deletion records. |
| FR-012 | P1 | Export normalized records, original files and a checksum manifest without AI. | Export reopens independently, hashes match and includes a consistent snapshot boundary and unsupported-file status. |
| FR-013 | P1 | Search by text, sender, dates, project/service and attachment name/text; provide explicit archive and quarantine filters. | Exact fixture queries work during cloud AI failure; offline search explicitly covers cached content only. |
| FR-014 | P1 | Apply rules then permitted inference to categorize, summarize, prioritize and extract deadlines/services/actions. | Rules are deterministic; invalid or unavailable AI leaves a visible awaiting/failed state rather than hiding a source. |
| FR-015 | P1 | Explain priority with cited evidence and rule/model provenance, and save explicit owner corrections. | Always-surface/less-important overrides take effect on future matching sources and never silently rewrite archived text. |
| FR-016 | P1 | Group related evidence into concerns with inspectable membership and owner split/merge controls. | Verified concern keys and thread references group; subject equality alone does not; new material evidence reopens resolved concerns. |
| FR-017 | P1 | Keep importance, read, acknowledged and concern resolution as separate state. | Opening a card does not resolve or acknowledge it; waiting/snoozed concerns remain unresolved. |
| FR-018 | P1 | Offer Acknowledge, Done, Snooze, Open source, Ask agent and Show evidence where applicable. | Controls function without model availability; external operations use separate reviewed actions. |
| FR-019 | P1 | Support due dates, waiting state, snooze, undo dismissal and deadline resurfacing. | Preserve inferred date wording/timezone; active snoozes survive ordinary updates but rule-confirmed critical updates resurface. |
| FR-020 | P1 | Present a since-last-review briefing of new, changed, overdue and unresolved concerns. | Previously acknowledged unchanged entries are not presented as new; explicit Mark reviewed advances review time. |
| FR-021 | P1 | Select local or Cloudflare inference automatically under task, sensitivity, readiness and cost policies. | Every attempt records decision reason, model, policy revision and usage; duplicate agents cannot both commit authoritative output. |
| FR-022 | P1 | Honor local-only and no-AI restrictions for source content, derived text, chat context and embeddings. | Restricted source bytes never reach a cloud model; no-AI also disables OCR; absence of a capable phone remains queued and visible. |
| FR-023 | P1 | Run bounded local inference on the S24+ with readiness, memory, battery, thermal and concurrency controls. | Device acceptance measures latency/memory; unavailable local model causes a permitted route change or visible deferral. |
| FR-024 | P1 | Limit cloud inference by deterministic request/token allowances and owner-configured paid ceilings. | Reserve worst-case work before dispatch; exhausted/unknown allowances defer or route locally without losing archived email. |
| FR-025 | P1 | Provide global and concern/source/service-scoped chat for retrieval, explanations, summaries and action preparation. | Selected scope is visible; archive queries and bounded model input obey the same source access/policy filters. |
| FR-026 | P1 | Link factual answers to retained source excerpts and distinguish evidence, stale status, uncertainty and hypotheses. | Nonexistent or out-of-scope citation IDs are rejected; no unsupported service-health or causality claim is shown as established fact. |
| FR-027 | P1 | Persist chat draft/request identity and recover committed results after interruption without blind inference replay. | Process death/lost HTTP response recovers the same request; altered payload with the same key conflicts. |
| FR-028 | P1 | Treat incoming text/files as untrusted evidence and restrict model tools to validated application operations. | Prompt-injection fixtures cannot execute content, fetch arbitrary URLs, reveal secrets or acquire action authority. |
| FR-029 | P1 | Push rule-confirmed critical events independently of phone-side model availability. | Server rule produces one critical event per material critical signal ID; AI-only priority never bypasses quiet hours as confirmed critical. |
| FR-030 | P1 | Send bounded inactivity reminders only for eligible unresolved work after meaningful review inactivity. | App launch, sync and push receipt do not reset review time; empty/snoozed-only backlog does not trigger reminders. |
| FR-031 | P1 | Respect configurable channels, quiet hours/cooldowns and opaque private push payloads. | Denied permission, invalid token and delayed push preserve inbox state; push receipt cannot execute an operation. |
| FR-032 | P1 | Show capture, parsing, extraction, AI, sync, notification and oldest-backlog health separately. | Missing/unavailable observations differ from success and stale telemetry is labeled. |
| FR-033 | P1 | Synchronize server changes, offline reversible edits, tombstones and conflicts across revocable devices. | Old cursor rebootstrap and out-of-order responses cannot resurrect deleted content or overwrite newer revisions. |
| FR-034 | P1 | Review exact target, parameters, effects and current revision before consequential changes. | Expired/stale/altered reviews and duplicate confirmations fail safely or return the original receipt. |
| FR-035 | P1 | Record external intent before dispatch and preserve uncertain outcomes for readback/reconciliation. | A lost response or expired dispatched lease never causes automatic redeployment, resend, payment or other duplicate effect. |
| FR-036 | P2 | Maintain owner-managed service/project/domain aliases and directed dependency relationships. | Emails map to known IDs with evidence; unknown mappings remain unassigned; graph changes are explicit. |
| FR-037 | P2 | Surface Whumpo maintenance, backlog and uncertain-operation reports as actionable concerns. | Preserve skipped/unobserved/unavailable/failed/uncertain distinctions and link evidence/runbooks without generic unsafe retry. |
| FR-038 | P2 | Relate release summaries and later incidents by time/service while preserving deployment-versus-acceptance states. | Timeline shows evidence timestamps and never treats correlation or deployment readback as a verified diagnosis/customer acceptance. |
| FR-039 | P2 | Surface operator-decision metadata without importing Whumpo support statements excluded from AI context. | Signed report schema rejects private statement fields; support action responses require the separate P3 protected operator contract. |
| FR-040 | P2 | Track source-linked invoices, estimates, internal usage, budgets and unresolved costs as distinct types. | An extracted invoice is proposed until owner-confirmed and never automatically changes payments/customer enforcement. |
| FR-041 | P2 | Track renewal, reconnect, revocation, backup-verification and recovery-readiness obligations. | Historical backup receipts do not imply a currently restorable system; reminders cite dated evidence. |
| FR-042 | P2 | Store owner-reviewed incident notes and response steps linked to evidence and confirmed outcomes. | Unreviewed model notes cannot become an authoritative runbook or trigger automatic consequential action. |
| FR-043 | P2 | Receive authenticated minimal Whumpo report emails through new separately implemented producers. | Invalid signatures/replayed event IDs cannot update trusted operational state; ordinary email evidence remains retained. |
| FR-044 | P2 | Detect missing expected emailed heartbeats using per-source schedules and grace periods. | A missing heartbeat produces one evolving stale-source concern; late/replayed heartbeats cannot erase newer failure evidence. |
| FR-045 | P3 | Support opt-in scoped direct Cloudflare and Whumpo reads with freshness and connector health. | Only registered read adapters/targets run; revoked/missing credentials yield unavailable, not healthy. |
| FR-046 | P3 | Support authenticated webhook ingestion and incremental GitHub synchronization. | Signatures, replay IDs, bounded pagination and cursor recovery are verified; no P1/P2 endpoint accepts these sources. |
| FR-047 | P3 | Capture notifications only from owner-selected Android apps after explicit system permission. | Deselected apps, Adminifender itself and unselected sensitive content are excluded; permission revocation stops capture. |
| FR-048 | P3 | Accept explicit Android shares/files with a preview and owner submission step. | Share intent alone never uploads, fetches a link, sends chat or executes an action; retry uses same content identity. |
| FR-049 | P3 | Prepare and send reviewed outbound email replies with recipient/body/attachments bound to review. | Sender capability/domain must be configured; unknown provider outcome is retained and not automatically resent. |
| FR-050 | P3 | Offer bounded reviewed DNS, release/recovery and Whumpo operator actions through scoped adapters. | Adapter preconditions and recent authentication are checked at execution; no arbitrary shell, generic cloud proxy or model-supplied authority. |
| FR-051 | P3 | Add semantic retrieval alongside exact search without replacing source evidence. | Deleted/restricted/quarantined sources are excluded after vector lookup; local-only sources never enter cloud embeddings. |
| FR-052 | P3 | Learn candidate triage rules from owner corrections and evaluate them before activation. | Candidate rules require owner review, retain versioned metrics, and cannot grant consequential action authority. |
| FR-053 | P3 | Forecast usage/cost and suggest reconciliation from dated evidence with uncertainty. | Insufficient history is labeled; forecasts never replace invoices or trigger automatic spending/enforcement. |
| FR-054 | P1 | Expose inference, notifications, storage/cache, block rules, devices, exports and processing settings with effective-policy explanations. | Invalid combinations fail with actionable errors; changes are revisioned and appropriate settings require recent authentication/review. |

## Nonfunctional requirements

Apply from P1 onward unless the affected connector is P3. Numerical values are initial engineering acceptance targets, not measured results or provider guarantees.

| ID | Area | Requirement | Acceptance |
| --- | --- | --- | --- |
| NFR-001 | Durability | Accepted staged content survives consumer retries and process failure; each source has a durable recoverable status. | Injected failures at every handoff lose zero staged fixture sources and duplicate no committed effects. |
| NFR-002 | Confidentiality | Apply owner isolation, private R2 access, protected device storage and no secrets/content in telemetry; no end-to-end-encryption claim. | Cross-session/revoked-device, signed-URL leakage, backup-exclusion and prompt-injection fixtures pass. |
| NFR-003 | Performance | On the target phone, cached inbox p95 renders within 1 second for 1,000 cached concerns; bounded text search p95 under 2 seconds for 10,000 sources in the reference test. | Record device OS/SoC, build and dataset; network inference latency is measured separately and never claimed guaranteed. |
| NFR-004 | Recovery | After simulated Worker/device failure, reconcile internal pending jobs within 5 minutes of service restoration; aim archive RPO <=24h and recovery RTO <=4h. | Restore encrypted backup into isolated environment, reconcile deletions and verify random attachment hashes; targets unverified until drill. |
| NFR-005 | Cost bounds | Bound every parse, query, extraction, retrieval, inference and connector task; reserve inference allowance before dispatch. | Stress fixtures terminate/defer within selected limits; scenario arithmetic and shared-provider allowances are explicit. |
| NFR-006 | Accessibility | Support TalkBack, 200% font scaling, Material contrast, 48dp touch targets and no color-only state communication. | Physical-device checks exercise inbox, review, quarantine and recovery without clipped critical controls. |
| NFR-007 | Compatibility | Version APIs/events and use additive migrations; retain compatibility with current and previous released app protocol versions. | Upgrade/downgrade-protocol fixtures retain recovery access, safe reads and explicit upgrade-required errors. |
| NFR-008 | Observability | Record structured content-free latency, counts, errors, queue age and deployment identity with baseline retention. | Synthetic secret/body markers never appear in exported diagnostics; unavailable data never displays zero. |
| NFR-009 | Distribution | Use signed AAB private Play internal testing, separate environments, retained signing/recovery records and revocable backend access. | Install/update on S24+, prove association/sign-in/push, and exercise backend stop plus forward-fix rollout. |
| NFR-010 | Testability | Provide deterministic clock, fake model/provider adapters and sanitized fixture suites before activating integrations. | Behavior tests assert side effects/source retention, not just implementation-shaped mocks; per-phase evidence retained. |
| NFR-011 | Evidence honesty | Label model-derived, inferred, reported, configured, tested and deployed evidence separately. | Examples and screens cannot claim live freshness, complete recovery or measured quality without dated supporting evidence. |
| NFR-012 | Independent ownership | Keep Adminifender resources, sessions and permissions separate from Whumpo tenant/maintainer authority. | A valid Whumpo customer session cannot access Adminifender, and an Adminifender model cannot acquire arbitrary platform credentials. |

## Canonical traceability matrix

This is the only requirement-to-design/test matrix. FRD explains behavior; LLD defines implementable contracts; ADRs explain choices. P1/P2/P3 applicability is owned by the requirements above. All links target stable document anchors.

| Requirement | Phase | Source | Functional explanation | Detailed design | Decision | Acceptance group |
| --- | --- | --- | --- | --- | --- | --- |
| FR-001 | P1 | O,D | [Workflow](frd.md#identity) | [Contract](lld.md#identity) | [ADR](adr/0004-identity-and-actions.md) | [AT-01](#at-01) |
| FR-002 | P1 | O,A | [Workflow](frd.md#identity) | [Contract](lld.md#android) | [ADR](adr/0006-android-and-sync.md) | [AT-10](#at-10) |
| FR-003 | P1 | O | [Workflow](frd.md#intake) | [Contract](lld.md#ingestion) | [ADR](adr/0003-jobs-and-recovery.md) | [AT-02](#at-02) |
| FR-004 | P1 | D | [Workflow](frd.md#intake) | [Contract](lld.md#ingestion) | [ADR](adr/0002-storage-and-retention.md) | [AT-02](#at-02) |
| FR-005 | P1 | D | [Workflow](frd.md#intake) | [Contract](lld.md#ingestion) | [ADR](adr/0002-storage-and-retention.md) | [AT-02](#at-02) |
| FR-006 | P1 | O,A | [Workflow](frd.md#intake) | [Contract](lld.md#ingestion) | [ADR](adr/0002-storage-and-retention.md) | [AT-02](#at-02) |
| FR-007 | P1 | O | [Workflow](frd.md#archive) | [Contract](lld.md#storage) | [ADR](adr/0002-storage-and-retention.md) | [AT-03](#at-03) |
| FR-008 | P1 | O,A | [Workflow](frd.md#archive) | [Contract](lld.md#storage) | [ADR](adr/0002-storage-and-retention.md) | [AT-04](#at-04) |
| FR-009 | P1 | A,D | [Workflow](frd.md#extraction) | [Contract](lld.md#extraction) | [ADR](adr/0008-search-and-extraction.md) | [AT-04](#at-04) |
| FR-010 | P1 | A,D | [Workflow](frd.md#extraction) | [Contract](lld.md#extraction) | [ADR](adr/0008-search-and-extraction.md) | [AT-04](#at-04) |
| FR-011 | P1 | D,E | [Workflow](frd.md#archive) | [Contract](lld.md#deletion) | [ADR](adr/0002-storage-and-retention.md) | [AT-03](#at-03) |
| FR-012 | P1 | A | [Workflow](frd.md#archive) | [Contract](lld.md#exports) | [ADR](adr/0002-storage-and-retention.md) | [AT-03](#at-03) |
| FR-013 | P1 | A | [Workflow](frd.md#archive) | [Contract](lld.md#search) | [ADR](adr/0008-search-and-extraction.md) | [AT-03](#at-03) |
| FR-014 | P1 | O,A | [Workflow](frd.md#inbox) | [Contract](lld.md#triage) | [ADR](adr/0005-hybrid-inference.md) | [AT-05](#at-05) |
| FR-015 | P1 | A | [Workflow](frd.md#inbox) | [Contract](lld.md#triage) | [ADR](adr/0005-hybrid-inference.md) | [AT-05](#at-05) |
| FR-016 | P1 | A | [Workflow](frd.md#inbox) | [Contract](lld.md#concerns) | [ADR](adr/0006-android-and-sync.md) | [AT-06](#at-06) |
| FR-017 | P1 | A | [Workflow](frd.md#inbox) | [Contract](lld.md#concerns) | [ADR](adr/0006-android-and-sync.md) | [AT-06](#at-06) |
| FR-018 | P1 | O,A | [Workflow](frd.md#inbox) | [Contract](lld.md#concerns) | [ADR](adr/0004-identity-and-actions.md) | [AT-06](#at-06) |
| FR-019 | P1 | A | [Workflow](frd.md#inbox) | [Contract](lld.md#concerns) | [ADR](adr/0006-android-and-sync.md) | [AT-06](#at-06) |
| FR-020 | P1 | A | [Workflow](frd.md#inbox) | [Contract](lld.md#briefings) | [ADR](adr/0005-hybrid-inference.md) | [AT-06](#at-06) |
| FR-021 | P1 | D | [Workflow](frd.md#inference) | [Contract](lld.md#inference) | [ADR](adr/0005-hybrid-inference.md) | [AT-07](#at-07) |
| FR-022 | P1 | A,D | [Workflow](frd.md#inference) | [Contract](lld.md#inference) | [ADR](adr/0005-hybrid-inference.md) | [AT-07](#at-07) |
| FR-023 | P1 | O,A,D | [Workflow](frd.md#inference) | [Contract](lld.md#inference) | [ADR](adr/0005-hybrid-inference.md) | [AT-07](#at-07) |
| FR-024 | P1 | D,E | [Workflow](frd.md#inference) | [Contract](lld.md#inference) | [ADR](adr/0010-cost-and-observability.md) | [AT-07](#at-07) |
| FR-025 | P1 | O,A | [Workflow](frd.md#chat) | [Contract](lld.md#chat) | [ADR](adr/0005-hybrid-inference.md) | [AT-08](#at-08) |
| FR-026 | P1 | A | [Workflow](frd.md#chat) | [Contract](lld.md#chat) | [ADR](adr/0005-hybrid-inference.md) | [AT-08](#at-08) |
| FR-027 | P1 | A | [Workflow](frd.md#chat) | [Contract](lld.md#jobs) | [ADR](adr/0003-jobs-and-recovery.md) | [AT-08](#at-08) |
| FR-028 | P1 | A,E | [Workflow](frd.md#chat) | [Contract](lld.md#security) | [ADR](adr/0004-identity-and-actions.md) | [AT-17](#at-17) |
| FR-029 | P1 | O,A,E | [Workflow](frd.md#notifications) | [Contract](lld.md#notifications) | [ADR](adr/0007-notifications.md) | [AT-09](#at-09) |
| FR-030 | P1 | O,A | [Workflow](frd.md#notifications) | [Contract](lld.md#notifications) | [ADR](adr/0007-notifications.md) | [AT-09](#at-09) |
| FR-031 | P1 | A | [Workflow](frd.md#notifications) | [Contract](lld.md#notifications) | [ADR](adr/0007-notifications.md) | [AT-09](#at-09) |
| FR-032 | P1 | A | [Workflow](frd.md#settings) | [Contract](lld.md#health) | [ADR](adr/0010-cost-and-observability.md) | [AT-12](#at-12) |
| FR-033 | P1 | A | [Workflow](frd.md#sync) | [Contract](lld.md#sync) | [ADR](adr/0006-android-and-sync.md) | [AT-10](#at-10) |
| FR-034 | P1 | A,D | [Workflow](frd.md#actions) | [Contract](lld.md#actions) | [ADR](adr/0004-identity-and-actions.md) | [AT-11](#at-11) |
| FR-035 | P1 | A | [Workflow](frd.md#actions) | [Contract](lld.md#actions) | [ADR](adr/0003-jobs-and-recovery.md) | [AT-11](#at-11) |
| FR-036 | P2 | A | [Workflow](frd.md#operations) | [Contract](lld.md#operations) | [ADR](adr/0009-phased-integrations.md) | [AT-12](#at-12) |
| FR-037 | P2 | A | [Workflow](frd.md#operations) | [Contract](lld.md#operations) | [ADR](adr/0009-phased-integrations.md) | [AT-12](#at-12) |
| FR-038 | P2 | A | [Workflow](frd.md#operations) | [Contract](lld.md#operations) | [ADR](adr/0009-phased-integrations.md) | [AT-12](#at-12) |
| FR-039 | P2 | A | [Workflow](frd.md#operations) | [Contract](lld.md#reports) | [ADR](adr/0009-phased-integrations.md) | [AT-17](#at-17) |
| FR-040 | P2 | A | [Workflow](frd.md#operations) | [Contract](lld.md#operations) | [ADR](adr/0010-cost-and-observability.md) | [AT-12](#at-12) |
| FR-041 | P2 | A | [Workflow](frd.md#operations) | [Contract](lld.md#operations) | [ADR](adr/0009-phased-integrations.md) | [AT-12](#at-12) |
| FR-042 | P2 | A | [Workflow](frd.md#operations) | [Contract](lld.md#operations) | [ADR](adr/0009-phased-integrations.md) | [AT-12](#at-12) |
| FR-043 | P2 | A,E | [Workflow](frd.md#operations) | [Contract](lld.md#reports) | [ADR](adr/0009-phased-integrations.md) | [AT-12](#at-12) |
| FR-044 | P2 | A | [Workflow](frd.md#operations) | [Contract](lld.md#reports) | [ADR](adr/0009-phased-integrations.md) | [AT-12](#at-12) |
| FR-045 | P3 | A | [Workflow](frd.md#connectors) | [Contract](lld.md#connectors) | [ADR](adr/0009-phased-integrations.md) | [AT-13](#at-13) |
| FR-046 | P3 | A | [Workflow](frd.md#connectors) | [Contract](lld.md#connectors) | [ADR](adr/0009-phased-integrations.md) | [AT-13](#at-13) |
| FR-047 | P3 | A | [Workflow](frd.md#connectors) | [Contract](lld.md#connectors) | [ADR](adr/0009-phased-integrations.md) | [AT-13](#at-13) |
| FR-048 | P3 | A | [Workflow](frd.md#connectors) | [Contract](lld.md#connectors) | [ADR](adr/0009-phased-integrations.md) | [AT-13](#at-13) |
| FR-049 | P3 | A,D | [Workflow](frd.md#actions) | [Contract](lld.md#connectors) | [ADR](adr/0004-identity-and-actions.md) | [AT-11](#at-11) |
| FR-050 | P3 | A,D | [Workflow](frd.md#actions) | [Contract](lld.md#connectors) | [ADR](adr/0009-phased-integrations.md) | [AT-11](#at-11) |
| FR-051 | P3 | A | [Workflow](frd.md#advanced) | [Contract](lld.md#search) | [ADR](adr/0008-search-and-extraction.md) | [AT-14](#at-14) |
| FR-052 | P3 | A | [Workflow](frd.md#advanced) | [Contract](lld.md#advanced) | [ADR](adr/0005-hybrid-inference.md) | [AT-14](#at-14) |
| FR-053 | P3 | A | [Workflow](frd.md#advanced) | [Contract](lld.md#advanced) | [ADR](adr/0010-cost-and-observability.md) | [AT-14](#at-14) |
| FR-054 | P1 | A,D | [Workflow](frd.md#settings) | [Contract](lld.md#settings) | [ADR](adr/0010-cost-and-observability.md) | [AT-17](#at-17) |
| NFR-001 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0003-jobs-and-recovery.md) | [AT-02](#at-02), [AT-10](#at-10) |
| NFR-002 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0004-identity-and-actions.md) | [AT-01](#at-01), [AT-17](#at-17) |
| NFR-003 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0006-android-and-sync.md) | [AT-10](#at-10), [AT-16](#at-16) |
| NFR-004 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0003-jobs-and-recovery.md) | [AT-15](#at-15) |
| NFR-005 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0010-cost-and-observability.md) | [AT-07](#at-07), [AT-16](#at-16) |
| NFR-006 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0006-android-and-sync.md) | [AT-15](#at-15) |
| NFR-007 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0006-android-and-sync.md) | [AT-10](#at-10), [AT-15](#at-15) |
| NFR-008 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0010-cost-and-observability.md) | [AT-12](#at-12), [AT-17](#at-17) |
| NFR-009 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0006-android-and-sync.md) | [AT-15](#at-15) |
| NFR-010 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0003-jobs-and-recovery.md) | [AT-02](#at-02), [AT-11](#at-11), [AT-13](#at-13) |
| NFR-011 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0009-phased-integrations.md) | [AT-08](#at-08), [AT-12](#at-12) |
| NFR-012 | P1–P3 | E,D | [Quality](tdd.md) | [Contracts](lld.md) | [ADR](adr/0001-personal-boundaries.md) | [AT-13](#at-13), [AT-17](#at-17) |

## Acceptance scenarios

Use credential-free fixtures locally, then explicit isolated test integrations, then the physical S24+. No real customer statements belong in model tests. Each group below must record build, fixture/corpus digest, environment, outcome and unverified boundaries. Subsequent app implementation supplies executable tests; this documentation task does not claim application test results.

<a id="at-01"></a>
### AT-01 Identity and recovery

Register only through single-use owner bootstrap; reject reused/expired challenges, wrong RP/origin and wrong app association. Test refresh rotation and reuse, revoked-device denial, offline cached access policy, one-time recovery rotation and interrupted enrollment. Losing the phone must not require reading this app's email to recover. A replacement passkey does not revive old sessions.

<a id="at-02"></a>
### AT-02 Email and quarantine

Fixtures: plain text, HTML-only, Unicode, mismatching text/HTML, duplicate provider delivery, missing/duplicated Message-ID, invalid MIME and provider-size rejection. Simulate R2 success with D1 failure and queue publication failure; reconcile without duplicate archive entries. Block rules reject before body storage; automatic suspicion preserves full sources quietly. Pause/fail admission and report validation while consumers run; no downstream model/extraction job may become eligible before final admission/privacy publication. Release reuses source identity and starts pending jobs once. Sender spoofing cannot bypass blocks or establish signed report trust.

<a id="at-03"></a>
### AT-03 Archive lifecycle

Search low-priority and quarantined mail in correct views; export mixed text/attachment records at a snapshot boundary; verify checksums. Review deletion, immediately hide it, fail/retry object cleanup and reapply the independently persisted deletion ledger during restore. Fail the ledger-head write before deletion confirmation; the operation must stay pending. An unavailable or unverifiable current ledger blocks restored reads, even when an older backup contains a valid historical copy. Deleting a source removes its own object paths; another source with identical bytes remains intact because P1 does not deduplicate files across sources. Expired exports cannot remain anonymously accessible.

<a id="at-04"></a>
### AT-04 Extraction and OCR

Include scanned/mixed PDF, DOCX zip bomb, encrypted PDF, oversized page/image, malformed office file, missing local OCR model and unsupported audio attachment. Verify originals unchanged; quarantine never starts extraction; bounded work resumes by page/chunk after phone loss. Reject oversized batches and stale lease results; unpublished extraction generations never become searchable. No-AI sources permit deterministic parsing but never OCR. Text extraction and OCR accuracy are separately measured on a manually transcribed 30-document corpus; text-evidence links retain page/offset. Initial OCR acceptance is >=95% character accuracy on clean printed Latin fixtures, not a claim for handwriting or every language.

<a id="at-05"></a>
### AT-05 Triage quality

Maintain a versioned 200-message synthetic/owner-sanitized corpus with spam, routine, important and rule-critical labels. Require 100% recall of explicit rule-critical cases and >=95% recall of labeled important messages before enabling automatic suppression; otherwise keep uncertain items in review. Validate schema rejection, learned-versus-explicit rule precedence, changed rules, source evidence and reproducibility. Benchmark local and cloud routes independently; model confidence is not calibrated probability.

<a id="at-06"></a>
### AT-06 Attention workflow

Verify unrelated same-subject messages stay separate; reply chains and verified concern keys group; manual merge/split preserves evidence. New material evidence reopens resolved concerns, respects ordinary snooze and resurfaces rule-critical changes. Test waiting/deadline timezone ambiguity, undo within its revision boundary, a briefing across two reviews and clock/DST changes. Reading/opening never implies resolution.

<a id="at-07"></a>
### AT-07 Hybrid inference and limits

Exercise foreground-ready local, phone unavailable, low battery, severe thermal state, failed model download, local-only, no-AI and cloud unavailable. Enforce task lease/fencing against late duplicate results. Exhaust daily/request/token/monetary allowance; concurrent requests cannot overspend their application reservation. A provider timeout records uncertain usage and is not silently billed again. Verify no local-only bytes in cloud requests or embedding jobs.

<a id="at-08"></a>
### AT-08 Grounded chat

Answer fixture questions using exact source IDs and excerpts; reject fabricated citations and out-of-scope/quarantined/deleted results. Separate temporal association from diagnosis. Kill the phone and lose the post response; read back the existing turn instead of redispatching. Malformed tool proposals never become actions. State the absence of evidence instead of inventing a current service state.

<a id="at-09"></a>
### AT-09 Notifications

Test rule-critical while phone offline, AI-only critical suggestion, denied permission, expired/rotated FCM token, Doze, delayed/duplicate/out-of-order pushes, quiet hours and DST. Verify inactivity with eligible/open versus empty/snoozed-only backlog, once-per-cooldown and material-signal behavior and last meaningful review updates. Mark-read, acknowledge and ordinary snooze edits must not mint another critical signal or due-date revision; a new material critical escalation can. Push payload contains opaque identity/generic copy only. A provider send receipt is not proof that the owner saw it.

<a id="at-10"></a>
### AT-10 Offline synchronization

Inject airplane mode, process death after enqueue, duplicate mutation, conflicting expected revision, logout while work is pending, two devices, expired cursor and deletion during bootstrap. Server changes and cursor ordering are committed atomically. Stale offline state cannot overwrite a newer decision; the UI exposes conflict and offers explicit reapplication. Clearing session/private cache does not delete server archive.

<a id="at-11"></a>
### AT-11 Reviewed effects

Alter review parameters, expire a review, revoke a connector, race confirmations and change the target revision. In each case only an authorized current intent executes. Simulate crash before dispatch, after dispatch and after receipt persistence. Readback/unknown outcomes never trigger blind retry. SMTP/send/DNS/deployment/operator fixtures prove exact recipient/target and receipt identity, including no provider-supported idempotency.

<a id="at-12"></a>
### AT-12 Operations and reports

Verify all report kinds, signature validation, revoked key, duplicate event, old sequence, missing heartbeat, late recovery event and contradictory evidence. Show report freshness and coverage separately from provider health; a partial snapshot cannot clear absent earlier failures, and one complete observation cannot imply complete service coverage. Correlate releases without asserting causality; invoice estimates differ from verified costs; old backup success differs from restore readiness. Reports never confer action authority. Runbooks require explicit owner approval.

<a id="at-13"></a>
### AT-13 P3 connectors

Each connector has a disabled-state test before activation. Exercise webhook forgery/replay, GitHub incremental pagination/rate limits, provider credential revocation, unsupported targets and secret redaction. Android notification listener captures only allowlisted packages and stops on permission removal; incoming shares remain local until confirmed. No arbitrary link fetching or local-network destination access occurs.

<a id="at-14"></a>
### AT-14 Advanced intelligence

Compare semantic+exact retrieval against exact-only on 50 labeled queries: semantic mode must improve relevant-result coverage without authorization regressions before activation. Delete a source during vector ingestion and prove post-filter denial. Candidate learned rules require explicit approval and cannot lower rule-critical recall. Forecasts show sample count, period, method and missing data; no financial action follows automatically.

<a id="at-15"></a>
### AT-15 Device delivery and recovery

Install/update signed internal-test AAB on actual S24+; record Android version/SoC, signing certificate, API/backend/model versions, passkey association, TalkBack/large-text results and FCM test. Exercise database upgrades, older app compatibility, revoked session, backend stop, higher-version forward fix and independent restore drill. Discard restored credentials and push registrations, require fresh independent enrollment, and prove old restored sessions cannot authenticate. A build or emulator pass alone does not establish device acceptance.

<a id="at-16"></a>
### AT-16 Capacity and cost

Run the assumed low/typical/high mixes from TDD with mocked billable adapters, then a bounded real-model sample. Record writes, reads, object bytes, queue operations, input/output tokens, parse CPU, local latency/memory and bill estimate. Check formula arithmetic separately from live rates, account-shared included allowances and actual bills. Rate limiting leaves durable work visible; it never silently deletes received mail to hit a budget.

<a id="at-17"></a>
### AT-17 Privacy and hostile inputs

Use synthetic credential strings, prompt injections, malicious filenames/HTML, oversized JSON, support statement fields, URL redirects to private IPs, log markers and revoked sessions. Assert no external execution/secret disclosure, bounded resource use, owner-only downloads and appropriate metadata-only support handling. A forbidden support statement or invalid report signature stays model-ineligible even after manual quarantine release; only a validated sanitized projection is eligible. Review deletion/export/block/device policies and ensure unintended field updates are rejected.

## Change control and completion

Requirement IDs are never reused. A changed obligation updates its version/change note, linked tests and relevant ADRs; removal records a superseding decision. The suite is complete only when every row resolves to a functional/design owner and acceptance group, all accepted features are represented, and no contradictory policy remains. Actual deployment configuration, credentials and device measurements are verification inputs, not unspecified architecture alternatives.
