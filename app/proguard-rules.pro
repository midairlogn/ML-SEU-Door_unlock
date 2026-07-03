# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Crypto - keep constants used by NFC/BLE protocol
-keepclassmembers class com.whxinna.userplatform.crypto.** {
    public static final int *;
    public static final byte *;
}
