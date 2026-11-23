# R8 rules for the Play Store build (release: isMinifyEnabled + isShrinkResources in build.gradle.kts).
# Test builds (assembleDebug) are not shrunk, so these rules change nothing there.

# Unused code is removed, but names are not shortened: the crash notes in Control Center → Errors
# (AppStatus.kt) and the call / game error texts keep the real class names and line numbers. (R8 still joins
# small methods into their callers, so a crash trace can show a few frames fewer than in a test build.)
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# The app's classes reached by reflection:
# - the sign-in / web page bridges (WebBridge, Push.Bridge, CallManager's bridge, AppStatus.Bridge,
#   LoginLayer.Bridge): their methods are called by name from JavaScript, never from Kotlin
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes RuntimeVisibleAnnotations
# - LiveVoice.kt reads Agora's RtcEngine.mInstance by name (covered by the Agora rule below)

# Agora (calls, Live Dance, Live Rooms, the games' voice): the engine's native code calls its Java classes
# by name, so all of Agora stays as it is (Agora's own advice for shrunk apps)
-keep class io.agora.** { *; }
-dontwarn io.agora.**
# Agora 4.5.2 refers to a build-tool helper that is not in any library (checked with jdeps: 11 Agora classes →
# com.google.devtools.build.android.desugar.runtime.ThrowableExtension); R8 would stop the build on it.
# Test builds already run with it missing, so nothing changes for the app.
-dontwarn com.google.devtools.build.android.desugar.runtime.**

# Firebase (notifications while the app is closed): Firebase finds its parts by class name
# (ComponentRegistrar, listed in the merged manifest). The Firebase libraries bring their own R8 rules;
# this keeps the part the app depends on even if those rules change. TfMessagingService is in the
# manifest, so R8 keeps it by itself. Firebase's texts from google-services.json (google_app_id …) are read
# by name too: src/release/res/raw/truefind_keep.xml keeps them when resources are shrunk.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# OkHttp (live chat socket, PATCH requests): okhttp-4.12.0.jar carries its own R8 rules
# (META-INF/proguard/okhttp3.pro: the PublicSuffixDatabase name, optional TLS providers); these repeat
# the part that would stop the build if it were missing.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# org.json: part of Android itself (android.jar), not inside the app, so R8 never removes or renames it;
# the app reads JSON by key names (strings), not by reflection. Nothing to keep.
