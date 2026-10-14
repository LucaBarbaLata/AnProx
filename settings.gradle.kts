/*
 * AnProx — Gradle settings.
 *
 * Module map (see docs/architecture.md for the "why"):
 *   :core:model         pure Kotlin  — data classes for the Proxmox API + formatting helpers
 *   :core:network       pure Kotlin  — HTTP client, auth (ticket + TFA), endpoint failover, TLS
 *   :core:data          pure Kotlin  — repositories, multi-server hub, polling; storage interfaces
 *   :core:designsystem  Android      — "Midnight Glass" theme and reusable Compose components
 *   :app                Android      — screens, ViewModels, DI wiring, Android storage implementations
 *   :tools:mock-server  pure Kotlin  — fake PVE/PBS API used by tests and for manual testing
 *
 * JVM-only mode: pass -Panprox.jvmOnly=true (or set ANPROX_JVM_ONLY=true) to build just the
 * pure-Kotlin modules. This exists for environments without the Android SDK / Google Maven
 * (for example sandboxed CI agents). Android Studio users never need it.
 */
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "AnProx"

val jvmOnly = providers.gradleProperty("anprox.jvmOnly").orNull == "true" ||
    System.getenv("ANPROX_JVM_ONLY") == "true"

include(":core:model")
include(":core:network")
include(":core:data")
include(":tools:mock-server")

if (jvmOnly) {
    // The root build file declares the Android Gradle Plugin; swap it for one that doesn't,
    // so Gradle never tries to download it.
    rootProject.buildFileName = "build.jvm.gradle.kts"
} else {
    include(":core:designsystem")
    include(":app")
}
