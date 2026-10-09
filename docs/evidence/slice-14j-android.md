# Slice 14j (Android, document text) local verification evidence

Date: 2026-10-09. Scope: the **Android half of the document-text part of slice 14j**: a strict client for the five shared device extraction contracts, a runner that leases a deferred PDF or image, reads its pages and submits the text in batches, platform adapters (PdfRenderer, image decoding, bundled ML Kit Latin recognition), WorkManager scheduling, battery and thermal limits, session-end cleanup, and clearer extraction states on the email screen. The Worker half is in the [14j backend note](slice-14j-backend.md). **Local inference readiness and claims (the `/device-work` inference part of 14j, LiteRT-LM) are not started**, so 14j is **not complete**. Status: **implemented and locally verified within the scope below** (JVM tests, Lint with warnings as errors, an unsigned release build and a Kotlin compile of the instrumentation tests). Nothing here is emulator, device or staging evidence: no PDF was rendered, no text layer was read and no recognition ran on Android. Nothing was pushed or deployed, and no secret, remote resource or real mail was used.

Starting point: HEAD `3d0bf2f`; the wire layer was committed earlier as `9bf1db4`.

## Result

Toolchain outside the repository under `/tmp/adminifender-toolchain-20261008` (JDK 21.0.12.1+1, Android SDK API 37, the committed Gradle 9.8.0 wrapper, AGP 9.4.1, private QEMU-wrapped AAPT2 because the host is ARM), run from `android/`, with the build's temporary directory on `/var/tmp` (see the second row below):

```
./gradlew --no-daemon --continue --max-workers=1 -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/var/tmp/...' \
  -Pkotlin.compiler.execution.strategy=in-process -Pandroid.aapt2FromMavenOverride=<toolchain>/emu/bin/aapt2 \
  :core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest :core:data:lintDebug :app:lintDebug \
  :core:data:assembleDebugAndroidTest :app:assembleDebug :app:assembleRelease
```

Final session: **exit 0, `BUILD SUCCESSFUL in 8m 33s`**, 223 actionable tasks (60 executed, 163 up-to-date). Counted from the JUnit XML reports: **446 JVM tests passed** (`:core:model` 27, `:core:data` 352, `:app` 67), zero failures, errors or skipped. `:core:data:lintDebug` and `:app:lintDebug` (warnings as errors) passed, `:core:data:assembleDebugAndroidTest` built the instrumentation APK (compiled only, not run), `:app:assembleDebug` passed, and `:app:assembleRelease` with `lintVitalRelease` built an unsigned release APK (not signed, not installed, not a Play artifact).

Sessions on the way, none of them the final result:

| Run | Outcome |
| --- | --- |
| Wire tests | `ExtractionWireFixturesTest` and `HttpExtractionApiTest` (14 tests) passed after one fix: a KDoc line containing `/*` opened a nested comment. |
| First full run | Failed with `Disk quota exceeded` while compiling and packaging: the shared `/tmp` tmpfs was nearly full. Environmental, not the code. The build's temporary directory was moved to `/var/tmp` for the later runs. |
| Second | Tests passed; Lint failed: the Android-version check needed `@ChecksSdkIntAtLeast`. |
| Third | Tests passed; Lint failed: `Bitmap.createBitmap` should use the KTX helper. Adding core-ktx to this module for one call would change the pinned dependency set, so a single-site `@SuppressLint("UseKtx")` with the reason in a comment was used. No Lint gate was disabled or weakened. |
| Fourth | The final session above. |

An earlier full run also had one intermittent failure in the **existing** `ExportRepositoryTest.signOutDuringADownloadStopsItAndWritesNothingBack` (the finished file was still there). Three isolated reruns passed, and reading the code showed a real window in the 14i download: a stop arriving after the last read but before the outcome is recorded left a complete file and a row still marked `downloading`. Fixed in `ExportRepository` (the final record is no longer cancellable, and a superseded verified file is discarded), with the regression test `aStopBetweenTheLastReadAndTheRecordRemovesTheFinishedFileAndSettlesTheRow`, which fails on the old code and passes now.

## What is implemented

