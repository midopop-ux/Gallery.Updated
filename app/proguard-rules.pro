-keep class com.goodwy.** { *; }
-dontwarn android.graphics.Canvas
-dontwarn com.goodwy.**
-dontwarn org.apache.**

# Picasso
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.ConscryptPlatform

-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# RenderScript
-keepclasseswithmembernames class * {
native <methods>;
}
-keep class androidx.renderscript.** { *; }

# Reprint
-keep class com.github.ajalt.reprint.module.** { *; }
# android.hardware.fingerprint.FingerprintManager was removed from the SDK in API 37;
# Reprint's legacy Marshmallow module still references it as a runtime-only fallback.
-dontwarn android.hardware.fingerprint.FingerprintManager$AuthenticationCallback
-dontwarn android.hardware.fingerprint.FingerprintManager$AuthenticationResult
-dontwarn android.hardware.fingerprint.FingerprintManager$CryptoObject
-dontwarn android.hardware.fingerprint.FingerprintManager

# Goodwy
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

# ExifInterface
-keep class androidx.exifinterface.media.** { *; }
-dontwarn androidx.exifinterface.media.**
-keep class it.sephiroth.android.library.exif2.** { *; }
-dontwarn it.sephiroth.android.library.exif2.**

# Room & SQLite
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
-keep class androidx.sqlite.** { *; }
-dontwarn androidx.sqlite.**

# Glide
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**
