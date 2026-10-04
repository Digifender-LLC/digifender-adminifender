# Android app

Kotlin and Jetpack Compose app for the owner's phone ([TDD](../docs/specifications/tdd.md#android)). Status per slice is in the [roadmap](../docs/roadmap.md).

- `core/model`: pure domain types and policy (concern controls, optimistic edits, outbox decisions, push payload parsing). JVM tests only.
- `core/data`: Room cache (schemas exported to `core/data/schemas`), the OkHttp API client and the sync engine with its offline outbox. The engine depends only on interfaces and is tested on the JVM with fakes and the shared `contracts/` sync fixture; the Room store has instrumentation tests that need an emulator or device.
- `core/data` `session/`: passkey sign-in, recent-auth confirmation and token refresh, behind interfaces tested on the JVM. `platform/` holds the Android adapters: the Credential Manager passkey prompt and the Keystore-sealed token vault (instrumentation test only).
- `core/data` `repository/`: `ConcernRepository` combines the Room cache with queued edits for the UI and sends owner actions through the outbox. `ArchiveRepository` lists synced sources, runs exact search with a labelled cached-only fallback and keeps opened source text for offline reading. `platform/` also holds the WorkManager sync worker and scheduler.
- `app`: hand-wired `AppContainer`, view models and Compose screens for sign-in, the inbox (open, waiting, snoozed and resolved), concern detail with conflict resolution and its source emails, the archive (archive and quarantine views, search, source detail with attachment inventory and linked items), and settings with sync and sign-out. The Worker origin comes from `-Padminifender.apiBaseUrl` and defaults to an unroutable host. `push/` holds the FCM receiver, notification channels and the registration worker. Chat arrives in a later slice.

Toolchain: the committed Gradle wrapper (checksum-pinned), JDK 21, and the versions in `gradle/libs.versions.toml`. Builds need an Android SDK with platform 37 (`ANDROID_HOME` or `local.properties`, which is not committed).

```sh
./gradlew :core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest :core:data:lintDebug :core:data:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

The release build is unsigned; signing and Play delivery belong to slice 15. Push needs `app/google-services.json` for the `adminifend` Firebase project, which is never committed; the Google services plugin is applied only when that file exists.
