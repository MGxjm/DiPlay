# DiPlay car-test (debug) shrinking rules.
# The standalone car-test APK runs R8; keep everything that is reached reflectively,
# by JNI, or by the CarPlay / MFi stacks.

# Core DiPlay
-keep class com.shilapi.xcertplay.** { *; }
-keep class com.shihab.diplay.** { *; }

# CarPlay / Android for Cars
-keep class androidx.car.app.** { *; }
-keep class androidx.car.** { *; }

# MFi / accessory authentication (BouncyCastle + iAP2 crypto)
-keep class org.bouncycastle.** { *; }
-keep class org.spongycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**

# JNI entry points and native callbacks
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.shilapi.xcertplay.** { *; }
-keep class **.R$* { *; }

# Annotations used to mark keep-worthy code
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}

# Parcelable / Serializable contract
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Reflection-driven Android entry points declared in the manifest
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# Compose brings its own consumer rules; keeping the whole toolkit would double the dex.
-dontwarn androidx.compose.**
-dontwarn kotlinx.coroutines.**
