plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The "True Find Preview" app (-PtfPreview=true; GitHub builds it from the branch supabase-app): a second
// app on the owner's phone, next to True Find (its own id, name and icon), to try every change before it goes
// into the real app. Its Update button reads its own file (release "preview-build"), never the real app's.
val tfPreview = project.findProperty("tfPreview") == "true"

android {
    namespace = "app.truefind.android"
    compileSdk = 36

    defaultConfig {
        applicationId = if (tfPreview) "app.truefind.android.preview" else "app.truefind.android"
        minSdk = 24
        targetSdk = 36
        // every GitHub build gets the next number, so the app can tell an update is newer
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        // the name under the icon (the Preview app: "True Find Preview")
        manifestPlaceholders["appLabel"] = if (tfPreview) "True Find Preview" else "@string/app_name"
        // test builds: the app checks this file and offers the newer APK (Update button)
        buildConfigField("String", "UPDATE_INFO_URL", "\"https://github.com/matrix2danimation-collab/Truefind-android/releases/download/${if (tfPreview) "preview-build" else "test-build"}/version.json\"")
        buildConfigField("boolean", "PREVIEW", tfPreview.toString())
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

    // The Play Store upload key: only on GitHub, from the repo secrets (never in this repo). The CI step
    // "Play Store bundle" writes the key file and sets these; without them the release build stays unsigned.
    val uploadStore = System.getenv("TF_UPLOAD_STORE_FILE")
    if (!uploadStore.isNullOrEmpty() && file(uploadStore).exists()) {
        signingConfigs.create("upload") {
            storeFile = file(uploadStore)
            storePassword = System.getenv("TF_UPLOAD_STORE_PASSWORD")
            keyAlias = System.getenv("TF_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("TF_UPLOAD_KEY_PASSWORD")
        }
    }

    // lint only reports (the test-branch CI step prints it); it never stops a build
    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        textReport = true
    }

    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // The Play Store build (bundleRelease -PtfStore=true) has no install permission: Google Play does not
    // allow an app to update itself, and the test builds' Update button was the only thing that used it
    // (src/store/AndroidManifest.xml removes it). Test builds (assembleDebug) are unchanged.
    if (project.findProperty("tfStore") == "true") {
        sourceSets.getByName("release").manifest.srcFile("src/store/AndroidManifest.xml")
    }
    // the Preview app's own icon (the same heart with an orange "PREVIEW" band): over the main one
    if (tfPreview) sourceSets.getByName("debug").res.srcDir("src/preview/res")
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
// App Health (25 Nov): Firebase Crashlytics too — every crash and "app not responding" with its full technical
// details in the Firebase console (free); the Control Center shows the app's own crash list (AppHealth.kt).
val firebaseOn = file("google-services.json").exists()
if (firebaseOn) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-messaging")
    if (firebaseOn) implementation("com.google.firebase:firebase-crashlytics")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // spring physics for the Wallet's smooth bounce (owner, 27 Nov)
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    // Live Dance video: Agora's Android engine, basic part only (no extensions) to keep the app small
    implementation("io.agora.rtc:full-rtc-basic:$agoraVersion")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    // an old Fragment library came in through other libraries; with it, permission answers (microphone,
    // camera, notifications) can be lost on some phones. Android's checker asks for 1.3.0 or newer.
    implementation("androidx.fragment:fragment:1.8.5")
    // plain-logic unit tests (app/src/test): JUnit, and the real org.json (Android's own is only a stub in tests)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    implementation("androidx.webkit:webkit:1.14.0")
    // Profile → Become a Host / Creator: the live selfie video (camera only)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // live chat updates (the same socket the web SDK uses) and PATCH requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Google Play Billing (25 Nov): coins and Reconnect plans are bought through Google Play (PlayBilling.kt)
    implementation("com.android.billingclient:billing:9.1.0")
    // native games: the web's SVG pictures drawn by the phone, and the games' voice room
    implementation("com.caverock:androidsvg-aar:1.4")
}
