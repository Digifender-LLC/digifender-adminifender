# Adminifender accepted features and Whumpo evidence

Prepared October 1, 2026, after reviewing the adjacent `/home/ender/Documents/TellUmo` repository, including feature documents and representative implementation code. The owner has **accepted every feature below**. The [SRS](specifications/srs.md) now owns the requirements; this file preserves their rationale and local evidence. P1/P2/P3 timing is defined by the [baseline](specifications/baseline.md). The strongest direction is a personal attention and operations app: retain evidence, surface decisions, and help the owner finish the next useful action.

The initial version still ingests only email. An operations view built from email should say “last reported” and show its timestamp; it cannot claim current service health. Where a service does not already send the relevant mail, an email-producing integration must be built separately. Direct polling, webhook intake, and live service queries would expand the initial scope.

## Accepted general capabilities

| Accepted capability | Why it would help | Sensible first version |
| --- | --- | --- |
| Explain and correct prioritization | Makes filtering inspectable and helps prevent missed important mail. | Show the source excerpt and rule/model reason; offer “Always surface this” and “Less important.” Save explicit corrections before attempting automatic learning. |
| Group related alerts into one concern | Avoids ten emails becoming ten separate obligations. | Group by thread and known project/service identifiers; keep all source messages accessible. Reopen when a new event meaningfully changes the concern. |
| Briefing since last review | Answers what needs attention without rereading everything. | Summarize new, changed, overdue and unresolved items with links. Avoid repeatedly highlighting already acknowledged information. |
| Deadlines and waiting states | Helps turn an inbox into a useful personal work queue. | Snooze until a time, mark waiting, attach a due date, undo a dismissal, and resurface approaching obligations. Preserve the original wording for AI-extracted dates. |
| Attachment search | Makes the retained archive useful beyond notifications. | Search filenames and extracted document text, with a visible extraction status and access to the original file. P1 includes document/image OCR with explicit unsupported-format status. |
| Contextual chat | Reduces the effort of explaining an alert to the assistant. | “Ask about this” scopes retrieval to the selected email, concern or service. Answers cite sources and distinguish an observed event from a possible cause. |
| A pipeline health view | Reveals whether a quiet inbox means no work or broken ingestion. | Show last receipt, oldest unprocessed email, extraction/AI failures, pending sync and push status. An expected emailed heartbeat can later detect missing reports. |
| Search and export independent of AI | Keeps the archive useful through model outages and future redesigns. | Sender/date/project/text filters and an export of normalized records plus attachment files. Retain archive evidence until explicit deletion; temporary staging/export copies have separate lifecycles. |
| Personal inference controls | Makes the local/cloud choice practical instead of hidden. | Select route by task or sensitivity; show model/route and usage. Defer expensive local work until appropriate battery/charging conditions. |

**P1 delivery includes:** explanations with corrections, related-alert grouping, and a briefing since last review. They improve the requested inbox immediately without needing a new external integration.

## What the Whumpo project establishes

Whumpo is an invitation-only AI website and application platform in private testing. Its README describes four separate Worker services and distinguishes implementation from configuration and customer acceptance. It also states that the MVP is incomplete. This is useful operational context, not evidence that every documented capability is live. [Whumpo README](/home/ender/Documents/TellUmo/README.md:1).

Whumpo already has a planned Kotlin/Compose app for its platform's project owners. Its document explicitly says native application code, mobile authentication/API routes, and FCM delivery are not implemented or configured by that plan. Adminifender is the owner's personal app and should keep its own scope; there is no existing native Whumpo codebase to adopt. [Android roadmap](/home/ender/Documents/TellUmo/docs/android-app.md:3).

The suggestions below use local source and documented evidence. No live Cloudflare account, customer data, credentials, or private evidence archives were inspected. Dated deployment statements are attributed to the repository, not independently reverified here.

## Accepted Whumpo capabilities

### Maintenance and recovery inbox

**Accepted capability:** Turn overdue maintenance, growing backlogs, and unresolved operations into one prioritized operations inbox. A card could say “Backup outcome unresolved,” show when it was last observed, and offer Acknowledge, Snooze, Show evidence, and Open recovery instructions.

