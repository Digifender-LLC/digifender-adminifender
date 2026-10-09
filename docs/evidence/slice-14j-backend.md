# Slice 14j backend local verification evidence

Date: 2026-10-09. Scope: the **Worker and shared-contract half** of slice 14j only: leases, immutable batches and fenced publication for text that the phone extracts from PDFs and images (FR-010). No Android code was written or run, so nothing here is evidence for PDF text, OCR or any device behaviour, and 14j is **not complete**. Status: **implemented and locally verified within the scope below**. Nothing was deployed or pushed, and no remote resource, secret or real mail was used. The contract text is in the [LLD](../specifications/lld.md#text-extraction-and-ocr) and the schemas in [contracts](../../contracts/README.md).

Starting point: HEAD `e502a07`, whose Worker suite passed 33 files and 722 tests.

## Result

Node 22.22.0 (`/tmp/adminifender-toolchain-20261008/node22/...`), `TMPDIR=/var/tmp/adminifender-worker-14i`, from `backend/`, one invocation on the final code (log `check-14j.log`, ends with `exit=0`):

```
npm run check      # typecheck, vitest run, wrangler deploy --dry-run
```

- Exit code **0**. `vitest run`: **34 files, 877 tests passed** (722 before; 15 in the new `extraction-device.test.ts`, and the five new contracts add 140 fixture tests across the fixture, Workers-runtime and request-validator suites).
- Bundle dry run: 1452.17 KiB, 302.37 KiB gzip.
- `python3 tools/check_docs.py` and `git diff --check` are reported in the handoff.
- Not run: any Android check, any staging, live Cloudflare D1/R2/Queue or device run. Everything is synthetic local evidence from Miniflare-backed bindings and an in-test clock.

## What changed, with the tests that pin it

- **Lease and claim** (`src/extraction/device.ts`, migration `0015_device_extraction.sql`): `POST /device-work/extraction/claim` hands out one deferred PDF or image of a `ready`, `normal` email that the phone can process, and moves the attachment to `extracting` with a change record. One live lease per attachment (unique index) and per device. Server extraction now records `device_kind` when it defers a file; attachments deferred before the migration have none and are never handed out. Tests: kinds recorded, one file at a time (`busy`), capability filtering (`no_work`, `no_capability`), the PDF offered for its text layer only under `no_ai` and the image withheld.
- **Immutable batches**: numbered, at most 10 pages and 128 KiB, parts of a page joined by one newline, text normalized before hashing. Same ordinal and content is `replayed`; different content is `409 batch_conflict`; a skipped number is `409 batch_out_of_order`. The server returns its own `batchHash`, and the final batch carries a `manifestHash` over the earlier ones, so the phone never reproduces the server's hashing. Tests: replay, conflict, skip, nine ordering/size/shape refusals, nothing written to the archive by refused batches.
- **Fenced publication**: chunks go to the archive under an unpublished generation; before publication the Worker recomputes every batch's digest from the archive, then one control batch rechecks lease, attachment revision and state, email state and revision, policy revision and batch contiguity, and sets `ready` and `active_generation`. Tests: nothing searchable or readable before the last batch (search, chunk endpoint, state), pages, methods and offsets after it, change record, lease closed, file not offered again; a wrong manifest leaves everything unpublished; a tampered archive row is refused with the generation discarded and the file back in the queue; a document longer than the phone read publishes with detail `limit`, and the 100-page boundary requires the final batch.
- **Lifecycle**: heartbeat renews; an expired lease is closed by the next claim or the scheduled pass and the file returns; a new lease continues the generation from the stored batches; quarantine ends live leases and returns the files at once (not only at the next batch); deletion makes a late batch `410 deleted` and the cleanup job removes lease and batch rows and the chunks; a policy change ends the lease (`409 policy_changed`) and starts a new generation; under `no_ai` recognition is refused (`409 ocr_not_allowed`) while a native text layer is accepted; the phone can hand a file back (`unavailable`) or end it (`encrypted`, `failed`); generations never published are removed after a day and the published one is kept.
- **Shared contracts**: five schemas with 51 fixtures (valid and invalid). The Worker's real requests and answers are checked against them in the feature tests, and the Worker's request validators are checked against the same fixtures. This caught one real drift: a replayed answer leaked the internal `batchHashes` list, which the schema refuses.
- Mutation checks, each reverted afterwards: skipping the archive digest comparison (1 test failed), skipping the policy check (1 failed), skipping the recognition check (1 failed), never returning orphaned files (6 failed). Removing the batch-contiguity assertion from the publishing batch failed nothing: it is redundant with the primary key and the earlier ordinal check, kept as defence in depth, and not pinned by a test.

## Defects found on the way

- Orphan detection first keyed on `job_id IS NULL`; a deferred attachment keeps its earlier job ID, so an expired device lease never returned its file. Found by the expiry tests; the check now asks whether a lease was granted at the attachment's current revision, which server extraction can never produce.
- A manifest over all batches including the final one was impossible for the phone to compute before the server answered; it now covers the batches before the final one.

## Not covered, and not claimed

- Any Android component: the PDF/OCR worker, ML Kit, rendering limits, the download, the `/device-work` client and local inference readiness (the rest of 14j).
- Real Workers, D1 limits and latency: the statements-per-batch slicing (50) and the size of a ten-page batch were exercised only locally.
- A second device using another device's lease (rejected by the `device_id` comparison; no test enrols a second device).
- Continuation past 100 pages ("Resume next pages"): a longer document is published up to page 100 with detail `limit`.
- `confidence` is accepted and hashed, not stored. The heartbeat and outcome answers have no shared schema. The claim schema cannot state that `batchHashes` has one entry per `nextBatchOrdinal`.
- Quarantine returns a file to `deferred: device_unavailable` rather than `stored`; the earlier server-job reset is unchanged.
