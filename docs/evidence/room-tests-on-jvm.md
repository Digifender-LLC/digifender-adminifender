# Room and migration tests on the JVM: local verification evidence

Date: 2026-10-09. Scope: the five Room test classes (32 tests) and every Room schema migration from version 1 to 5, executed for the first time. Until now these tests were only compiled (CI built the instrumentation APK; nothing ran them). Status: **locally verified on the JVM under Robolectric 4.17 and API 35**. Not run on a device or emulator, and not run by CI (see below).

## What was done

- The Room tests (`RoomArchiveCacheTest`, `RoomBriefingCacheTest`, `RoomChatCacheTest`, `RoomFilesReviewsTest`, `RoomLocalStoreTest`) and `MigrationHelpers` moved to `android/core/data/src/sharedTest`, which is part of both `androidTest` (device) and `test` (JVM, Robolectric). The Keystore tests stay device-only. `robolectric.properties` selects API 35 (the committed note: Android 17 application setup does not run on JDK 21 in Robolectric yet). The committed schema JSONs are made available to `MigrationTestHelper` as test assets. Robolectric 4.17 is the latest release on Maven Central (metadata updated 2026-09-10, checked 2026-10-09).
- Robolectric has no native runtime for Linux aarch64 ("not supported on Linux (aarch64)"; all 32 tests failed with that on the first attempt). On an ARM host the build now skips these tests with a printed message, and x86_64 hosts run them. To run them here anyway, an x86_64 Temurin 21.0.12.1 JDK (SHA-256 `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`, equal to Adoptium's published value) was started through the user-mode QEMU already in the toolchain, via `-Padminifender.testJava=<home>/bin/java`; method in [verification.md](../verification.md).

## Result

Final session, `./gradlew :core:data:testDebugUnitTest --tests '*Room*' -Padminifender.testJava=/var/tmp/jdk-x64-home/bin/java`, pinned toolchain: **exit 0, `BUILD SUCCESSFUL in 10m 54s`**. JUnit XML: **32 tests, 0 failures, 0 errors, 0 skipped** (`RoomArchiveCacheTest` 4, `RoomBriefingCacheTest` 4, `RoomChatCacheTest` 16, `RoomFilesReviewsTest` 7, `RoomLocalStoreTest` 1).

The migration tests among them, each against the committed schema export: `version1MigratesToVersion2WithTheCacheIntact`, `version2MigratesToVersion3WithTheCacheIntact`, `version3MigratesToVersion4AndAsksForAFullDownload`, `version4MigratesToVersion5KeepingEditsDraftsAndQuestions`, and `anInventoryCachedBeforeVersion5HasNoChecksumAndIsNeverDownloadedUnverified`. The normal ARM build (`:core:data:testDebugUnitTest`, `:core:data:assembleDebugAndroidTest`, `:core:data:lintDebug`) also passed with the shared source set and the skip.

## What the first run found

Three of the 32 failed, all mistakes in the tests, none in the cache or the migrations:

- `RoomArchiveCacheTest.cachedSearchMatchesEveryTermLiterallyInTheSelectedView`: every fixture email shared the sender `billing@water.example`, so the second email matched "water" and "bill" through its sender and the expectation `[a]` was wrong (the cache matches each term against subject, sender or text). The second email now has its own sender, and the sender-filter assertions say so.
- `RoomBriefingCacheTest.clearingAllTablesForSignOutRemovesTheBriefing` and `RoomChatCacheTest.nothingIsStoredForAConversationThatSignOutCleared`: `db.clearAllTables()` called on the test thread, which is the main thread under Robolectric, where Room forbids it. Production's `LocalCache.clear()` runs it on the IO dispatcher; the tests now do too.

Several intermediate attempts failed for environment reasons and are not results: the x86 JDK shim needed a `<home>/bin/java` layout, and Robolectric's native library extraction hit the shared `/tmp` quota until the test JVM's temp directory was moved.

## Not covered

- **CI did not run them.** The pull request's checks were refused by GitHub ("recent account payments have failed or your spending limit needs to be increased"), so there is no x86 CI result for this change. The result above is local, under emulation.
- Not a device: Robolectric's SQLite and Room are real, but the Android framework around them is simulated; the instrumentation run on API 31 and the owner's API remains open. The Keystore token vault and file-key tests (`KeystoreTokenVaultTest`, `KeystoreFileKeysTest`) are still compiled only.
- Only these 32 tests. Compose screens, TalkBack and screenshots were not touched.
