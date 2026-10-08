# Slice 14g local verification evidence

Date: 2026-10-08. Scope: wait and due-date forms, the Briefing tab, Room schema v3 and the Worker briefing coverage bound. Status: **locally verified** within the scope below. Nothing here is staging or device evidence. The slice itself is described in the [roadmap](../roadmap.md); the contract is in the [LLD](../specifications/lld.md#briefings).

## Worker and contracts

Node 22.22.0, in `backend/` (run from the fixes task):

- TypeScript typecheck passed.
- Vitest: 25 files, 304 tests passed, including the shared briefing schema and fixtures and the bounded-coverage cases (`hasMore` and cursor behavior is specified in the LLD "Bounded coverage" paragraph).
- `npm run bundle` dry run passed.
- An earlier parallel `npm run check` failed only on a Vitest pool startup timeout under memory pressure; it is not counted.

## Android, pinned toolchain

Toolchain (outside the repository, under `/tmp/adminifender-toolchain-20261008`): JDK 21.0.12.1+1 (aarch64), Android SDK platform API 37.0 revision 2 with build-tools 36, the committed Gradle wrapper, and the exact Maven AAPT2 the build pins, run through a private QEMU wrapper because the host is ARM. The owner accepted the Google SDK license for this local verification. No pinned version, `warningsAsErrors`, security or API Lint check was changed.

Flags: `--no-daemon --continue --max-workers=1 -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process -Pandroid.aapt2FromMavenOverride=<wrapper>`, with `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` and `ANDROID_USER_HOME` pointing into the toolchain directory.

| Invocation | Tasks | Result |
| --- | --- | --- |
| 1 | `:core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | Exit 1 at `app compileDebugUnitTestKotlin`: serialization dependency missing. Model and data tasks passed: 124 tests, 0 failures (27 model, 97 data). App tests: 38 passed before compile failure. Room generated `core/data/schemas/.../3.json` during this invocation. |
| 2 | `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :core:data:compileDebugAndroidTestKotlin :app:assembleRelease` | Exit 1 after adding `testImplementation(libs.serialization.json)`. App JVM tests: 38 passed. Android Lint: 7 findings reported. Debug APK build and unsigned release APK (build 18132) succeeded. Compose and Room compilation succeeded; androidTest Kotlin compile succeeded. |
| 3 | `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | Exit 0, BUILD SUCCESSFUL in 6m 16s, on the latest code (build 59307). App JVM tests: 38 passed. `lint-results-debug.txt` reads "No issues found." Debug APK built successfully. |

Lint fixes made between invocation 2 and invocation 3:

- `Forms.datePreset` called an API 34 method; it now converts through `Instant.atZone(...).toLocalDate()`, which is API 31 compatible.
- A literal ASCII string that tripped `TypographyDashes` carries a per-string ignore.
- `android/app/lint.xml` ignores only the version-freshness checks (`GradleDependency`, `NewerVersionAvailable`) in `gradle/libs.versions.toml` and `AndroidGradlePluginVersion` in the wrapper properties, which pin versions on purpose. Every other check, including security and API checks, stays on.

Reported honestly:

- The unsigned release APK (build 18132) was built in invocation 2, **before** the final date and lint fixes. It was not rebuilt afterward, so do not cite it as a build of the final code. The debug APK and tests/Lint results are from invocation 3.
- The `:core:model` JVM module is not analyzed by Lint (AGP warns about this).
- Two native libraries are packaged unstripped. No tool versions changed.
- The `:core:data:lintDebug` and `:core:data:assembleDebugAndroidTest` tasks were not run locally (only `:core:data:compileDebugAndroidTestKotlin` verified Kotlin compilation). Instrumentation APK assembly and execution are not part of this verification.
- The CI command in [verification](../verification.md) differs from these invocations (it includes `:core:data:lintDebug` and `:core:data:assembleDebugAndroidTest`). Those exact tasks were not run together on the final code; check the Actions result.

## Not run

- `RoomBriefingCacheTest` and the other Room store, migration (including 2 to 3) and Keystore vault tests were compiled (androidTest Kotlin compile in run 3) but never executed.
- No emulator, physical device, screenshot or TalkBack inspection, live FCM, live passkey, or CI run inspection.
- Nothing on staging; no Play-installed build.
