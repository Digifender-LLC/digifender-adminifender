# Adminifender accepted product notes

Updated October 1, 2026. The owner accepted every earlier feature suggestion and the full documentation plan. These notes are the consolidated product baseline; [SRS](specifications/srs.md) owns the numbered requirements and acceptance criteria, and [design baseline](specifications/baseline.md) owns selected defaults. Accepted later-phase capabilities remain requirements. No application or backend has been implemented or provisioned by this documentation work.

## Owner direction and resolved choices

Adminifender is a personal, evolving native Android app built in Kotlin and Jetpack Compose. Its main purpose is to retain incoming information, draw attention to important work, and provide useful actions and source-grounded chat. Cloudflare and services the owner runs there are the primary integration environment.

The target phone is a Galaxy S24+ with Google Play services. Distribution uses private Google Play testing. Authentication uses a passkey, revocable device sessions and recovery independent of the phone. The exact installed Android version, SoC, Play account setup and local inference performance remain verification inputs, not reasons to guess credentials or claim device acceptance.

The owner selected automatic hybrid local/Cloudflare inference, governed by sensitivity, task, device readiness and cost. Local-only content must not silently use cloud inference; no-AI content also excludes model-based OCR. Consequential actions require review of the exact effects. Documents and images, including scanned-document OCR, are supported processing targets; audio/video files are retained without understanding/transcription requirements.

Workload and monthly budget are unknown. Optimize ongoing cost, expose usage and configurable limits, and keep received data safe when inference is deferred. No claimed budget or paid-service authorization is inferred from a design choice.

## Accepted scope by delivery phase

### P1 Personal email workflow

Email is the only external ingestion source. Cloudflare-native routing delivers messages to the backend; forwarding existing mail to the ingest address remains compatible with this boundary. App chat, manual notes, rules and review actions are interactions, not additional external feeds.

Archive complete normalized text, meaningful links/structure, source metadata and original attachment bytes for every accepted email, including low-priority and quarantined mail. Original HTML layout is unnecessary. Summaries and embeddings never replace evidence. Preserve files even when extraction fails. Keep archive text/files until explicit deletion; temporary staging/export/diagnostic retention follows the selected technical policies. See FR-003–013.

Manual sender/domain block rules reject matching mail before body storage and AI calls. This is the explicit exception to retaining every email. Automatic spam suspicion quarantines rather than deletes: retain the evidence quietly, offer release/correction, and skip downstream AI/OCR until permitted. Protected operator reports retain a non-downgradable privacy restriction even if released. See FR-004–005 and FR-039.

The inbox presents actionable concerns, grouping related emails while keeping all evidence accessible. Importance, read status, acknowledgment and resolution remain separate. Provide explanations and saved corrections; Acknowledge, Done, Snooze, Open source, Ask agent and Show evidence; waiting/follow-up and due dates; undo, manual split/merge, reopening on material updates, and a since-last-review briefing. See FR-014–020.

Provide both inference adapters behind the same validated task schema. Global and source/concern/service-scoped chat can retrieve, summarize, explain and prepare supported actions with citations. The app must work for ordinary browsing, search and review when AI fails. Local-only processing of newly arrived email waits when the phone is unavailable; server rules can still identify critical conditions. See FR-021–028.

Critical notifications and inactivity reminders are separate configurable policies. Backend evaluation does not depend on the app running; lock-screen content remains generic and pushes only point to current authenticated state. Track meaningful review, quiet hours, cooldowns, snoozes, delivery limitations and stale evidence. Show pipeline health so an empty inbox is not mistaken for a healthy ingest service. See FR-029–032 and FR-054.

Support offline cached reads, pending reversible edits, explicit conflict handling, durable chat/action identity and recovery after lost responses. Exact review precedes consequential changes; an unknown external outcome never becomes a blind retry. Export/search remain independent of AI. See FR-033–035 and NFR-001–012.

### P2 Whumpo operations through email

All contextual suggestions are accepted: service/project/domain aliases and dependencies; maintenance/backlog/uncertain-operation concerns; release timelines and incident context; pending operator decisions; usage/cost/invoice attention; domain/connection/revocation/backup/recovery-readiness reminders; and owner-reviewed operational memory/runbooks. See FR-036–042.

Implement separately scoped producers of signed, minimized report emails and expected heartbeats. Missing reports mean missing evidence, not proven service failure. Display last-reported timestamps and separate deployment/readback from acceptance. Support-request content excluded from Whumpo AI context must never be copied into ordinary Adminifender model context; only validated sanitized metadata is eligible. See FR-043–044.

Whumpo's current CLI reports and planned native app are references, not existing Adminifender integrations. New producer/operator-service work is specified as a dependency; this documentation task does not change Whumpo.

### P3 Additional sources and reviewed service controls

Accepted future delivery includes direct Cloudflare/Whumpo reads, signed webhooks, incremental GitHub synchronization, owner-allowlisted Android notification capture, explicit share/file ingestion, outbound email replies, reviewed DNS/Worker-version/Whumpo release/recovery/support actions, semantic retrieval, reviewed learned triage and evidence-based forecasting. See FR-045–053.

Each connector is disabled until its own authority, contract and acceptance gate passes. Model output and received email cannot authorize provider effects. Recovery can automate evidence gathering and preparation; consequential execution still needs exact review. New external ingestion does not enter P1/P2 merely because its full design exists.

## Selected implementation direction

The complete selected design is in [HLD](specifications/hld.md), [SDD](specifications/sdd.md), [TDD](specifications/tdd.md) and [LLD](specifications/lld.md). The backend uses Workers, D1 control/archive partitions, private R2, durable jobs/outbox, Queues and scheduled reconciliation. Android uses native state/repository boundaries, Room, WorkManager, Credential Manager, Keystore and FCM. Source data, AI-derived results and reviewed external effects have distinct lifecycles.

These are engineering selections accepted for implementation planning, not deployed-service claims. [ADRs](specifications/adr/README.md) explain alternatives and consequences. Official references and dated cost assumptions live in [Sources](specifications/sources.md) and TDD. Defaults such as reminder intervals are configurable engineering choices rather than invented owner habits.

## Provenance and future edits

- **O — Original owner request:** Kotlin/Compose, personal evolving app, Cloudflare focus, native email intake, all accepted email text/files archived, actionable importance inbox, critical/inactivity push and local/cloud chat.
- **A — Acceptance of all suggestions:** general inbox improvements, operational context, reliability/privacy features and explicitly phased future integrations from the [Whumpo evidence notes](feature-suggestions.md).
- **D — Owner decisions:** full-vision buildable documentation; TDD means Technical Design; phased acceptance/email-only first; automatic hybrid; exact consequential review; documents/images; S24+ with Google Play; private Play testing; passkey/recovery; retain until deleted; unknown workload/low costs; manual-block rejection and automatic-spam quarantine.
- **E — Derived engineering decisions:** selected libraries/services, bounded contracts, configurable default values, storage rollover, reliability states and measurable test targets. These are recorded as decisions, not silently promoted to claims of owner-provided numbers or completed implementation.

Future changes update the SRS IDs, corresponding functional/design sections and tests, with superseding ADRs where needed. Do not relabel accepted later-phase features as optional or introduce a second contradictory requirements list.
