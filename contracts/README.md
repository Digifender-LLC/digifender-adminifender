# Shared contracts

Versioned JSON Schemas (draft 2020-12) and synthetic fixtures shared by the Worker and, later, the Android app. The [LLD](../docs/specifications/lld.md) is the source for every shape here.

- `schema/v1/` — one schema per contract; `common.schema.json` holds shared definitions.
- `fixtures/v1/<schema>/valid|invalid/` — each valid fixture must pass and each invalid fixture must fail its schema.

`npm test` in `backend/` checks every fixture against its schema, and runs the Worker's own parsers against the same fixtures where a parser exists. Fixtures are synthetic; never add real mail, tokens or report payloads.

Status: **locally verified** for the schemas present. The Android JVM tests parse `fixtures/v1/sync-changes/` and `fixtures/v1/briefing/` with the app's DTOs, and Worker tests check that live sync projections and the live briefing carry the same fields.

The chat wire shapes (`chat-thread`, `chat-turn`, `chat-history`, `chat-answer`) are shared the same way: the Worker's tests compare live responses with the valid fixtures' fields, and the Android JVM tests (`ChatWireFixturesTest`) account for every valid and invalid fixture. `chat-turn` is the answer's wire shape for the phone: it carries the answer, claims, gaps, proposals and the evidence provenance `context` (every email the model was given and the privacy each had; `null` when there is no answer to show), plus the `withheld` and `invalidated` flags. `chat-answer` is the validated model output and has no `context`. `chat-history` carries `nextBefore`, the Worker's cursor for the next earlier page (null exactly when `hasMore` is false); its invalid fixtures cover the contradictory combinations. Status: **locally verified**. The whole Worker suite passed (25 files, 368 tests) and the Android JVM tests, including `ChatWireFixturesTest`, passed on the pinned toolchain (262 tests; see [slice 14h evidence](../docs/evidence/slice-14h.md)).
