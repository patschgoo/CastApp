plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.patschgoo.castapp"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.patschgoo.castapp"
        minSdk = 25
        targetSdk = 36
        versionCode = 5
        versionName = "3.1.0"

        // Phones only; keeps the speech-recognition library small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    signingConfigs {
        // A fixed key that lives in the repo so every build (local or CI) can be
        // installed over the previous one. Replace it with your own key if you
        // ever publish the app anywhere.
        create("shared") {
            storeFile = file("castapp.keystore")
            storePassword = "castapp"
            keyAlias = "castapp"
            keyPassword = "castapp"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    // Native part of the optional Whisper speech engine (whisper.cpp, vendored in src/main/cpp).
    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Compressed native libraries keep the download small (they are unpacked on install).
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    // Windows / Samba network shares
    implementation(libs.smbj)
    // Crypto for AirPlay pairing (Ed25519, X25519, ChaCha20-Poly1305); also used by smbj
    implementation(libs.bouncycastle)
    // Offline speech recognition for generated subtitles
    implementation(libs.vosk.android)
    // The Android flavour of JNA (with its native part), needed by Vosk.
    implementation(libs.jna) { artifact { type = "aar" } }
    // Player of the TV receiver mode (Fire TV, Android TV)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    // Wear OS remote: messages to and from a paired watch (every call is guarded; the app also
    // runs on phones without Google Play services)
    implementation(project(":wear-protocol"))
    implementation(libs.play.services.wearable)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
