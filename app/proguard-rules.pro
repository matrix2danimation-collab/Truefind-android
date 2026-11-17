-keepclassmembers class app.truefind.android.WebBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Live Dance video (Agora) — keep the engine classes if shrinking is ever turned on
-keep class io.agora.** { *; }
-dontwarn io.agora.**
