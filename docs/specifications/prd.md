# PRD — Product Requirements Document

Version 1.0 · October 1, 2026 · Complete accepted product vision; implementation pending.

## Product definition

Adminifender is a personal Android inbox and assistant for the owner's email and operational responsibilities. It receives email through Cloudflare, retains the full normalized text and original attachments, turns important evidence into grouped concerns, and helps the owner review or act from a Galaxy S24+. The product remains useful when the network or models are unavailable.

Kotlin/Jetpack Compose, private Play testing, passkeys with independent recovery, and automatic hybrid inference are selected. This is a single-owner product whose requirements may evolve. All original suggestions are accepted. The [SRS](srs.md) is normative; the [FRD](frd.md) details behavior; the [baseline](baseline.md) supplies defaults and policy precedence. This PRD does not imply that any component is implemented or deployed.

## Experience principles

- Lead with what needs attention, why it matters, and the next available action.
- Preserve evidence separately from summaries; show the source when a claim matters.
- Keep read/acknowledgment, priority, and resolution independent.
- Show stale, unavailable, pending, failed, and uncertain states accurately.
- Make ordinary review available without AI; explain route and cost decisions when inference is used.
- Require exact effects for consequential review; opening the app or reading a model answer never grants authority.
- Keep the personal workflow small and coherent as integrations are added. Avoid a generic Cloudflare dashboard or Whumpo customer-app clone.

## App information architecture

The bottom navigation contains **Inbox**, **Archive**, and **Chat**. **Operations** appears once a P2 service/report is configured. Settings and Health are available from the top-level account menu. This keeps an unused operational dashboard out of the initial daily path without removing its accepted scope.

| Surface | Content and principal controls |
| --- | --- |
| Inbox | Open concerns, changed evidence, urgent and due items, waiting/snoozed filters, and a since-last-review briefing. Cards expose Acknowledge, Done/Reopen, Snooze, Wait, source, and Ask agent. |
| Concern detail | Current state, service context, chronological source list, extracted dates with original wording, explanation/corrections, notes, and action receipts. |
| Archive | Accepted email/source text, structured filters, attachment filenames/text, original file access, extraction status, export and reviewed deletion. Quarantine has a separate filter and badge. |
| Chat | Global, concern, email, attachment, or service context; cited answers; route/usage; durable pending turns; typed proposed actions. |
| Operations | Maintenance/uncertain work, release/incident timeline, pending operator decisions, cost observations, service dependencies, readiness reminders, and runbooks. Each report displays its evidence age. |
| Health | Last receipt, processing backlog, extraction/AI failures, sync status, push registration/delivery diagnostics, report freshness, and recovery instructions. |
| Settings | Identity/recovery/devices, source and sender rules, quarantine, inference/privacy, notifications/timezone, cache/storage/export, service/report configuration, and opt-in P3 connectors. |

The UI uses system font scaling, accessible labels and touch targets, explicit text status in addition to color, and dark/light system themes. All source rendering is plain text with safe link affordances; the app does not execute email HTML or embedded scripts.

## Owner journeys

### Set up a private phone

The owner installs the app through private Play testing, establishes passkey access and independent recovery, and confirms notification, timezone, and inference preferences. The app explains that email storage is in Cloudflare even when inference is local-only. Local model download/setup reports size, readiness, and errors. The phone is not considered ready for unattended local jobs until actual model acceptance succeeds.

The owner can revoke a lost device and recover using the independent method. Recovery does not resurrect old sessions or silently dispatch queued work. Missing deployment configuration produces an actionable setup failure; the app does not invent a usable ingest address or accept a broken source setup as complete.

### Receive, filter, and retain email

An email reaches the dedicated Cloudflare route. Explicit sender/domain blocks reject it before archival. Automated spam suspicion retains it in quarantine, where no downstream AI or OCR occurs until release. Other accepted mail is durably captured, normalized, archived, indexed, and triaged. HTML-only mail becomes readable text while meaningful link destinations and structure remain accessible. Original attachments survive extraction failure.

The owner can release a legitimate quarantined message, optionally confirm a future sender rule, or add a block for later mail. Release preserves privacy restrictions: protected report content remains model/OCR-ineligible, with only validated sanitized projections eligible for inference. Existing messages are not retroactively deleted by blocking their sender. Unsupported or oversized-for-processing attachments remain available with a reason rather than disappearing.

