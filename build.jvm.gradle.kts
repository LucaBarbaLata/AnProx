// Root build file used only in JVM-only mode (see settings.gradle.kts).
// Same as build.gradle.kts minus the Android plugins.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
