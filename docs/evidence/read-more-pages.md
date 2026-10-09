# Read more pages: local verification evidence

Date: 2026-10-09. Scope: reading a document past the first 100 pages (the continuation that slice 14j left open): Worker (`POST /attachments/{id}/read-more`, resumed generations), shared contracts, the phone's reader and a "Read more pages" button. Status: **implemented and locally verified within the scope below**; nothing ran on a phone, and staging was **not** redeployed with this change (see the end).

Contract text: [LLD, device extraction](../specifications/lld.md#text-extraction-and-ocr). Starting point: `main` at `0442af7` (staging deployment of the reconciled branch, migration 15).

## Result

- **Worker**, Node 22.22.0, `backend/`: `npm run check` exit 0 (typecheck, **37 files, 929 tests**, bundle 1522.61 KiB / 320.00 KiB gzip). Migration `0016_read_more_pages.sql`; `controlMigration` is 16.
- **Android**, pinned toolchain, one session: exit 0, `BUILD SUCCESSFUL in 58m 1s`, 219 tasks, for `:core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest :core:data:lintDebug :app:lintDebug :app:assembleStaging :app:assembleRelease`; **454 JVM tests** (27, 358, 69), no failures; both Lint tasks passed. (That machine was shared with other builds, which is why it took an hour.)
- `python3 tools/check_docs.py` passes.

## What changed, with the tests that pin it

- **Owner request**: `POST /attachments/{id}/read-more` moves an attachment that is `ready` with detail `limit` to `deferred: device_unavailable` with `resume_page = pages_read + 1`; the published text and its search rows are untouched. Refused with `nothing_to_read` (never read, already complete, already requested), `not_eligible` (quarantined) and 404 (unknown or deleted). Tests: refusals, and that searching still finds the published text while the request waits.
- **Resumed generation**: the next claim returns `startPage`, `nextBatchOrdinal`, `nextPage` and `batchHashes` continuing after the published batches, in a new generation `<generation>:r<startPage>` that is a server-side copy of the published one (chunks, search rows and batch records), made before the lease exists and only once. Tests: the copy has exactly the published generation's chunk count; an expired resumed lease is continued without copying again and without duplicating text; a second request continues from the page reached (`:r201`).
- **Limits**: a lease reads at most 100 pages from its `startPage` and the last of them closes the read; a document is read up to 1,000 pages. Tests cross the cap (`beyond_lease_pages`) and the page that must close the read (`required_at_page_limit`).
- **Publication**: switches `active_generation` to the new generation and records `pages_read`/`pages_total`; the old generation goes after a day while the new one stays (test with the cleanup pass). A resumed read that fails (`encrypted`, `unsupported`, `failed`) restores `ready: limit` and keeps the text; `unavailable` stays queued from the same page.
- **Contracts**: `extraction-claim` gains required `startPage`; pages, ordinals and hash lists go up to 1,000; fixtures `work-read-more` and `resumed-read-batch` added, `page-beyond-limit` moved to page 1001. The Android parsers account for every fixture.
- **Phone**: the runner reads `startPage` to `startPage + 99` (a test reads pages 101 to 200 of a 250-page document and ends `limited` again; another reaches the end at page 130 and is not limited), `ReadMoreRepository` asks for a sync and schedules the reader when the Worker queued the request, and the attachment line shows "Read more pages" only for a `ready: limit` attachment. Notices for queued, nothing to read, email unavailable, signed out and no answer are tested on the view model.
- Mutation checks, each reverted afterwards: copying from a generation that does not exist (3 Worker tests failed), removing the per-lease cap (1 failed), publication forgetting the page reached (8 failed). A first attempt at the cap check was wrong: a hundred-page jump is refused by the existing ten-pages-per-batch rule before the cap is reached, so the first version of the test never exercised the cap and a mutation appeared "caught" only because the test itself was failing on the unmutated code. The test now stops at page 194 and asks for 195 to 204; unmutated it passes and mutated it fails.

## Not covered, and not claimed

- No run on a phone or emulator: `PdfRenderer` reading of pages 101 onward, the button, WorkManager scheduling.
- The reader shows a hundred more pages per request only; there is no "read everything" shortcut, and pages past 1,000 are never read (the line keeps the "first pages only" text).
- The staging Worker still runs the previous version (migration 15). Applying `0016` and deploying are separate steps, taken after this is merged.
- The attachment line does not show which pages were read, because the synced attachment record carries only state and detail.