**Evidence:** Whumpo has a private maintenance report covering scheduled-job telemetry, 27 backlog queries, and unresolved operation IDs across several families. The report distinguishes unavailable, unobserved, skipped and failed states; completion of a scheduler pass does not prove every item succeeded. It is an operator CLI using Cloudflare/D1 credentials, and alert delivery is explicitly not installed. [Maintenance contract](/home/ender/Documents/TellUmo/docs/maintenance.md:3), [backlog coverage](/home/ender/Documents/TellUmo/docs/maintenance.md:32), [alert boundary](/home/ender/Documents/TellUmo/docs/maintenance.md:50).

**Delivery path:** Add a separately scoped publisher of sanitized report emails, or initially consume manually emailed reports. The phone reads those emails without broad operator credentials. Preserve unknown outcomes; a Retry button is inappropriate unless a later recovery integration can establish that retry is safe.

**Priority:** Highest-value Whumpo-specific addition once the basic inbox works.

### Release timeline and incident context

**Accepted capability:** Associate release notices and later incident emails so chat can answer “What changed before this started?” Show the relevant release, affected services, observation time, and links to original evidence. A time correlation should be described as a hypothesis, not a diagnosis.

**Evidence:** Whumpo's release tooling records four-Worker versions, migration identity, archived inputs, operation intent and partial-recovery state. Its documentation distinguishes successful deployment readback from application acceptance and retains uncertain outcomes. This is operator tooling, not a ready mobile release API. [Release contract](/home/ender/Documents/TellUmo/docs/releases.md:3), [journal contents](/home/ender/Documents/TellUmo/docs/releases.md:32), [recovery states](/home/ender/Documents/TellUmo/docs/releases.md:60).

**Delivery path:** A new release-summary email can carry a bounded status, release ID and approved evidence link. Do not email complete private release archives. Begin with review and navigation; deployment and rollback controls would be a distinct future integration with current-state checks.

**Priority:** High, especially if the owner deploys frequently.

### Pending operator decisions

**Accepted capability:** Collect requests that need the owner, with age, reason, relevant evidence and a next step. Examples include support/usage review, uncertain provider work, and an expired review that needs to be prepared again. Keep these separate from routine informational mail.

**Evidence:** Whumpo documents customer usage-review requests and operator responses in its private test platform. It does not promise email delivery or a response time, and its workflow has privacy restrictions on model context. Existing typed Chat controls and review receipts provide patterns for bounded actions and checking current review state. [Usage-review contract](/home/ender/Documents/TellUmo/docs/usage-review-requests.md:3), [support privacy](/home/ender/Documents/TellUmo/docs/usage-review-requests.md:16), [typed controls](/home/ender/Documents/TellUmo/src/chat-controls.ts:1), [review receipts](/home/ender/Documents/TellUmo/src/chat-review-receipts.ts:8).

**Delivery path:** Build a metadata-only notification email where appropriate: request ID, age, category and a trusted destination. Do not copy customer statements excluded from AI context into the general email-agent corpus. Where no browser operator review exists, begin with a reminder and instructions; a native response button needs a new narrow operator API.

**Priority:** High when there are actual pending requests; avoid a permanently empty dashboard.

### Usage and cost attention

**Accepted capability:** Combine provider billing emails and deliberately exported operational summaries into a view of unusual usage, approaching budgets and items needing reconciliation. Keep invoice amounts, estimates, internal usage and unsettled operations visibly distinct.

**Evidence:** Whumpo already distinguishes standard AI accounting, historical usage, fair-use observations and operator-attested company-finance comparisons. Its README says automated verified finance ingestion and active enforcement are not implemented, and Stripe purchases are disabled. Workers AI and AI Gateway routing are planned rather than the current test provider. [Plans and inference status](/home/ender/Documents/TellUmo/README.md:21), [maintenance provider receipts](/home/ender/Documents/TellUmo/docs/maintenance.md:42).

**Delivery path:** Use invoices/alerts that arrive by email and new sanitized P2 report emails. Display “as of” dates and source type. A billing email alone must never silently change Whumpo customer limits or payment state.

