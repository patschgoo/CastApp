plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
        versionCode = 5
        versionName = "3.1.0"
    }

    signingConfigs {
        create("shared") {
            storeFile = file("../app/castapp.keystore")
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
