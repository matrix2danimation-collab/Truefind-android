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

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    // live chat updates (the same socket the web SDK uses) and PATCH requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
