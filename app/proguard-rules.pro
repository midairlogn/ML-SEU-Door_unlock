# Optimization and Obfuscation settings
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# AndroidSVG
-keep class com.caverock.androidsvg.** { *; }

# Crypto - keep constants used by NFC/BLE protocol
-keepclassmembers class com.midairlogn.seudoorunlock.crypto.** {
    public static final int *;
    public static final byte *;
}

# Keep models for JSON parsing if needed (though we use JSONObject manually, it's safer)
-keep class com.midairlogn.seudoorunlock.model.** { *; }

# Strip verbose/debug/info Log calls in release builds, keep warn/error for diagnostics
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
