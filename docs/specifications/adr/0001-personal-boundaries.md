# ADR-0001 — Independent personal application and modular backend

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

The owner wants a fluid Kotlin/Compose application for personal attention and Cloudflare operations. Whumpo has useful operational concepts and a planned customer Android app, but is a separate product with customer privacy, identity and operator boundaries. Adminifender should evolve without importing Whumpo's customer tenancy or backend assumptions.

## Decision

Build a single-owner native Android application and an independently deployed TypeScript Cloudflare Worker application. Keep HTTP, email, queue and scheduled entry points in that deployment, backed by modules for identity, capture, attention, inference, sync, notifications and integrations. Use environment-isolated resources. Choose the services and device baseline specified in the [baseline](../baseline.md); do not add Durable Objects, Workflows, AI Gateway or an external inference vendor to the initial architecture.

Whumpo is an external evidence producer and later scoped adapter target. Adminifender neither reuses customer sessions nor receives broad Whumpo database credentials. P1/P2 ingestion remains email-only; P3 connectors are separately activated.

## Alternatives rejected

- **Extend Whumpo's planned Android app:** conflates personal operator access with customer-owner capabilities and assumes unavailable native code/API foundations.
- **Microservice per module:** adds deployment, credential, network and recovery cost before this single-owner workload justifies it.
- **General plugin or multi-tenant platform:** expands authorization and configuration surface without a stated requirement.

## Consequences and verification

One backend deployment has a shared failure/release boundary, mitigated by durable work and separately testable modules. New capabilities must respect module ownership rather than bypass policy through helper calls. Whumpo integration work needs its own implementation and acceptance; an Adminifender design is not proof the interface exists. Verify independent identity, environment isolation and AI-independent archive use through the SRS acceptance suite. See [HLD](../hld.md) and [SDD](../sdd.md).
