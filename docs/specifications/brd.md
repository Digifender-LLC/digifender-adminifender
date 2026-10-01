# BRD — Business Requirements Document

Version 1.0 · October 1, 2026 · Accepted product scope; implementation pending.

## Purpose and authority

Adminifender is a personal attention and operations app for one owner. It brings the important parts of incoming email onto a Galaxy S24+ as an actionable inbox, preserves the underlying evidence in Cloudflare, and provides a source-grounded assistant. Its value is time recovered, fewer missed obligations, and clearer operational decisions. It is not a commercial product and has no revenue, customer acquisition, public signup, or multi-tenant objectives.

This document describes the reasons to build the product and the outcomes it must serve. The [SRS](srs.md) owns normative requirements and acceptance criteria; the [shared baseline](baseline.md) owns selected defaults. The [PRD](prd.md) owns the product experience and delivery gates. Earlier [product notes](../product-notes.md) and [Whumpo research](../feature-suggestions.md) preserve provenance. Every previously suggested capability is accepted; phase assignment determines delivery order, not whether a feature is in scope.

## Problems to solve

| Present problem | Required product outcome | Evidence of benefit |
| --- | --- | --- |
| Relevant information arrives among routine mail and spam. | Apply inexpensive filtering and inspectable prioritization; retain accepted evidence and quarantine suspected spam. | The owner can find the source of every surfaced concern and recover a false-positive spam decision. |
| One issue can generate many messages. | Group related evidence into one concern without hiding its history. | Reviewing a concern exposes all constituent messages and changes since acknowledgment. |
| A message can be read without the underlying work being finished. | Separate read, acknowledged, unresolved, waiting, snoozed, and resolved states. | Acknowledging an alert does not silently remove an outstanding obligation. |
| Important work can be missed between phone visits. | Deliver critical alerts and bounded inactivity reminders using explicit policy. | Critical rule fixtures produce notification intents while ordinary mail does not cause repeated interruptions. |
| Operational history is scattered across releases, failures, invoices, and maintenance reports. | Associate time-stamped evidence with services, incidents, and owner decisions. | The owner can reconstruct what was reported and what changed, with uncertainty visible. |
| Models, connectivity, and the phone can be unavailable. | Preserve useful archive, inbox, search, pending edits, and recovery without requiring inference. | Model outage and offline acceptance scenarios retain ordinary review workflows. |
| An assistant can suggest an action whose actual effect is unclear. | Require exact review for consequential effects and durable evidence of the outcome. | Lost acknowledgments and uncertain provider responses never appear as proven completion. |
| Unknown mail volume and attachment growth create cost uncertainty. | Bound automatic AI processing, block known spam early, expose usage, and model storage growth. | Workload scenarios identify costs and limiting factors before paid activation. |

## Owner, stakeholders, and boundaries

The owner is the sole end user, administrator, decision-maker, and acceptance authority. Cloudflare is the primary hosting and integration environment. Google Play services provide the Android push transport, and private Play testing is the selected distribution path. These dependencies do not create a requirement for Google-hosted application data or a separate cloud AI provider.

Whumpo is a source of operational context and a later integration target. Adminifender remains independently authenticated and deployed. Whumpo customer ownership does not imply platform-operator authority. Customer statements that Whumpo excludes from model context must not be imported into Adminifender's general assistant corpus. The [operator usage-review contract](/home/ender/Documents/TellUmo/docs/usage-review-requests.md) is evidence for that boundary.

The initial system accepts external data only by email. Owner-entered rules, catalog entries, notes, chat requests, and inbox controls are app interactions rather than new ingest channels. Additional ingest sources and direct service operations are accepted P3 capabilities with individual activation gates. This documentation task does not implement them, provision accounts, send messages, or modify Whumpo.

## Business requirements

| ID | Requirement | Related functional requirements |
| --- | --- | --- |
| BR-01 | Provide a native, private, recoverable personal app optimized for the owner's Galaxy S24+. | FR-001–002, FR-033, FR-054 |
| BR-02 | Preserve full useful email text and original attachments, with explicit exceptions for mail rejected before archival and deliberate deletion. | FR-003–013 |
| BR-03 | Reduce review effort through trustworthy triage, grouping, controls, briefings, and searchable evidence. | FR-014–020 |
| BR-04 | Make local and Cloudflare inference practical while respecting sensitivity and monetary limits. | FR-021–024 |
| BR-05 | Answer questions using retained evidence and preserve unfinished conversations through interruption. | FR-025–028 |
| BR-06 | Bring genuinely urgent or neglected work to the owner's attention without depending on the phone running continuously. | FR-029–032 |
| BR-07 | Make every consequential action reviewable, attributable, and recoverable without blind replay. | FR-034–035, FR-049–050 |
| BR-08 | Turn Whumpo operational reports into useful maintenance, incident, cost, dependency, and readiness views. | FR-036–044 |
| BR-09 | Add wider inputs, direct integrations, and advanced intelligence behind bounded, reversible activation choices. | FR-045–053 |
| BR-10 | Preserve low operating cost and maintainability as a personal project evolves. | FR-004–005, FR-024, FR-040, FR-054; NFR-005, NFR-012 |