### Review one concern

The inbox groups related reports or email replies. A card states the reason it surfaced and links to source evidence. The owner can acknowledge it, correct importance, assign or correct a due date, mark it waiting, snooze it, or resolve it. A saved correction can become an explicit rule after review. A new relevant event marks the concern changed and may reopen a resolved concern; it does not erase the previous resolution history.

The owner can reverse a routine edit when its revision still permits safe undo. Source deletion and external operations use a separate reviewed flow. Opening a concern marks it read but does not complete it or reset the inactivity clock by itself.

### Catch up after time away

A briefing groups new, changed, overdue, and unresolved concerns since the last meaningful review. It avoids recycling unchanged acknowledged information as new. The owner follows evidence, completes work, or explicitly marks the briefing reviewed. Critical server rules can request push while the phone is away. Inactivity reminders require an actionable backlog and obey the selected cooldown and quiet-hour policy.

### Ask with context

From a concern the owner taps Ask agent, or opens global Chat. The assistant retrieves evidence from the selected scope, shows source links, and distinguishes reported facts from hypotheses. Missing extraction, stale reports, and unavailable evidence are visible. The owner can inspect the inference route and usage. A local-only request waits for a ready phone; it is never silently rerouted to Cloudflare inference. No-AI policy also disables OCR while preserving deterministic text parsing and ordinary archive/inbox use.

A request interrupted by app termination or connectivity loss resumes from its durable identity. The app reads back the prior result before offering a new submission. A proposed action is displayed as a typed control with its effects; model prose alone cannot execute it.

### Understand Whumpo operations

In P2, a configured publisher emails signed sanitized maintenance, release, operator-decision metadata, usage, and heartbeat reports to its registered alias. Adminifender verifies the service-bound signature and maps the validated projection to owner-defined services and concerns. The owner sees last-reported status, age, unresolved operations, release timelines, and pointers to valid recovery instructions. Private support statements are excluded at the producer boundary; protected raw reports remain model-ineligible, and failed verification stays restricted even after release.

The owner may record a resolution note, link supporting evidence and receipts, and promote it to a reusable runbook after review. Correlating an incident with a preceding release is a hypothesis, not proof that the release caused it. No P2 control deploys, retries an uncertain operation, or changes Whumpo state.

### Expand the app deliberately

In P3, the owner enables individual direct-read, signed webhook/GitHub, notification-capture, and share/file connectors. The app names the permissions, source scope, sensitivity policy, and last successful synchronization. Notification capture is limited to explicitly chosen packages; share intake shows a preview before submission.

Outbound replies and supported service operations present the exact recipient/target, parameters, current revision, and effects for fresh review. Changed or expired reviews require preparation again. Uncertain outcomes lead to status inspection and reconciliation, not blind retry. Semantic retrieval and learned suggestions augment explicit filters and text search; a learned policy change requires owner confirmation.

## Feature inventory and phasing

