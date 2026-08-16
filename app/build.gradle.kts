import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// ── Load signing credentials from local.properties ────────────────────────────
// local.properties is git-ignored; never hard-code credentials in this file.
val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) load(propsFile.inputStream())
}

val releaseStoreFile: String   = localProps.getProperty("RELEASE_STORE_FILE",   "")
val releaseStorePassword: String = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias: String    = localProps.getProperty("RELEASE_KEY_ALIAS",    "")
val releaseKeyPassword: String = localProps.getProperty("RELEASE_KEY_PASSWORD", "")

android {
    namespace = "com.androidexpert35.audiophilemusicplayer"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.androidexpert35.audiophilemusicplayer"
        minSdk = 33
        targetSdk = 36

        // ── Versioning (single source of truth) ──
        // Bump these three values for each release; versionCode is derived automatically.
        val major = 1
        val minor = 0
        val patch = 2
        versionCode = major * 10000 + minor * 100 + patch   // e.g. 1.2.3 → 10203
        versionName = "$major.$minor.$patch"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Bit-perfect native audio pipeline: FFmpeg JNI decoder + AudioTrack sink.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    signingConfigs {
        // Release signing config — credentials are loaded from local.properties
        // (git-ignored).  Falls back to the debug config when any required value
        // is absent so local debug builds continue to work without credentials.
        create("release") {
            if (releaseStoreFile.isNotBlank()
                && releaseStorePassword.isNotBlank()
                && releaseKeyAlias.isNotBlank()
                && releaseKeyPassword.isNotBlank()
            ) {
                storeFile     = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias      = releaseKeyAlias
                keyPassword   = releaseKeyPassword
            } else {
                // Credentials not set — fall back to the debug keystore so the
                // project still assembles on machines without a release keystore.
                val debugConfig = signingConfigs.getByName("debug")
                storeFile     = debugConfig.storeFile
                storePassword = debugConfig.storePassword
                keyAlias      = debugConfig.keyAlias
                keyPassword   = debugConfig.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Shared presentation primitives: UI state, BaseViewModel, navigation, and screen scaffolds.
    implementation(libs.coreui)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose (minimal — kept for future UI layer)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Media3 — MediaSession surface + common types always present. The ExoPlayer
    // runtime backs the StandardEngine (battery-saving) strategy; the AudiophileEngine
    // still uses the in-process FFmpeg + AudioTrack pipeline. AudioEngineManager
    // hot-swaps between the two at runtime.
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Navigation (for NavOptions in BaseViewModel; Compose nav graph deferred)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Image Loading (Album Art + Remote Enrichment)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Networking — Deezer Remote Metadata Enrichment
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.gson)
    debugImplementation(libs.okhttp.logging.interceptor)

    // Material Icons Extended
    implementation(libs.androidx.compose.material.icons.extended)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    // Android Instrumented Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Debug
    implementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
