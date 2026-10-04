# Shared contracts

Versioned JSON Schemas (draft 2020-12) and synthetic fixtures shared by the Worker and, later, the Android app. The [LLD](../docs/specifications/lld.md) is the source for every shape here.

- `schema/v1/` — one schema per contract; `common.schema.json` holds shared definitions.
- `fixtures/v1/<schema>/valid|invalid/` — each valid fixture must pass and each invalid fixture must fail its schema.

`npm test` in `backend/` checks every fixture against its schema, and runs the Worker's own parsers against the same fixtures where a parser exists. Fixtures are synthetic; never add real mail, tokens or report payloads.

Status: **locally verified** for the schemas present. The Android JVM tests parse `fixtures/v1/sync-changes/` with the app's DTOs, and a Worker test checks that live sync projections carry the same fields.
