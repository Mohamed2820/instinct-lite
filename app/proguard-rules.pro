# R8 keeps what the manifest references automatically. Nothing extra is needed for this app.
# Strip debug/verbose logging from release builds so nothing sensitive can reach logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# security-crypto uses Tink, which references optional annotation classes.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
# Tink keysets use protobuf-lite reflection on generated fields.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
