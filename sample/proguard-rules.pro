# BioPulse ProGuard / R8 Rules
-keep class com.github.ajalt.reprint.** { *; }
-dontwarn com.samsung.android.sdk.pass.**

# RxJava
-dontwarn rx.**
-dontwarn io.reactivex.**

# Biometric
-keep class androidx.biometric.** { *; }

# Google Mobile Ads
-keep class com.google.android.gms.ads.** { *; }
