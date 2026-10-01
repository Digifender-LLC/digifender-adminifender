# Specification validation record

October 1, 2026. Scope: the consolidated notes, workspace index and linked specification suite. These results concern documentation and contract examples, not a running Android app or backend.

## Coverage and reconciliation

The suite contains all nine requested document types: BRD, PRD, FRD, SRS, HLD, SDD, TDD, LLD and an indexed collection of ten ADRs. The SRS defines 54 functional and 12 nonfunctional requirements, each with a canonical traceability row linking provenance, delivery phase, functional behavior, detailed design, decision and acceptance group. Seventeen acceptance groups cover normal flows, failure cases, privacy, recovery, device delivery and cost assumptions.

The authoring review reconciled the following shared contracts across documents:

- P1/P2 email-only ingestion and accepted, separately activated P3 capabilities.
- Manual rejection before body storage versus retained automatic-spam quarantine.
- Immutable source/attachment bytes and published extraction generations, with stale-job fencing.
- Independent read, acknowledgment, importance and concern-resolution state; material-evidence notification identity.
- Local-only/no-AI restrictions, protected raw reports and sanitized inference projections.
- Account-aware cost reservations and visible deferral without automatic archive deletion.
- Exact reviewed effects, stale authority rejection and uncertain outcomes without blind replay.
- Independent deletion suppression before confirmation, current-ledger verification on restore and fresh identity enrollment.

Whumpo evidence remains attributed to the adjacent repository. Required report producers and operator APIs are specified as new work. This task did not change Whumpo.

## Checks performed

| Check | Result and scope |
| --- | --- |
| Repository documentation check | `python3 tools/check_docs.py` passed with exit code 0: 44 Markdown files and seven workflow pairs. Checks repository-relative link targets and existing agent-workflow wiring; it does not check external URLs or Markdown anchors. |
| Specification links and anchors | Python standard-library scan passed for 26 authored Markdown files and 476 local links across the suite and original notes/index: local paths, cited adjacent-file paths, explicit anchors and generated heading anchors resolve; fenced code blocks are balanced. |
| Requirements coverage | All 66 IDs occur once in their normative table and once in the canonical matrix. Matrix phases match the normative functional rows; all referenced acceptance anchors exist. |
| SQL examples | Both LLD schema blocks executed successfully in isolated in-memory Python SQLite databases with foreign keys enabled and FTS5 available: 45 control and ten archive tables including SQLite/FTS internal tables. This is syntax/schema validation, not a D1 migration or transaction-behavior test. |
| JSON examples | All four fenced JSON examples parsed successfully. Kotlin/TypeScript contract sketches were read for consistency but were not compiled. |
| Cost arithmetic | Decimal recomputation matched the TDD low/typical/high scenario tables and their stated storage/usage assumptions. Estimates are not a forecast of the owner's unknown workload or a verified bill. |
| Source verification | The technical author inspected 46 dated primary platform/model/provider references listed in [Sources](sources.md). Platform documentation supports capabilities and rates, not account entitlement or measured device behavior. |
| Diagrams | Mermaid flow/state/sequence definitions were reviewed against the prose. No rendered visual-layout or Mermaid-engine validation is claimed. |

The additional link/ID/SQL/JSON checks used Python standard-library scripts during this authoring session. The repository check above is the workspace's reusable command; implementation must add contract fixtures and executable behavior tests described in [TDD](tdd.md#development-and-verification-workflow).

## Evidence still required for implementation

There is no Android/Worker build, deployed service, remote migration, live email route, Play release, provider action or phone benchmark certified by this record. Exact Android version/SoC, inference memory/latency, library/model artifact versions, domain association, account entitlements and actual resource usage remain verification inputs. Credential and resource identifiers belong in protected deployment configuration.

Before enabling a phase, run its [SRS acceptance cases](srs.md#acceptance-scenarios) and the [TDD preflight gates](tdd.md#preflight-and-release-gates), preserving separate local, staging and physical-device evidence. Recheck current pricing and limits when deployment decisions are made. If a selected capability fails a probe, record a superseding ADR and update affected contracts rather than silently changing the baseline.