## Success measures and acceptance

Success is judged first by end-to-end acceptance, then by personal usefulness on real traffic. The system records content-free counters for receipt, processing failure, classification route, corrections, concern count, notification intent, and operation outcome. It does not need product analytics or third-party behavioral tracking.

| Measure | Acceptance baseline | Ongoing interpretation |
| --- | --- | --- |
| Evidence preservation | Every accepted test message can be reconstructed as complete normalized text plus attachment hashes/files; accepted malformed mail remains recoverable. | Compare accepted receipts, completed archives, and unresolved capture jobs. A zero-error dashboard cannot hide failed or unobserved work. |
| Inbox coverage | Every relevant fixture appears in the expected concern/state; inference failures remain visible. | Review owner promotions and demotions to improve explicit rules. No fabricated accuracy percentage is promised before a representative corpus exists. |
| Decision quality | All assistant factual answers have inspectable source links or explicitly state missing evidence; correlations are labeled. | Inspect disputed answers and correction history before expanding automation. |
| Interruption restraint | Notification scenarios follow critical, quiet-hour, inactivity, cooldown, and deduplication policy. | Track notification intents and acknowledged concerns; provider acceptance is not a display guarantee. |
| Recovery | Offline edits survive process death; duplicated requests do not duplicate effects; uncertain effects remain unresolved until readback. Disaster restore reconciles the newest independent suppression ledger and requires fresh identity enrollment. | Inspect oldest pending work, recovery receipts, and dated restore evidence. |
| Owner control | A blocked sender is rejected before archival; quarantined mail can be released; local-only work never falls back to cloud inference. | Show policy decisions and usage in Settings and Health. |
| Cost | Low, typical, and high workload estimates are documented; paid inference is disabled until an explicit ceiling is configured. | Compare observed operations, token use, and retained bytes with estimates. Storage growth is not disguised by deleting retained evidence. |

No workload, monthly budget, target accuracy, or claimed time saving has been supplied. Those values must be measured rather than attributed to the owner. Workload assumptions for design scenarios belong in [TDD](tdd.md); acceptance thresholds belong in [SRS](srs.md).

## Delivery economics and dependencies

P1 establishes a useful complete email workflow, including both inference adapters, reliable archival, spam controls, grounded chat, notifications, and recovery. P2 adds operational value using emailed reports, so it does not depend on opening general-purpose operator APIs. P3 expands sources, direct reads, reviewed external actions, and learned/semantic intelligence only after each connector passes its contract tests.

Cost controls are applied before expensive processing: reject explicit blocks, quarantine suspected spam, use deterministic parsing/rules, avoid repeating extraction and inference, enforce budgets, and defer to local or queued processing when limits prevent cloud work. Default included-only inference also requires known account headroom and coordinated account writers; app counters cannot cap unrelated applications' provider spending. An infrastructure bill can still accrue for storage, request traffic, and the retained archive; the selected inference ceiling is not a promise of a free deployment.

Activation depends on a suitable ingest domain and DNS setup, actual Cloudflare entitlements and limits, Play/Firebase setup, passkey origin association, and physical-device acceptance. These are verification inputs, not undecided product scope. Whumpo's report publishers and operator APIs require separate implementation. The existing [maintenance report](/home/ender/Documents/TellUmo/docs/maintenance.md) explicitly describes a read-only CLI and states that alert delivery is not installed; its existence does not prove an email integration exists.

## Risks and treatment

| Risk | Selected treatment |
| --- | --- |
| Missed urgent mail while the phone cannot infer. | Server-side trusted rules remain available; allowed cloud inference can triage; local-only work remains visibly pending. AI-only urgency does not gain critical quiet-hour bypass. |
| Spam increases storage or AI cost. | Reject explicit sender/domain blocks; quarantine automated suspicions; skip downstream AI/OCR until owner release; expose volume/cost controls. |
| Important mail is incorrectly classified. | Preserve accepted sources, expose explanations and corrections, and support false-positive quarantine release. |
| Reports become stale or incomplete. | Display observed/received time, last-reported state, unavailable/unobserved distinctions, and configured missing-heartbeat concerns. |
| Model output or email content attempts to control infrastructure. | Treat input as untrusted evidence; validate closed action types and obtain exact reviewed authority in application code. |
| A lost response triggers duplicate work. | Durable identity, fenced work, provider readback, and an explicit uncertain state. |
| The design becomes too large for a personal project. | One small backend with module boundaries, phased activation, shared contracts, and ADRs for architectural changes. |

## Change governance

Add or revise a numbered requirement and its traceability entry when behavior changes. Update the applicable PRD/FRD/design sections and acceptance cases in the same change. Supersede an ADR when changing a selected architecture decision. A phase gate may delay activation but cannot silently remove an accepted capability. Environment findings belong in verification evidence; personal preferences remain owner decisions.
