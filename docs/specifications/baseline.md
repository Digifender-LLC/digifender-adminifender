# Adminifender design baseline

Version 1.0 — October 1, 2026. Status: selected design, not implemented or deployed. Owner decisions take precedence over engineering defaults. The SRS owns requirements; this page owns shared constants and terminology used by the design documents.

## Owner decisions

Personal, single-owner Kotlin/Compose Android app; Galaxy S24+ with Google Play services; private Google Play testing; passkey and independent recovery; automatic hybrid local/Cloudflare inference; exact review for consequential actions; full email text and original attachments retained until explicit deletion; document/image extraction and OCR, no audio/video understanding; unknown workload and budget, prioritize low recurring cost. Reject manually blocked senders/domains before archival; quarantine automatically suspected spam, retain it, and skip downstream AI/OCR until released. All previously suggested capabilities are accepted with delivery phases.

## Delivery phases

| Phase | Contract |
| --- | --- |
| P1 | Complete personal email workflow: identity, capture/blocking/quarantine, archive, extraction/OCR, offline inbox/search/export, rules and both inference adapters, grounded chat, critical/inactivity push, receipts, diagnostics, private Play release. Email is the only external ingest source. |
| P2 | Whumpo report producers and operational views: maintenance/uncertainty, releases, operator-decision metadata, usage, service catalog/dependencies, reminders, runbooks and expected email heartbeats. External ingestion remains email. |
| P3 | Opt-in direct service reads, signed webhooks/GitHub sync, Android notification capture, share/file intake, outbound email, reviewed infrastructure/Whumpo actions, semantic retrieval, learned triage and forecasting. Disabled until each connector's acceptance gate passes. |

## Selected architecture

Single independently deployed TypeScript Cloudflare Worker application with email, HTTP, Queue-consumer and scheduled handlers; D1 CONTROL_DB owns mutable application state and the source-to-partition registry; versioned ARCHIVE_0001 and later D1 partitions own source metadata, chunks and FTS; private R2 stores raw staging, attachments and export objects; Queues carry job IDs; a one-minute Cron scan reconciles due jobs/outbox/notifications. Transactional D1 outbox plus replay-safe handlers prevents lost handoffs; there is no cross-service transaction. FCM HTTP v1 sends native push. No Durable Objects, Workflows, AI Gateway or external inference provider in the initial architecture.

Android uses Compose Material 3, ViewModels, coroutines/Flow, Room, DataStore, WorkManager, Credential Manager, Android Keystore and FCM. Minimum API 31 is a selected application baseline, not an assertion about the owner's installed OS. Use private Play internal testing with Play App Signing; actual package/application origin, domain, project IDs and signing fingerprints are deployment configuration, not invented credentials.

