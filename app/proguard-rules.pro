# Tracer release rules. Hilt, Room, Compose, kotlinx.coroutines, OkHttp, CameraX and ML Kit ship
# their own consumer rules; only gaps are covered here.

# Readable crash traces from the field
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MapLibre Native: JNI looks up Java classes/fields/methods by name from native code.
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
# MapLibre's optional telemetry / location engine dependencies
-dontwarn com.google.android.gms.location.**

# ML Kit barcode (pairing QR scanner): model classes are reflectively instantiated by the
# component registrar; consumer rules cover most of it, keep the registrars explicitly.
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**

# ZXing (QR generation) — pure Java, no reflection; silence optional AWT references.
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# OkHttp optional platform integrations
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
