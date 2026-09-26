import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jawa.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jawa.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.18"
    }

    signingConfigs {
        // Debug builds (Android Studio, the Build workflow): a key kept in the repo,
        // so every debug build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release builds: your own key. The Release workflow provides it from GitHub
        // secrets through these environment variables; nothing secret is in the repo.
        create("release") {
            System.getenv("JAWA_KEYSTORE_FILE")?.let { storeFile = file(it) }
            storePassword = System.getenv("JAWA_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("JAWA_KEY_ALIAS")
            keyPassword = System.getenv("JAWA_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // Separate app ID for debug builds, so a Studio build and the signed release
            // can sit side by side instead of clashing over different signing keys.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            // Your key when the environment provides it (CI); otherwise the debug key,
            // so a local release build still works.
            signingConfig = if (System.getenv("JAWA_KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Personal app: don't let lint warnings block a release build.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
