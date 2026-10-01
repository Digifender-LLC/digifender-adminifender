---
name: adminifender-review
description: Review an Adminifender diff against requirements and trust boundaries.
---

# Review

Read `AGENTS.md`, the relevant SRS acceptance cases and contracts, and the full intended diff. Review read-only in a fresh session for independence; use a subagent only with explicit user delegation. Do not read secrets or publish comments.

Prioritize source retention and deletion, owner authorization, exact-action review, replay/uncertain effects, local-only inference, cost reservations, sync conflicts, notification privacy, migration compatibility and accessibility. Report concrete blocking/nonblocking findings with file/symbol and failure scenario, missing evidence and the smallest useful checks. Do not substitute style preferences for correctness findings.
