# Export download resume and export discovery on the phone: local verification evidence

Date: 2026-10-09. Scope: the phone side of the two gaps left after the [Worker range and list change](range-downloads-and-export-list.md). Status: **implemented and locally verified (JVM tests, Lint, builds)**; no emulator, device or staging run. Staging runs the Worker side only for the range/list change; this branch is stacked on it.

## Result

Pinned toolchain (JDK 21, Gradle 9.8.0), one invocation: `:core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest :core:data:lintDebug :app:lintDebug :app:assembleStaging :app:assembleDebug` exit 0, `BUILD SUCCESSFUL in 41m 56s`. Test XML reports: model 27, data 371, app 69, **467 tests, 0 failures or errors**. Unsigned release was built by the same invocation's dependencies (`app-release-unsigned.apk` present), not signed or installed.

## What changed

- **Resume inside one download.** When the connection drops while an export ZIP is being received, the repository reopens the stream with a `Range` request from the number of bytes already received (`openContentFrom`), at most 3 times with a 1 s pause, and keeps feeding the same streaming verifier, so size, entry checks and SHA-256 are still checked over the whole ZIP. A server that answers a range with the whole object, a wrong `Content-Range`, or no range support ends the attempt as an interrupted download (`ranges_unsupported` or the specific code); nothing is claimed verified. A download that is interrupted after the resumes are used up, or after the app restarts, still starts from zero: resuming across restarts is **not** implemented.
- **Export discovery.** `GET /exports?limit=50` is parsed by a strict reader of the shared `export-list` schema (all 9 shared fixtures accounted for) and `ExportRepository.discover()` keeps every listed export the phone has no record of as a *created* export (new local ID, a key that was never sent, `download = NONE`), so it can be read back and downloaded like any other. Nothing is created or changed on the Worker. Exports without a recorded scope (legacy) are not adopted; unsettled ones get polling; a repeat in the list is adopted once (the schema cannot say "each ID once"); the sign-in epoch fences the write so a list that arrives after sign-out adds nothing. Storage shows a "Find exports on the server" button with outcome text (found/none new/sign in/could not reach).

## Tests and mutation checks

- `ExportRepositoryTest` 31, `HttpReviewExportApiTest` 16, `ReviewExportWireFixturesTest` 9 pass, including the new discovery and wire tests (adoption without a request, already-known and repeated IDs, no-scope refusal, failed list, late list after sign-out, request line/limit/auth header, 401/503/malformed classification).
- Four deliberate mutations of `discover()`, each reverted afterwards (file compared byte for byte): adopting scope-less exports, removing the known-ID check, removing the epoch fence, polling every adopted export. Each failed exactly the test written for it.

## Not covered, and not claimed

- No test of `StorageViewModel.discover` or the button itself (Compose/ViewModel layer compiled and Lint-checked only); no screenshots or TalkBack.
- Resume was exercised against MockWebServer and scripted streams, not against the staging R2 bucket or a real flaky network; the staging Worker does not run the range/list change yet.
- No resume across process death, and none for attachment downloads (sealed as one message).
