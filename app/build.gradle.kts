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
        // The build for users (Play Store) is made with -PtfStore=true: then there is no Update box, no
        // "Check for update", no "Page speed report" and no test screens — all of them hang on this one switch.
        buildConfigField("boolean", "SELF_UPDATE", if (project.findProperty("tfStore") == "true") "false" else "true")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Live Dance video (Agora) ships for phones (ARM) only; the emulator tests add x86_64
        ndk {
            abiFilters += (project.findProperty("tfAbis") as String?)?.split(",")?.map { it.trim() }
                ?: if (project.findProperty("tfAllAbis") == "true") listOf("arm64-v8a", "armeabi-v7a", "x86_64") else listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Live Dance video libraries are stored compressed in the APK: about half the download
    // (measured: 36.4 MB instead of 66.6 MB for the phone APK). -PtfPackedLibs=false turns it off.
    packaging {
        jniLibs {
            useLegacyPackaging = project.findProperty("tfPackedLibs") != "false"
        }
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

val agoraVersion = (project.findProperty("agoraVersion") as String?) ?: "4.5.2"

// Notifications while the app is closed (Push.kt): Firebase Cloud Messaging. Firebase is switched on only
// when app/google-services.json is there (CI writes it from the GOOGLE_SERVICES_JSON secret); without it the
// app builds and works as before, just without these notifications.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Live Dance video: Agora's Android engine, basic part only (no extensions) to keep the app small
    implementation("io.agora.rtc:full-rtc-basic:$agoraVersion")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    // Profile → Become a Host / Creator: the live selfie video (camera only)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // live chat updates (the same socket the web SDK uses) and PATCH requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // native games: the web's SVG pictures drawn by the phone, and the games' voice room
    implementation("com.caverock:androidsvg-aar:1.4")
}
