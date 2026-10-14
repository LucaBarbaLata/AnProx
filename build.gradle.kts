// Root build file. Plugins are declared here with `apply false` so every module shares
// one copy of each plugin (Gradle requires this for the Kotlin + Android plugins to see
// each other). Modules then apply what they need via `alias(libs.plugins.xxx)`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