**Priority:** Medium; begin with simple reminders in P2 before accepted P3 forecasting and reviewed reconciliation workflows.

### Domains and service dependencies

**Accepted capability:** Give an issue a lightweight service context: Whumpo project, domain, relevant Cloudflare service, related messages and known dependencies. Group domain renewal, DNS, certificate, hosting and backup notices when the email provides enough evidence.

**Evidence:** Whumpo maintains separate service boundaries, domain/hosting contracts and operator diagnostics. Its Cloudflare catalog expressly distinguishes platform capabilities from customer integrations and cautions against recreating a broad provider dashboard. [Service layout](/home/ender/Documents/TellUmo/README.md:45), [Cloudflare capability status](/home/ender/Documents/TellUmo/docs/cloudflare-capability-roadmap.md:5).

**Delivery path:** Maintain a small owner-edited service catalog and alias mapping; associate received emails with it. This configuration is compatible with email-only external ingestion. Do not infer that certificate or backup mail already exists for every service. Live provider status comes later if requested.

**Accepted extension:** Add reminders for reconnecting a service, unfinished credential revocation, and backups without recent verification. Distinguish a historical source-backup receipt from a current recoverable platform backup. Whumpo's GitHub backup contract still records real provider acceptance as pending configuration. [Backup acceptance boundary](/home/ender/Documents/TellUmo/docs/integrations/github-backups.md:57).

**Priority:** Medium; a few labels and aliases can deliver value before a dependency graph.

### Operational memory and reusable response steps

**Accepted capability:** After resolving an incident, save a short owner-reviewed note: what happened, which sources established it, which action worked, and what should be tried next time. Chat can retrieve it when a similar email arrives.

**Evidence:** Whumpo's Jarvis reuse notes distinguish recorded intent, accepted result and uncertain outcome. Its current browser Chat helper reads back a possibly committed result after a lost response instead of immediately sending another inference request. Those are useful reliability patterns for a phone app that backgrounds or loses connectivity. [Jarvis recovery design](/home/ender/Documents/TellUmo/docs/jarvis-reuse.md:19), [Chat recovery implementation](/home/ender/Documents/TellUmo/frontend/src/lib/chat-delivery.ts:12).

**Delivery path:** Add owner notes to an existing inbox concern. Store the supporting emails and action receipts with the note. A suggested response procedure can propose a next action, but should not autonomously repeat an old operation against a new incident.

**Priority:** Medium, after real incidents provide useful examples.

## Reuse ideas without inheriting a second product

- **Reuse concepts:** closed action types, current-state review receipts, durable request identity, explicit uncertain outcomes, private object access and source-linked answers. Adapt the small contracts where useful rather than importing the entire Whumpo backend.
- **Keep independent:** Adminifender's inbox-first experience, personal settings, inference choice and archive. Whumpo's planned chat-first product does not override the requested inbox.
- **Keep AI optional for ordinary use:** browsing mail, search, acknowledgment, snooze and opening a trusted destination should remain available when inference fails. Whumpo's proposed interactive Chat experience also documents fallback requirements. [Chat roadmap](/home/ender/Documents/TellUmo/docs/roadmap/chat-first-experience.md:77).
- **Treat future controls as an integration:** Whumpo currently has browser authentication and proposes a separate native API. Customer-owner access would not automatically authorize platform-operator access. Start with authenticated browser destinations that actually exist; add narrowly scoped native access only for selected tasks. [Current and proposed mobile interface](/home/ender/Documents/TellUmo/docs/android-app.md:27).

## Accepted P3 scope

Direct Cloudflare/Whumpo polling, webhook intake, GitHub synchronization, phone notification capture and general file/share ingestion add external sources beyond email. Outbound email replies, remote deployment, DNS edits and recovery controls add consequential actions with exact owner review; recovery automation cannot bypass that policy. All are accepted P3 requirements. They remain outside P1/P2 activation, with contracts in the LLD and per-connector acceptance gates.

Deliver the complete P1 email workflow first, P2 operational reporting next, then P3 integrations. This sequence preserves the owner’s email-only initial boundary while retaining the full accepted vision.
