// Room cache, HTTP client, session layer and the sync/outbox engine. The engine depends only on interfaces and runs in JVM tests.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.digifender.adminifender.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }

    // Room tests live in src/sharedTest and run twice: on a device (androidTest) and on the JVM under Robolectric
    // (test). The Keystore test stays device-only: Robolectric has no hardware-backed Keystore.
    sourceSets {
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        // MigrationTestHelper reads the committed schema JSON from assets.
        getByName("test").assets.directories.add("$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

// Shared contract fixtures live at the repository root; JVM tests parse them to keep the DTOs in step with the API.
val contractsDir = rootDir.resolve("../contracts").absolutePath
tasks.withType<Test>().configureEach { systemProperty("contractsDir", contractsDir) }

room {
    // Exported schemas are committed so every migration is tested against the real previous version.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.credentials)
    implementation(libs.work.runtime)
    // Passkeys on devices whose platform provider is Google Play services.
    runtimeOnly(libs.credentials.play.services)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
}
