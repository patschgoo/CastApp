import java.util.Base64

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
        versionCode = 6
        versionName = "4.0.0"

        // Phones only; keeps the speech-recognition library small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    signingConfigs {
        // The key that lives in the repo, so every build (local or CI) can be installed
        // over the previous one. It is public: use your own key for anything you publish.
        create("shared") {
            storeFile = file("castapp.keystore")
            storePassword = "castapp"
            keyAlias = "castapp"
            keyPassword = "castapp"
        }
        // Your own release key, used only when it is configured (see "Signing key" in the
        // README): CI secrets as environment variables, or gradle properties on your PC.
        //   CASTAPP_KEYSTORE_FILE or CASTAPP_KEYSTORE_BASE64, CASTAPP_KEYSTORE_PASSWORD,
        //   CASTAPP_KEY_ALIAS, CASTAPP_KEY_PASSWORD (key password defaults to the store password)
        fun setting(name: String): String? =
            (System.getenv(name) ?: providers.gradleProperty(name).orNull)?.trim()?.takeIf { it.isNotEmpty() }
        val keystoreFile: File? = setting("CASTAPP_KEYSTORE_FILE")?.let { file(it) }
            ?: setting("CASTAPP_KEYSTORE_BASE64")?.let { b64 ->
                layout.buildDirectory.file("signing/release.jks").get().asFile.also {
                    it.parentFile.mkdirs()
                    it.writeBytes(Base64.getMimeDecoder().decode(b64))
                }
            }
        val storePass = setting("CASTAPP_KEYSTORE_PASSWORD")
        val alias = setting("CASTAPP_KEY_ALIAS")
        if (keystoreFile != null && storePass != null && alias != null) {
            create("own") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = setting("CASTAPP_KEY_PASSWORD") ?: storePass
            }
        } else if (keystoreFile != null || storePass != null || alias != null) {
            logger.warn("CastApp: incomplete own signing key (need keystore, CASTAPP_KEYSTORE_PASSWORD and CASTAPP_KEY_ALIAS); using the committed key.")
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
            // Own key when configured, else the committed one.
            signingConfig = signingConfigs.findByName("own") ?: signingConfigs.getByName("shared")
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
        // Robolectric (screenshot tests) reads the app's strings, fonts and drawables.
        unitTests.isIncludeAndroidResources = true
    }
}

// Screenshot tests (Robolectric + Roborazzi, in src/test/.../screenshots) render the Compose
// screens to PNGs on the JVM. They only run when asked for, so CI stays fast:
//   ./gradlew :app:testDebugUnitTest -Pscreenshots         -> app/build/outputs/roborazzi/
//   ./gradlew :app:testDebugUnitTest -Pscreenshots=store   -> also the docs/ and fastlane/ images
val screenshots: String? = providers.gradleProperty("screenshots").orNull
tasks.withType<Test>().configureEach {
    if (screenshots == null) {
        exclude("io/github/patschgoo/castapp/screenshots/**")
    } else {
        filter.includeTestsMatching("io.github.patschgoo.castapp.screenshots.*")
        systemProperty("roborazzi.test.record", "true")
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        systemProperty("castapp.screenshots", screenshots.ifEmpty { "all" })
        systemProperty("castapp.repoDir", rootDir.absolutePath)
        maxHeapSize = "3g"
        // The pictures are the output: render again even when no code changed.
        outputs.upToDateWhen { false }
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
    // The Data Layer library pulls in an ancient androidx.fragment (1.1.0), which lint rejects
    // for the Activity Result API used by ProjectionActivity; keep it at a current version.
    constraints {
        implementation(libs.androidx.fragment) { because("play-services-base asks for androidx.fragment 1.1.0") }
    }
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
