# Android app

Kotlin and Jetpack Compose app for the owner's phone ([TDD](../docs/specifications/tdd.md#android)). Status per slice is in the [roadmap](../docs/roadmap.md).

- `core/model`: pure domain types and policy (concern controls, optimistic edits, outbox decisions, push payload parsing). JVM tests only.
- `core/data`: Room cache (schemas exported to `core/data/schemas`), the OkHttp API client and the sync engine with its offline outbox. The engine depends only on interfaces and is tested on the JVM with fakes and the shared `contracts/` sync fixture; the Room store has instrumentation tests that need an emulator or device.
- `app`: application shell. Sign-in, screens and push arrive in later slices.

Toolchain: the committed Gradle wrapper (checksum-pinned), JDK 21, and the versions in `gradle/libs.versions.toml`. Builds need an Android SDK with platform 37 (`ANDROID_HOME` or `local.properties`, which is not committed).

```sh
./gradlew :core:model:test :core:data:testDebugUnitTest :core:data:lintDebug :core:data:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

The release build is unsigned; signing and Play delivery belong to slice 15.