| Capability | Phase | Requirement coverage and product boundary |
| --- | --- | --- |
| Private native shell, passkeys, recovery, revocable devices, offline cache | P1 | FR-001–002, FR-033; actual Android version/SoC and device performance remain verification inputs. |
| Email capture, early blocks, spam quarantine, robust MIME handling | P1 | FR-003–006; only email is an external ingest source. |
| Complete text/attachment archive, extraction/OCR, deletion/export/search | P1 | FR-007–013; preserve unsupported files; no audio/video understanding. |
| Inspectable prioritization and explicit corrections | P1 | FR-014–015; no silent learned filtering changes. |
| Grouping and independent concern/read/acknowledgment states | P1 | FR-016–017. |
| Routine controls, deadlines, waiting, safe undo, briefing | P1 | FR-018–020. |
| Automatic hybrid local/cloud route, local-only, model readiness and cloud caps | P1 | FR-021–024; both selected adapters are required for P1 acceptance. |
| Global/contextual grounded chat, citations, durable turns, untrusted-input handling | P1 | FR-025–028. |
| Critical/inactivity push, private notification payloads, health | P1 | FR-029–032; denied permission does not block the app. |
| Review/receipt/uncertain-outcome foundation | P1; external effects P3 | FR-034–035; reviewed source deletion and identity/security actions already need the common foundation. |
| Owner-edited catalog, aliases, and dependency context | P2 | FR-036; related incoming emails can be manually labeled in P1. |
| Maintenance and recovery inbox | P2 | FR-037; show unavailable, unobserved, skipped, failed, and uncertain distinctly. |
| Release and incident context | P2 | FR-038; retain deployment-readback versus application-acceptance distinction. |
| Pending operator decision metadata | P2 | FR-039; exclude protected customer support statements. |
| Usage, invoice, estimate, and unsettled-cost attention | P2 | FR-040; provenance and as-of dates; no automatic limit/payment changes. |
| Domain/connection/revocation/backup readiness reminders | P2 | FR-041; a historical backup receipt is not proof of current restore readiness. |
| Owner-reviewed operational memory and runbooks | P2 | FR-042; retrieval does not authorize replay of an old action. |
| Sanitized Whumpo email report publishers and heartbeat expectations | P2 | FR-043–044; required producer work is separate from this documentation task. |
| Direct service reads | P3 | FR-045; allowlisted resources and narrow credentials, displayed freshness. |
| Signed webhooks and bounded GitHub synchronization | P3 | FR-046; explicit subscriptions and replay protection. |
| Android notification capture | P3 | FR-047; explicit permission and package allowlist, disabled initially. |
| General share/file intake | P3 | FR-048; preview and submit, no automatic URL fetch. |
| Outbound replies and infrastructure/Whumpo operations | P3 | FR-049–050; exact review, preconditions, receipts, and uncertain recovery. |
| Semantic retrieval, learned triage proposals, forecasts | P3 | FR-051–053; source-authorized retrieval and measured benefits. |
| Policy settings and transparent defaults | All | FR-054; baseline owns defaults. |

## Delivery gates

**P1 acceptance:** Prove the full email-to-review path on the owner's phone, including malformed and duplicated messages, quarantine release, original attachment integrity, supported extraction/OCR, both inference routes, cost refusal, grounded chat, offline/process-death recovery, notification permission denial, passkey recovery, export/deletion, and private Play update compatibility. Verify deletion against the independent suppression ledger, offline purge at the next authenticated sync, and fresh identity enrollment after disaster restore. The initial archive and deterministic inbox must be useful before model-dependent enrichment is activated. Missing local-device acceptance blocks P1 completion rather than removing the local-inference requirement.

**P2 acceptance:** Each report producer has a versioned sanitized payload, authenticated origin verification, replay/freshness handling, and representative fixtures. Prove every operational state and missing-heartbeat behavior without importing protected content or asserting real-time health. Whumpo code changes are separately delivered and tested in its own repository.

**P3 acceptance:** Enable each connector and operation type only after authorization, permission revocation, input validation, idempotency, privacy, rate/cost control, and recovery tests pass. Semantic retrieval must show benefit on a representative retained corpus while honoring deletion and source visibility. Learned triage remains a reviewed proposal until its rule is approved. Forecasts identify their evidence horizon and uncertainty.

## Product exclusions and constraints

Public app-store distribution, multi-user account administration, iOS, autonomous spending/deployment, audio/video understanding, arbitrary code execution from mail, and unrestricted provider dashboard coverage are outside this accepted design. Supporting many future services does not justify a plugin marketplace, generic command tunnel, or a second copy of Whumpo's backend.

Actual workload and spending comfort are unknown. Keep them visible as measurement inputs. Numeric defaults, retention windows, and route limits are selected engineering defaults in the [baseline](baseline.md), not statements of the owner's habits or promises about provider availability.

## Research provenance

The Whumpo [Android roadmap](/home/ender/Documents/TellUmo/docs/android-app.md), [maintenance contract](/home/ender/Documents/TellUmo/docs/maintenance.md), [release contract](/home/ender/Documents/TellUmo/docs/releases.md), and [usage-review contract](/home/ender/Documents/TellUmo/docs/usage-review-requests.md) establish useful requirements and patterns. They do not establish that Adminifender can call a current native operator API or that report mail already exists. The complete source discussion remains in [Whumpo context](../feature-suggestions.md).
