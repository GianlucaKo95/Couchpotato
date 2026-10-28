plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build number from CI (GitHub Actions), so every APK can be installed over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val keystorePath: String? = System.getenv("CP_KEYSTORE")
val keystorePassword: String? = System.getenv("CP_KEYSTORE_PASSWORD")

android {
    namespace = "de.koeven.couchpotato"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.koeven.couchpotato"
        minSdk = 25          // Fire OS 6 (Fire TV Stick 4K, 2018) = Android 7.1
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (keystorePath != null && keystorePassword != null) {
            create("release-key") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "sofa"   // alias inside the keystore (generated before the rename)
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Stable key from CI secrets; falls back to the debug key for local builds.
            signingConfig = signingConfigs.findByName("release-key") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")   // only for text fields in settings
    implementation("androidx.tv:tv-material:1.0.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")   // .m3u8 streams

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