Local inference: LiteRT-LM Kotlin adapter, Gemma 3 1B IT int4 model artifact, CPU baseline with optional measured GPU acceleration. Cloud inference: Workers AI `@cf/meta/llama-4-scout-17b-16e-instruct` (owner decision, 2026-10-04: `@cf/meta/llama-3.2-3b-instruct`, the original choice, failed the citation and JSON checks on staging; see [sources](sources.md#cf-23)). Both produce the same validated application schema; neither executes free-form tool code. Package versions and model artifact hashes must be frozen in the implementation lockfiles/model manifest before device acceptance. Hardware suitability is not yet measured. Device failures use the documented routing policy rather than silently changing models.

Text search uses D1 FTS5 and Room FTS4 on synchronized chunks. Archive partitions warn at 7 GiB and roll at 8 GiB: a reviewed deployment provisions/binds the next database and changes the write target; there is no automatic resource purchase. A missing writable partition leaves R2 staging and visible pending_index work. Search visits at most four partitions per page and carries remaining partitions in its continuation cursor. Bodies above 256 KiB live in R2 with bounded D1 chunks. The control database has its own 7 GiB warning and 8 GiB maintenance gate; historical change/receipt tables are compacted under documented retention, never by deleting source records. P3 semantic retrieval uses Workers AI `@cf/baai/bge-small-en-v1.5` and Vectorize, with D1 authorization/deletion checks after candidate retrieval; embedding chunks use the selected tokenizer, at most 512 tokens and 384 output dimensions. Text, CSV, JSON, HTML, Markdown and DOCX text extraction use bounded Worker parsers; PDFs use PdfRenderer text extraction on API 35+ and page rendering plus bundled ML Kit Latin OCR on API 31–34 or scanned pages; JPEG/PNG/WebP use the same OCR adapter. Device extraction may wait while the phone is unavailable; original files stay retained. Unsupported/encrypted documents remain downloadable and explicitly unextracted. No server-side paid OCR fallback.

## Defaults and policy precedence

These are adjustable engineering defaults, not claimed owner habits or spending approvals.

| Setting | Default |
| --- | --- |
| Archive retention | Until explicit deletion, including quarantined mail |
| Successful raw MIME staging | 7 days after normalized text and attachment hashes are verified; failed captures retained until repaired/deleted |
| Completed export objects | 7 days; JSONL records, attachment files and SHA-256 manifest in a streamed ZIP |
| Attachment limits | Provider inbound ceiling is an external limit; accept within that ceiling, extract files up to 20 MiB and PDFs up to 100 pages per job; retain larger/unsupported content without extraction |
| Inactivity reminder | After 24 hours without meaningful review when actionable backlog exists; at most one reminder per 24 hours |
| Quiet hours | 22:00–08:00 in configured IANA timezone; onboarding proposes device timezone; critical notifications bypass quiet hours |
| Critical push | Rule-confirmed critical events immediately; AI-only critical classification enters urgent inbox without critical bypass until a trusted rule/owner confirms |
| Push repeats | Once per material critical signal ID/escalation; no unchanged critical repeats; due-date reminders once per due-date revision |
| Local readiness | Foreground interactive task, or device reports charging background worker; battery at least 30%, not severe thermal state, model ready; one inference at a time |
| Local preference | In auto mode, ready foreground local first for bounded text; background cloud triage unless a ready device has claimed the task; local-only always queues for device |
| AI input/output | Cloud: 6,000 characters per source chunk, up to 4 retrieved chunks and 1,000 output tokens, also bounded by model token context. Local 2,048-context artifact: at most 1,536 input tokens including instructions and 256 output tokens; pack/subchunk using its tokenizer. Long messages split deterministically, never truncate the archive |
| Cloud automatic allowance | Included allocation only until owner configures a paid monthly ceiling; unknown/stale shared-account headroom disables cloud dispatch; included-only dispatch also requires a dedicated account or coordination/reservations across every account writer. Application reservations cannot impose a provider-wide hard billing cap when other apps share the account. Default is zero incremental paid inference, not zero infrastructure cost |
| Cloud throttle | 100 automatic inference requests/day, 1,000 interactive requests/month maximum before local/queued mode; provider included allowance and configured monetary ceiling also apply, whichever blocks first |
| Sessions | Access 15 minutes; rotating refresh 30 days absolute; consequential action needs passkey verification within 5 minutes |
| Review expiry | 10 minutes, bound to exact parameters and current resource revision |
| Mutation identity | UUID idempotency key retained with result for 90 days; same key with different payload returns conflict |
| Offline cache | Latest 90 days of message/inbox text plus pinned items; attachments downloaded on demand with 500 MiB LRU cache; offline edits persist until sync |
| Sync | Monotonic server change cursor; tombstones 90 days; older clients rebootstrap; foreground refresh plus push hints and WorkManager reconciliation |
| Retry | 5 bounded automatic attempts for replay-safe jobs, exponential delay starting 30 seconds, capped at 1 hour; uncertain external effects never auto-replayed |
| Job lease | 5 minutes with fenced attempt token; heartbeat every 60 seconds for device work; expired pre-dispatch jobs retry, expired dispatched external work becomes uncertain |
| Diagnostics | Content-free operational records 30 days; block aggregate counts 30 days; no blocked bodies/attachments archived |
| Delete | Reviewed immediate server/online hiding and tombstone; offline devices purge on next authenticated sync; content/derived-file cleanup within 24 hours; backups expire within 30 days, independent suppression ledger reapplied on restore |

Precedence: disabled/revoked authority → deletion/privacy restrictions → explicit block → quarantine → local-only/no-AI policy (including OCR) → rule decisions → inference eligibility/cost → model interpretation. A trusted-sender hint never bypasses a manual block or authorizes an action. Protected report aliases/envelopes impose a non-downgradable privacy floor: before validation and for rejected sensitive payloads, quarantine with no model/OCR access; only validated sanitized projections may enter inference. Model output is evidence/proposal only. Restore from quarantine requires owner action; mark-not-spam offers a separately confirmed sender rule.

## Vocabulary

Email: archived source. Concern: grouped item requiring attention. Source: an email initially, or a P3 external record. Inbox status: open, waiting, snoozed or resolved, independent of read/acknowledged and importance. Job: replay-safe internal work. Operation: an external effect with durable intent and receipt. Review: an expiring authorization bound to an exact operation. Unknown/uncertain means the external outcome is not proven; it is not failure.

## Requirement catalogue allocation

SRS functional IDs are FR-001 through FR-054: identity; native/offline UI; email intake; blocking; quarantine; MIME parsing; archive; attachments; extraction; OCR; deletion; export; search; triage; explanation/correction; grouping; independent states; routine controls; deadlines/waiting/undo; briefing; hybrid route; local-only; device inference; cloud caps; chat; citations; durable turns; untrusted evidence; critical push; inactivity; push privacy; pipeline health; sync; reviewed actions; uncertain recovery; service catalogue; maintenance; release correlation; operator privacy; costs; readiness reminders; runbooks; report producers; heartbeats; direct reads; webhooks/GitHub; notification capture; share intake; replies; infrastructure operations; semantic search; learned rules; forecasting; settings.

NFR-001 through NFR-012: durability; confidentiality; performance; recovery; cost bounds; accessibility; compatibility; observability; distribution; testability; evidence honesty; independent ownership.

Acceptance groups AT-01 through AT-17: identity; intake; archive; extraction; triage; inbox; inference; chat; notifications; sync; actions; operations; connectors; advanced intelligence; distribution/recovery; capacity; security. Detailed test cases and the only traceability matrix belong to SRS.

## Evidence boundaries

The selected local/cloud models, storage/search, extraction APIs and service pricing require official source citations in TDD. Dated documentation establishes availability, not configuration or performance on this phone. Exact domain, Android version/SoC, account entitlements, Play account and usage are preflight inputs. A failed preflight blocks the affected activation, not archival or ordinary inbox use. Changing a selected technology requires a superseding ADR rather than an undocumented alternative.
