plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.digifender.adminifender"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.digifender.adminifender"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Worker origin for this build. Local builds point at an unroutable host until an environment exists;
        // staging and production pass -Padminifender.apiBaseUrl=https://... (tdd.md#environments-and-preflight).
        val apiBaseUrl = providers.gradleProperty("adminifender.apiBaseUrl").getOrElse("https://adminifender.invalid/")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
