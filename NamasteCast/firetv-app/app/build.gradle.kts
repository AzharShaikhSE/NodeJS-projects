plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.namastecast.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.namastecast.tv"
        // Fire OS 5 (Fire TV Stick gen 2) is API 22. Newer sticks are 25/28/30.
        minSdk = 22
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        // Committed keystore on purpose: personal sideloaded app, so every build
        // (this machine, CI, your laptop) signs the same way and updates install
        // over the top without uninstalling. Not for Play/Amazon store use.
        create("shared") {
            storeFile = rootProject.file("keystore/namastecast.jks")
            storePassword = "namastecast"
            keyAlias = "namastecast"
            keyPassword = "namastecast"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only ships stubs).
    testImplementation("org.json:json:20240303")
}
