plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI run number gives every build a higher versionCode, so new APKs install over old ones.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Store signing key comes from the environment (GitHub secrets) and is never committed.
val storeKeystore = System.getenv("PEELBOX_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.noctaur.palettestickers"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noctaur.peelbox"
        minSdk = 31
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Shared, checked-in key: only for the sideloaded dev build.
        create("dev") {
            storeFile = rootProject.file("keystore/palette-stickers.jks")
            storePassword = "palettestickers"
            keyAlias = "palette-stickers"
            keyPassword = "palettestickers"
        }
        if (storeKeystore != null) {
            create("store") {
                storeFile = storeKeystore
                storePassword = System.getenv("PEELBOX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PEELBOX_KEY_ALIAS") ?: "peelbox"
                keyPassword = System.getenv("PEELBOX_KEY_PASSWORD") ?: System.getenv("PEELBOX_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // "Peelbox Dev": unlimited packs, installs alongside the store version.
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("dev")
            buildConfigField("boolean", "STORE", "false")
            buildConfigField("boolean", "IAP_TEST", "false")
            resValue("string", "app_name", "Peelbox Dev")
        }
        // Store build for Galaxy Store: 3 free packs, Samsung IAP unlock.
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("store")
            buildConfigField("boolean", "STORE", "true")
            // IAP_TEST=true makes purchases free for testers registered in Seller Portal.
            buildConfigField("boolean", "IAP_TEST", (System.getenv("IAP_TEST") == "true").toString())
            resValue("string", "app_name", "Peelbox")
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
    implementation("com.samsung.developer:iap:6.5.2")
}