- **Contract client** (`network/ExtractionApi.kt`): models, request builders and strict parsers for `extraction-claim-request`, `extraction-claim`, `extraction-batch`, `extraction-batch-result` and `extraction-outcome`, including the rules between fields the schemas state (a batch has `manifestHash` exactly when it is final; a result has `attachment` exactly when it published; a claim must state the contract's limits and one server hash per stored batch). `ExtractionWireFixturesTest` accounts for all 51 shared fixtures, and the builders' output is re-read by the same parsers. The HTTP client tells apart lease ended (`lease_lost`, `not_eligible`, `policy_changed`, `input_changed`, `generation_*`), deleted (410), refused (other 4xx) and unavailable (network, 429, 5xx, an answer that is not the contract's shape), and refuses an answer about another lease or batch.
- **Runner** (`extraction/DeviceExtractor.kt`): claims one file, streams the original into the app's no-backup directory while checking size and SHA-256 against the lease, opens it, reads from the checkpoint, and submits batches of at most 10 pages and 128 KiB. A PDF page's own text layer is used when it has at least 16 letters or digits; recognition runs only on other pages and only when the claim says `ocrAllowed` and the recognizer exists; under `ocrAllowed=false` a short text layer is kept and nothing is recognized. At most 100 pages are read; a longer document is published with `totalPages` and the email screen says only the first 100 pages were read. The final batch carries the digest of the server's earlier batch hashes. A heartbeat goes out every 3 minutes of work. An unanswered batch is repeated up to three times with pauses (a stored batch is answered as a replay). A lease the Worker ended stops the run without reporting anything. A file that cannot be read is reported: encrypted (`encrypted`), damaged or not decodable (`unsupported`), a page that throws or a refused batch (`failed`), an unready recognizer or a dropped download (`unavailable`, which returns the file to the queue). Battery below 20 % when not charging and thermal status severe stop the run between pages and hand the file back. Cancelling the run hands the file back within 5 seconds if the Worker answers, and the download is always removed. Only one run at a time.
- **Tests** (`DeviceExtractorTest`, 29 tests) run the runner against a fake Worker that enforces the Worker's batch rules (order, page span, size, manifest, recognition permission) and re-reads every request through the strict parser. Mutations that were reverted afterwards: a manifest built from nothing (4 tests failed), recognition without permission (1 failed), the download kept (4 failed).
- **Platform adapters** (`platform/DocumentReaders.kt`, `MlKitTextRecognizer.kt`): `PdfRenderer` for PDFs (a `SecurityException` from it is read as password-protected), `ImageDecoder` for images, sampled to at most 16 megapixels, software bitmaps; PDF pages are rendered for recognition at about 200 dpi within 4 megapixels (a page that would exceed the budget is rendered smaller; `DocumentReadersTest` pins the rules); the text layer is read with `PdfRenderer.Page.getTextContents()` on API 35 and later only. Recognition uses `com.google.mlkit:text-recognition` **16.0.1**, the latest release on Google Maven on 2026-10-09 (the repository's metadata was last updated 2024-08-07). Its models are inside the AAR, so recognition needs no download. Pinned in `gradle/libs.versions.toml`.
- **Scheduling and wiring**: a unique one-time work request (network and battery not low, 5-minute exponential backoff) after each finished sync and a 6-hour periodic request, started with sync; at most 10 files per run. Sign-out and a session that ends (signed out, refused or revoked) cancel the work, hand back the running file and remove the downloads (`SessionEnd`, tested); leftovers of a killed process are removed at start.
- **Email screen**: attachment lines now use the Worker's detail: reading, waiting for the phone, first 100 pages only, password-protected, set to no AI.

## Checks on the ML Kit dependency

Made on the built debug APK and its merged manifest, not on a device: the `arm64-v8a` native library `libmlkit_google_ocr_pipeline.so` has 16 KB ELF alignment (`0x4000`); the 32-bit ABIs have 4 KB, which the Play 16 KB requirement does not cover (64-bit only); the merged manifest gains only ML Kit's component discovery service and init provider, no new permission and no `com.google.mlkit.vision.DEPENDENCIES` metadata (the Play-services model-download hint). The debug APK is 87 MB because it carries four ABIs and the models; no ABI filter or size budget was set.

## Not run, and not claimed

- Anything on Android: no emulator, device, screenshot or TalkBack; `PdfRenderer`, the text layer API, `ImageDecoder` orientation handling, ML Kit recognition quality and speed, WorkManager constraints, thermal and battery reads, the memory use of a 4-megapixel render, and whether `getTextContents()` returns what is assumed are all unverified. No instrumentation test was added or run.
- FR-010 acceptance (searchable text from Latin JPEG/PNG/WebP and scanned PDF fixtures) and the 30-document accuracy corpus: not built.
- Local inference readiness and claims (LiteRT-LM, `/device-work`), and the "Resume next pages" continuation past 100 pages (the Worker has none either).
- A scanned PDF under `no_ai` (recognition not allowed) is published as `ready` with no text rather than a blocked state, because the Worker has no detail for it; the email screen cannot say why.
- A page with more than 128 KiB of text is cut to fit one batch; no page is sent in parts. The last run's result is not stored or shown; only the attachment's synced state is.
- Live Worker, staging, Play, CI.
