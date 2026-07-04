# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Crypto - keep constants used by NFC/BLE protocol
-keepclassmembers class com.midairlogn.seudoorunlock.crypto.** {
    public static final int *;
    public static final byte *;
}

# Strip verbose/debug/info Log calls in release builds, keep warn/error for diagnostics
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
