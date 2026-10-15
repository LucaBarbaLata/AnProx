# R8 rules for release builds.
# Retrofit, OkHttp, kotlinx.serialization and Koin ship their own consumer rules; these are
# the project-specific additions.

# API models are decoded reflectively through Retrofit's converter; keep them and their
# generated serializers intact.
-keep class io.github.lucabarbalata.anprox.model.** { *; }
-keepclassmembers class io.github.lucabarbalata.anprox.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit service interfaces.
-keep,allowobfuscation interface io.github.lucabarbalata.anprox.network.api.**

# Navigation routes are @Serializable classes.
-keep class io.github.lucabarbalata.anprox.ui.navigation.** { *; }

# Enum names are persisted (e.g. the theme setting) and compared by name; keep them stable.
-keepclassmembers enum io.github.lucabarbalata.anprox.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep line numbers in crash reports (the source file name is hidden).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The bundled console pages call these through the WebView's JavaScript bridge.
-keepclassmembers class io.github.lucabarbalata.anprox.ui.console.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Glance creates the widgets' action callbacks by class name.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Small @Serializable records the app keeps on the device.
-keep @kotlinx.serialization.Serializable class io.github.lucabarbalata.anprox.storage.** { *; }

# JSch (the SSH client) creates its algorithm classes by name from its configuration.
-keep class com.jcraft.jsch.** { *; }
# Optional integrations JSch and Bouncy Castle refer to but the app doesn't use (agents, logging
# frameworks, Kerberos, LDAP certificate stores).
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
