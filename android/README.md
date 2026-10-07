# Android app

Kotlin and Jetpack Compose app for the owner's phone ([TDD](../docs/specifications/tdd.md#android)). Status per slice is in the [roadmap](../docs/roadmap.md).

- `core/model`: pure domain types and policy (concern controls, optimistic edits, outbox decisions, push payload parsing). JVM tests only.
- `core/data`: Room cache (schemas exported to `core/data/schemas`), the OkHttp API client and the sync engine with its offline outbox. The engine depends only on interfaces and is tested on the JVM with fakes and the shared `contracts/` sync fixture; the Room store has instrumentation tests that need an emulator or device.
- `core/data` `session/`: passkey sign-in, recent-auth confirmation and token refresh, behind interfaces tested on the JVM. `platform/` holds the Android adapters: the Credential Manager passkey prompt and the Keystore-sealed token vault (instrumentation test only).
- `core/data` `chat/`: durable draft/request identities in a separate private Room database; server answers are revalidated instead of retained as authoritative offline replies. `devicework/` orchestrates readiness and fenced leases, but the current runtime reports `model_not_configured`; a real model/tokenizer and OCR acceptance are pending.
- `core/data` `repository/`: `ConcernRepository` combines the Room cache with queued edits for the UI and sends owner actions through the outbox. `ArchiveRepository` lists synced sources, runs exact search with a labelled cached-only fallback and keeps opened source text for offline reading. `platform/` also holds the WorkManager sync worker and scheduler.
- `app`: hand-wired `AppContainer`, view models and Compose screens for sign-in, the inbox (open, waiting, snoozed and resolved), concern detail with conflict resolution and its source emails, the archive (archive and quarantine views, search, source detail with attachment inventory and linked items), and settings with sync and sign-out. The Worker origin comes from `-Padminifender.apiBaseUrl` and defaults to an unroutable host. `push/` holds the FCM receiver, notification channels and the registration worker. The current integration adds briefing and grounded-chat tabs, contextual citations, private attachment downloads, exact deletion review and exports. Settings also exposes pending edits whose concern was removed.

Toolchain: the committed Gradle wrapper (checksum-pinned), JDK 21, and the versions in `gradle/libs.versions.toml`. Builds need an Android SDK with platform 37 (`ANDROID_HOME` or `local.properties`, which is not committed).

```sh
./gradlew :core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest :core:data:lintDebug :core:data:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

The release build is unsigned; signing and Play delivery belong to slice 15. Push needs `app/google-services.json` for the `adminifend` Firebase project, which is never committed; the Google services plugin is applied only when that file exists.

Room v3 retains local source suppressions and concern revision fences so an older sync page cannot restore deleted source text or concern titles. Pending edits remain explicit conflicts and are accessible from Settings. The sync cursor advances only with feed pages; sign-out clears both Room databases/private files and waits through any in-flight sync commit. These invariants have JVM and Room instrumentation coverage; consult the roadmap for which checks have actually run.

The 2026-10-07 combined checkpoint passed 218 JVM tests and debug/instrumentation APK compilation. Full integrated lint/unsigned release and instrumentation execution remain pending; see [verification](../docs/verification.md) for the exact scope and evidence.
