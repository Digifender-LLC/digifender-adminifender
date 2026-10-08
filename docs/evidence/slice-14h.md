# Slice 14h local verification evidence

Date: 2026-10-08. Scope: grounded chat screen, answer provenance, chat history with cursor paging, shared chat fixtures, Room schema v4 and privacy rules. Status: **implemented and locally verified within the scope below**, including a successful final Gradle session (58210, `BUILD SUCCESSFUL`) that built the unsigned release APK. Nothing here is staging or device evidence.

The slice itself is described in the [roadmap](../roadmap.md); the contract is in the [LLD](../specifications/lld.md#chat). The code was uncommitted when the evidence was gathered (HEAD `f1b3bf8`) and is committed locally, not pushed, together with this note.

## Worker and contracts

Final code, `backend/`:

- Whole Worker suite (`npx vitest run`): **25 files, 368 tests passed, exit 0, 175.39 seconds**. The parent read the agent's terminal output directly. The Node version of that invocation was not recorded, so this note does not call the final full suite Node 22.22.0 verified.
- With Node 22.22.0 pinned by the parent, on the final code: `npm run typecheck` exit 0 and `npm run bundle` exit 0 (1401.64 KiB, 290.68 KiB gzip).
- These components were run separately; there was no single `npm run check` invocation on the final code.
- The 368 tests are the whole suite across 25 files, not the `chat.test.ts` count. They include history paging by cursor, answer provenance with `context`, bounded 50-turn history queries, `evidence_changed` failure cases, refused phone results and the shared chat wire fixtures.

Historical, not the final result: an earlier revision's full run (348 tests) had one SQLite `IOERR_WRITE` failure in concerns.test.ts. That test passed in isolation (19 concerns suite tests passed), and a full re-run with `TMPDIR=/var/tmp/adminifender-worker-14h-mXxiu6` passed all 348. The 348 figure belongs to that older revision and is superseded by the 368 above.

## Android, pinned toolchain

Toolchain, outside the repository under `/tmp/adminifender-toolchain-20261008`: JDK 21.0.12.1+1, Android SDK API 37.0 revision 2, build-tools 36.0.0, the committed Gradle wrapper with AGP 9.4.1 and AAPT2 artifact version 9.4.1-15978811 as in the [14g evidence](slice-14g.md), and a private QEMU-wrapped AAPT2 because the host is ARM. No toolchain version changed.

Final Gradle command (run from `android/`):

```
JAVA_HOME=/tmp/adminifender-toolchain-20261008/jdk-21.0.12.1+1 \
ANDROID_HOME=/tmp/adminifender-toolchain-20261008/android-sdk-java \
ANDROID_SDK_ROOT=/tmp/adminifender-toolchain-20261008/android-sdk-java \
ANDROID_USER_HOME=/tmp/adminifender-toolchain-20261008/android-user-home \
./gradlew --no-daemon --continue --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pandroid.aapt2FromMavenOverride=/tmp/adminifender-toolchain-20261008/emu/bin/aapt2 \
  :core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest \
  :core:data:lintDebug :core:data:assembleDebugAndroidTest \
  :app:assembleDebug :app:lintDebug :app:assembleRelease
```

Sessions, in order:

| Session | Outcome |
| --- | --- |
| 50505 | Failed at `:app:compileDebugKotlin`: a duplicate `Transient` and a smart cast on `citation.page` across a module boundary. `:app:compileDebugUnitTest` was not the failing task. 181 tests passed (27 model + 154 data); `:core:data:lintDebug` and the instrumentation APK passed. Duration 19m35s. |
| 56491 | Failed in `:app:lintDebug` on `PluralsCandidate` after 258 tests passed (27 model + 178 data + 53 app); `:core:data:lintDebug`, the debug app build and the instrumentation APK passed. Duration 8m2s. Fixed with a plurals resource; no lint gate was suppressed or weakened. |
| 58210 | Final session: **exit 0, `BUILD SUCCESSFUL in 28m`**, 222 actionable tasks (93 executed, 129 up-to-date). Every requested task succeeded, including `:app:assembleRelease` and its `lintVitalRelease`. The files compiled and tested were unchanged from the code in this commit. |

Results confirmed on the final code (session 58210 task results and the JUnit XML reports):

- **262 JVM tests passed** (`:core:model` 27, `:core:data` 182, `:app` 53), with zero errors, failures or skipped in the XML reports.
- `:core:data:lintDebug` and `:app:lintDebug` both passed.
- `:app:assembleDebug` and `:core:data:assembleDebugAndroidTest` both passed. The second only builds the data module's instrumentation APK; no app-module instrumentation APK is claimed, and no instrumentation test was executed.
- `:app:assembleRelease` (with `lintVitalRelease`) passed and built the **unsigned** release APK. It is not signed, not installed anywhere and not a Play artifact.
- `:core:model` is a JVM module and is not Lint-analyzed, as in slice 14g.

Room schema: `core/data/schemas/.../4.json` was regenerated with `olderCursor` and `historyLoaded` in `chat_threads` in place of `olderAvailable`. Version 4 is uncommitted and has never been released, so no 4-to-5 migration is needed. The existing migration test `RoomChatCacheTest.version3MigratesToVersion4AndAsksForAFullDownload` was compiled with the data module's instrumentation APK but **never executed**. Schemas 1 to 3 are kept.

## Privacy and provenance review

Review round 1: 11 findings fixed (answer provenance with all context emails and their privacy; target eligibility; later history; heuristics removed; route exit; atomic draft restore; as-of citation dates; honest local wait; sign-out disclosure with bounded cancel; strict shared contracts).

Review round 2: **three** actionable findings fixed: a once-per-process damaged-answer check; a bind-limit fix for history evidence (batched so a statement binds at most 90 values, not 90 turns); and an explicit `nextBefore` cursor independent of the phone clock. Cloud and device privacy race cases and 130-turn fixtures with skewed clocks and identical timestamps were added with them. History is the latest 50 turns, oldest first.

## Not run

- No instrumentation test was executed, including `RoomChatCacheTest` and its v3-to-v4 migration test. They are compiled only.
- No emulator, screenshots, TalkBack, physical device, staging, Play-installed build, live Worker, model, FCM or passkey evidence.
- No services endpoint exists. Services reach the phone only through `concerns.serviceId` on synced concerns, and the Chat tab offers a service only from those; this is a limitation, not an acceptance claim.
- Exact-passage in-email navigation is not implemented; the inline excerpt with a link to the retained email is what FR-026 asks for.
- Conversation deletion from the phone is not implemented.
- The CI result for this revision was not inspected.
