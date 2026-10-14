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
