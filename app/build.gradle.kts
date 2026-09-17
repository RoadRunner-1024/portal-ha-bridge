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
        versionCode = 47
        versionName = "1.20.6"

        // Portals are ARM — ship only ARM native libs (Vosk/RootEncoder bundle x86 +
        // x86_64 + mips for emulators, ~20 MB of dead weight on real hardware).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
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
        // Sendspin's JVM client library is built for JDK 17 bytecode.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.paho.mqtt)

    // Extracts a dominant/vibrant colour from album art for the lyrics-view gradient.
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Sendspin (Open Home Foundation) — synchronised multi-room audio from Music Assistant.
    // Pure Kotlin/JVM client: OkHttp + Java-WebSocket transport, Noise encryption and the
    // Kalman clock sync. mDNS is left to us to supply via Android's NsdManager.
    implementation("com.github.Sendspin:sendspin-jvm:v0.3.4")
    // The client takes an OkHttpClient and a Moshi instance, but scopes both as `implementation`,
    // so they aren't on our compile classpath transitively — declare them here too.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    // KotlinJsonAdapterFactory — the reflective fallback the Sendspin client's Moshi setup asks
    // for alongside its JsonOptionalAdapterFactory.
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // RTSP server (headless RtspServerStream). Kotlin-2.0-era versions so they
    // build cleanly under our Kotlin 2.0.20 — no metadata hacks.
    implementation("com.github.pedroSG94:RTSP-Server:1.3.0")
    implementation("com.github.pedroSG94.RootEncoder:library:2.4.6")

    // On-device wake word ("hey jarvis") — Vosk speech recognizer with a grammar
    // limited to the wake phrase. Keyless/offline; the phrase is a config string, so
    // the wake word is changeable without a new model. The ~40 MB model is downloaded
    // to filesDir on first enable (keeps the APK small), not bundled.
    implementation("com.alphacephei:vosk-android:0.3.75")

    // openWakeWord neural verifier (assets/oww/*.tflite, Apache-2, dscripka/openWakeWord):
    // second-stage precision check on Vosk wake matches for phrases with a pretrained
    // model ("alexa", "hey jarvis") — kills the grammar decoder's false positives.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
