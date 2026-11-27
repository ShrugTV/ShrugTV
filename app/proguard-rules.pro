# R8 for release and profile.
#
# Only shrink and optimise, do NOT rename: the code comes from a decompiler export and
# works by names in many places -- Gson fields, Serializable (home screen store
# HomeCache, intent extras), reflection on library fields. With names kept the
# baseline profile (app/src/main/baseline-prof.txt) fits without rewriting, and stack traces from the
# logcat stay readable. The size gain comes almost entirely from dropping unused code.
-dontobfuscate

# Gson reads these models by reflection (EventSub.java, fromJson); otherwise R8 would see the fields as
# unused and would remove them.
-keep class com.shrug.shrugtv.eventSub.** { *; }

# Serializable: Movie & Co. travel by intent and into the home screen store; fields that are only
# written and read back by serialisation must stay. For ALL classes,
# not only our own: Movie holds time values whose serialisation runs through writeReplace
# (erster Lauf 2026-10-28, damals Joda: Player-Absturz "ISOChronology; no valid constructor";
# java.time.ZonedDateTime arbeitet ebenso mit writeReplace).
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}


# Optional dependencies that the libraries only address after a runtime check
# (OkHttp-Plattformen); im APK fehlen sie absichtlich (R8-Lauf 2026-10-28, missing_rules.txt).
# Ion-Conscrypt and Joda-Convert went away with Ion and Joda-Time.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Ktor server (phone companion): its debugger check reads java.lang.management, which Android
# lacks; Ktor catches the error and takes "no debugger" (IntellijIdeaDebugDetector), so it only needs silencing.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
# kotlin-reflect is left out on purpose (app/build.gradle): Ktor references it only for loading
# modules by name and in development mode, both unused here. Its own rules went with it; the attributes they kept
# stay, so nothing that reads generic signatures or inner classes by reflection changes.
-dontwarn kotlin.reflect.full.**
-dontwarn kotlin.reflect.jvm.**
-keepattributes InnerClasses,Signature,RuntimeVisible*Annotations,EnclosingMethod
# HiveMQ MQTT client: optional Netty parts (epoll, HTTP/WebSocket, proxy, tcnative,
# log4j, Jetty ALPN) that the client only uses after a check; not packed on purpose.
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.handler.codec.http.**
-dontwarn io.netty.handler.proxy.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.**
# The client's own Android rules (hivemq.github.io/hivemq-mqtt-client, installation/android): Netty finds handler
# methods and JCTools its queue fields by name, so their members must neither be renamed nor removed.
-keepclassmembernames class io.netty.** { *; }
-keepclassmembers class org.jctools.** { *; }
