plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build number from CI (GitHub Actions), so every APK can be installed over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val keystorePath: String? = System.getenv("CP_KEYSTORE")
val keystorePassword: String? = System.getenv("CP_KEYSTORE_PASSWORD")
// TMDB key/token from the repo secret TMDB_TOKEN (empty = TMDB off, unless set on the stick)
val tmdbToken: String = (System.getenv("TMDB_TOKEN") ?: "").trim().replace("\"", "")
// read-only GitHub token (this repo, Contents: read) for the in-app updater; empty = updater off
val updateToken: String = (System.getenv("UPDATE_TOKEN") ?: "").trim().replace("\"", "")
val supabaseUrl: String = (System.getenv("SUPABASE_URL") ?: "").trim().trimEnd('/')
val supabasePublishableKey: String = (System.getenv("SUPABASE_PUBLISHABLE_KEY") ?: "").trim()
fun String.asBuildConfigLiteral() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "de.koeven.couchpotato"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.koeven.couchpotato"
        minSdk = 25          // Fire OS 6 (Fire TV Stick 4K, 2018) = Android 7.1
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
        buildConfigField("String", "SUPABASE_URL", supabaseUrl.asBuildConfigLiteral())
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", supabasePublishableKey.asBuildConfigLiteral())
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
            // R8: Compose runs much smoother optimized; smaller APK. mapping.txt goes to the release.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
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
    implementation("com.google.zxing:core:3.5.3")     // QR code for "Mit dem Handy eingeben"
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // image loading through ImageGate
    implementation("androidx.palette:palette-ktx:1.0.0")   // background glow colours from covers

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")   // .m3u8 streams

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
