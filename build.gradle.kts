plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("com.google.gms.google-services") version "4.4.3" apply false
    // crash reports with full details in the Firebase console (App Health, 25 Nov)
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
}
