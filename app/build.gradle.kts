import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing — keystore.properties and the keystore itself live in the
// project root. Back both up: losing them means future updates can't install
// over existing installs.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.aeonos.portalha"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aeonos.portalha"
        // 28 = Android 9 (Portal+); 29 = Android 10 (Portal / Portal Mini).
        minSdk = 28
        targetSdk = 35
        versionCode = 23
        versionName = "1.9.0"
    }

    buildFeatures {
        buildConfig = true   // exposes BuildConfig.VERSION_NAME for the in-app updater
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Minify off: R8 + Paho reflection is risk with zero upside for a
            // sideloaded kiosk app, and all testing happens on unminified builds.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.paho.mqtt)

    // RTSP server (headless RtspServerStream). Kotlin-2.0-era versions so they
    // build cleanly under our Kotlin 2.0.20 — no metadata hacks.
    implementation("com.github.pedroSG94:RTSP-Server:1.3.0")
    implementation("com.github.pedroSG94.RootEncoder:library:2.4.6")
}
