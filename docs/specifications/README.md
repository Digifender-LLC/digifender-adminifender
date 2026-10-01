# Adminifender specification guide

Version 1.0 — October 1, 2026. This suite specifies the complete accepted personal Android/backend product in three delivery phases. It is a selected design baseline, not an implemented application. Read the owner decisions and phase definitions in [Baseline](baseline.md), then follow the appropriate document below.

| Document | Owns | Intended use |
| --- | --- | --- |
| [BRD](brd.md) | Personal outcomes, constraints, cost objectives and success measures | Understand why the app exists. |
| [PRD](prd.md) | Product journeys, feature coverage and phase goals | Understand what to deliver and when. |
| [FRD](frd.md) | Detailed functional workflows and exceptions | Implement observable behavior. |
| [SRS](srs.md) | Normative requirements, measurable criteria and the single traceability matrix | Determine acceptance and track scope. |
| [HLD](hld.md) | Context, topology, trust boundaries and major flows | Understand the system structure. |
| [SDD](sdd.md) | Subsystem interaction, state ownership and recovery | Coordinate the app/backend/integrations. |
| [TDD](tdd.md) | Concrete technical stack, environments, delivery, capacity/cost and validation | Plan engineering and operations. TDD means Technical Design Document. |
| [LLD](lld.md) | SQL schema contracts, APIs, DTOs, state machines and algorithms | Implement precise component contracts. |
| [ADR collection](adr/README.md) | Ten architecture decisions, alternatives and consequences | Understand choices and supersede them deliberately. |

## Supporting evidence

[Accepted notes](../product-notes.md) preserve owner intent. [Whumpo research](../feature-suggestions.md) attributes existing versus needed integration capabilities. [Sources](sources.md) lists official platform documentation checked during authoring. [Validation](validation.md) records actual documentation checks; it does not certify application behavior.

Authority: owner decisions → SRS requirements → baseline shared defaults → LLD interface definitions and FRD observable behavior → supporting design/rationale. A contradiction must be reconciled, not resolved by choosing whichever page is convenient. Configurable numeric defaults are selected engineering choices; account identifiers, domains, credentials and measured device performance are activation inputs.

## Delivery and change process

P1 builds the complete personal email workflow with both inference adapters. P2 adds trusted emailed operational reporting and Whumpo context. P3 activates individually tested additional sources and reviewed service actions. The full P3 design exists now; phase labels do not make accepted capabilities optional.

Before implementation, resolve the enumerated deployment/device verification inputs, pin library/model artifacts and run bounded platform probes. Do not provision resources merely because an ADR selects a product. Implement each vertical slice with its SRS acceptance cases, then record local, connected and physical-device evidence separately.

Update the SRS requirement and traceability row for behavioral changes; update shared constants in baseline and concrete contracts in LLD. Architectural reversals receive a new ADR that supersedes the previous record. Keep requirement IDs stable and record removals rather than reusing IDs. Re-run document link/anchor, requirement-coverage and contract checks after substantive edits.
