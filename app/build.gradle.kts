plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.truefind.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.truefind.android"
        minSdk = 24
        targetSdk = 36
        // every GitHub build gets the next number, so the app can tell an update is newer
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        // test builds: the app checks this file and offers the newer APK (Update button)
        buildConfigField("String", "UPDATE_INFO_URL", "\"https://github.com/matrix2danimation-collab/Truefind-android/releases/download/test-build/version.json\"")
        buildConfigField("boolean", "SELF_UPDATE", "true")
        // the games' voice (Agora) has phone code for each kind of processor: keep the phones'
        // (arm) and the GitHub test phone's (x86_64)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Test builds are always signed with the same key (in this repo), so a new
    // APK installs over the old one as an update. The Play Store gets its own
    // private key later — never this one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("test-signing.keystore")
            storePassword = "truefindtest"
            keyAlias = "truefind-test"
            keyPassword = "truefindtest"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    // The games' voice uses only Agora's basic voice call (the engine's own echo and noise filters, as the
    // website's AEC/ANS). Agora's optional add-ons — AI noise and echo filters, 3D sound, voice beauty,
    // lip sync — are never switched on, so they stay out of the APK (Agora: "Remove unnecessary extensions").
    packaging {
        jniLibs {
            excludes += listOf(
                "**/libagora_ai_echo_cancellation_extension.so",
                "**/libagora_ai_echo_cancellation_ll_extension.so",
                "**/libagora_ai_noise_suppression_extension.so",
                "**/libagora_ai_noise_suppression_ll_extension.so",
                "**/libagora_audio_beauty_extension.so",
                "**/libagora_spatial_audio_extension.so",
                "**/libagora_lip_sync_extension.so",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    // native games: the web's SVG pictures drawn by the phone, and the games' voice room
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("io.agora.rtc:voice-sdk:4.5.0")
}
