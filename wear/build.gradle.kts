import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The watch app has the phone app's version: read it from there, so a release bump is one edit.
val phoneBuildScript = rootProject.file("app/build.gradle.kts").readText()
val phoneVersionName = Regex("""versionName = "([^"]+)"""").find(phoneBuildScript)?.groupValues?.get(1) ?: "0.0.0"
val phoneVersionCode = Regex("""versionCode = (\d+)""").find(phoneBuildScript)?.groupValues?.get(1)?.toInt() ?: 1

// Wear OS remote for CastApp. It talks to the phone app through the Wear Data Layer, which only
// connects apps with the same package name and signing key: so the application id (and the
// debug suffix) match the phone app's, and it is signed with the same shared key.
android {
    namespace = "io.github.patschgoo.castapp.wear"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.patschgoo.castapp"
        // Wear OS 3 (API 30) and newer.
        minSdk = 30
        targetSdk = 36
        versionCode = phoneVersionCode
        versionName = phoneVersionName
    }

    // Must be the phone app's key (see app/build.gradle.kts): the Data Layer only connects apps
    // signed with the same key.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("app/castapp.keystore")
            storePassword = "castapp"
            keyAlias = "castapp"
            keyPassword = "castapp"
        }
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
            signingConfig = signingConfigs.findByName("own") ?: signingConfigs.getByName("shared")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("shared")
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
    implementation(project(":wear-protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.play.services.wearable)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
