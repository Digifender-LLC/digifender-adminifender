import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Firebase config is per environment and never committed (AGENTS.md). Without it the app builds and runs with push
// reported as unavailable; the owner adds app/google-services.json for the adminifend project to enable FCM.
if (file("google-services.json").exists()) apply(plugin = libs.plugins.google.services.get().pluginId)

android {
    namespace = "com.digifender.adminifender"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.digifender.adminifender"
        minSdk = 31
        targetSdk = 36
        // Release builds pass a monotonically increasing code; tools/release_preflight.py checks it against ops/releases.json.
        versionCode = providers.gradleProperty("adminifender.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("adminifender.versionName").getOrElse("0.1.0")
        // Worker origin for this build. Local builds point at an unroutable host until an environment exists;
        // staging and production pass -Padminifender.apiBaseUrl=https://... (tdd.md#environments-and-preflight).
        val apiBaseUrl = providers.gradleProperty("adminifender.apiBaseUrl").getOrElse("https://adminifender.invalid/")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Pinned local model manifest (../models/README.md), embedded base64-encoded; without one the app reports
        // that no local model is configured. The model bytes themselves are never part of the build.
        val manifestPath = providers.gradleProperty("adminifender.modelManifest").orNull
        val manifestB64 = manifestPath?.let { Base64.getEncoder().encodeToString(rootProject.file(it).readBytes()) } ?: ""
        buildConfigField("String", "MODEL_MANIFEST_B64", "\"$manifestB64\"")
    }

    // Upload-key signing comes only from the environment (ops/release.md); the keystore and its passwords are never
    // committed. Without them the release and staging builds stay unsigned, as in CI.
    val uploadStore = providers.environmentVariable("ADMINIFENDER_UPLOAD_STORE_FILE").orNull
    val upload = uploadStore?.let {
        signingConfigs.create("upload") {
            storeFile = file(it)
            storePassword = providers.environmentVariable("ADMINIFENDER_UPLOAD_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ADMINIFENDER_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ADMINIFENDER_UPLOAD_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = upload
        }
        // Staging: a separate package and its own backend (tdd.md#environments-and-preflight), so it can never sign in
        // to or read production. Built like release so the tested code is the shipped code.
        create("staging") {
            initWith(getByName("release"))
            // Without an upload key, staging is signed with the local debug key so it installs by sideloading; its
            // fingerprint goes into assetlinks.json for the .staging package (ops/staging.md).
            signingConfig = upload ?: signingConfigs.getByName("debug")
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            matchingFallbacks += "release"
            val stagingUrl = providers.gradleProperty("adminifender.apiBaseUrl").getOrElse("https://adminifender-staging.digifender.com/")
            buildConfigField("String", "API_BASE_URL", "\"$stagingUrl\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // targetSdk is re-selected against current private Play requirements at release (slice 15).
        disable += "OldTargetApi"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.work.runtime)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.litertlm)
    implementation(libs.mlkit.text.recognition)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
