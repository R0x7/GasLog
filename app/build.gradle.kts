plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Keep this package name: it's what you register in Google Cloud for sign-in.
    namespace = "com.gaslog.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gaslog.app"
        minSdk = 26
        targetSdk = 36
        // GitHub Actions passes its run number so every release installs over the last one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        // Phones only: the on-device text recognizer ships ~11 MB of native code per CPU type.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Release signing comes from the CI workflow (the key is decrypted there; see signing/README.md).
    // Without these variables a local release build is simply unsigned.
    val keystore = System.getenv("GASLOG_KEYSTORE")
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("GASLOG_KEY_PASSPHRASE")
                keyAlias = "gaslog"
                keyPassword = System.getenv("GASLOG_KEY_PASSPHRASE")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
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
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Google authorization (Sheets access). Sheets and Gemini are called over plain HTTPS.
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    // Live scan: camera preview + on-device text recognition (works offline, nothing is uploaded).
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-mlkit-vision:$camerax")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
