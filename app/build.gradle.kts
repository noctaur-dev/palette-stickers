plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI run number gives every build a higher versionCode, so new APKs install over old ones.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.noctaur.palettestickers"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noctaur.palettestickers"
        minSdk = 31
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // One fixed key (checked in) so every build — local or CI — can update the installed app.
    // This app is sideloaded for personal use; swap in a private key if it's ever published.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/palette-stickers.jks")
            storePassword = "palettestickers"
            keyAlias = "palette-stickers"
            keyPassword = "palettestickers"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
