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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Live Dance video (Agora) ships for phones (ARM) only; the emulator tests add x86_64
        ndk {
            abiFilters += (project.findProperty("tfAbis") as String?)?.split(",")?.map { it.trim() }
                ?: if (project.findProperty("tfAllAbis") == "true") listOf("arm64-v8a", "armeabi-v7a", "x86_64") else listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // size test only (-PtfPackedLibs=true): native libraries stored compressed inside the APK
    packaging {
        jniLibs {
            useLegacyPackaging = project.findProperty("tfPackedLibs") == "true"
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

val agoraVersion = (project.findProperty("agoraVersion") as String?) ?: "4.5.2"

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Live Dance video: Agora's Android engine, basic part only (no extensions) to keep the app small
    implementation("io.agora.rtc:full-rtc-basic:$agoraVersion")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
}
