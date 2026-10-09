# Range downloads and the export list: local verification evidence

Date: 2026-10-09. Scope: the two Worker gaps listed after slice 14i: no byte-range support on downloads (an interrupted export restarts from zero) and no call that lists exports (an export made before a sign-out or on another device cannot be found). Status: **implemented and locally verified for the Worker**; **the phone uses neither yet**, and staging does not have this change.

## Result

Node 22.22.0, `backend/`: `npm run check` exit 0 (typecheck, **37 files, 933 tests**, bundle 1521.84 KiB / 320.12 KiB gzip). `python3 tools/check_docs.py` passes. No migration.

## What changed, with the tests that pin it

- `src/http/range.ts`: one `Range` header in the forms `bytes=a-b`, `bytes=a-` and `bytes=-n`. `GET /attachments/{id}/content` and `GET /exports/{id}/content` answer `206` with `Content-Range` and the slice's `Content-Length`, `416` with `Content-Range: bytes */size` when the start is at or past the end, and the whole object (`200`) for anything else: several ranges, another unit, nonsense, an inverted range, an empty `bytes=-`, or any `If-Range` (there is no validator to compare with, so the range is not honoured). Both responses now carry `Accept-Ranges: bytes`. A request without `Range` reads the object once, exactly as before; only a ranged request first looks up the size. `x-attachment-sha256` stays the whole file's hash on a slice. Tests: a middle slice, open-ended, suffix, a slice cut at the end, `416`, the ignored forms, `If-Range`, and for an export the first four bytes and the rest joined back into the identical ZIP, and a `404` for an unknown export before any range is considered.
- `GET /exports?limit=` (default 20, at most 50, no other parameter): the newest exports first, each exactly as `GET /exports/{id}` reports it. An export whose manifest cannot be read is listed as `failed` with `failureCode: export_manifest_unavailable` instead of failing the list, while the single-export call still answers with the server error. New shared schema `export-list` with three valid and six invalid fixtures, checked against the live answers and inside the Workers runtime. Tests: ordering, equality with the single-export answer for every entry, `limit` bounds and an unknown parameter (400), no session (401), and the broken-manifest case.
- Mutation checks, each reverted afterwards: ignoring every `Range` header (2 tests failed), listing oldest first (1 failed), letting one broken export fail the list (1 failed).

## Not covered, and not claimed

- **The phone does not use ranges.** Resuming an export download needs the Android side to reopen the destination for appending, re-read the part already written through the streaming verifier, and then ask for the rest; attachment downloads are sealed as one message, so they would need a segmented format first. Neither exists, and an interrupted download on the phone still restarts from the start.
- **The phone does not call `GET /exports`.** Adopting a listed export as a local record needs a decision on how it appears next to the owner's own requests; no Android parser for `export-list` exists yet.
- Real R2 range reads were exercised only against the local R2 binding, not against the staging bucket. The staging Worker does not yet run this change.
