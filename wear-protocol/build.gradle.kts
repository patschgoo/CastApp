plugins {
    alias(libs.plugins.android.library)
}

// The messages the phone app and the Wear OS app send each other. No UI in here; it is its own
// module so both apps compile the very same encoder and decoder.
android {
    namespace = "io.github.patschgoo.castapp.wearproto"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
