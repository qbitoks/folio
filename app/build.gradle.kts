plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.folio"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.folio"
        minSdk = 34
        targetSdk = 37
        versionCode = 30
        versionName = "0.11.0"
        // Phones (arm64) and the x86_64 emulator; speech engines with heavy native code are separate apps.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }

    // Trusted tag/manual CI builds restore the preview keystore from Actions Secrets.
    // Local builds and PR CI keep Android's normal debug signing.
    System.getenv("ANDROID_DEBUG_KEYSTORE_PATH")?.let { keyPath ->
        signingConfigs.getByName("debug") {
            storeFile = file(keyPath)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key until a release keystore exists, so the build is installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:catalog"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}
