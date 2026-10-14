// :app — the Android application: screens, ViewModels, navigation, dependency injection
// (Koin) and the Android implementations of the storage interfaces from :core:data.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------- versioning (docs/releasing.md)
//
//   versionName  MAJOR.MINOR.PATCH[-alpha.N | -beta.N | -rc.N]       e.g. 0.1.0-beta.1
//   versionCode  MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + stage
//                stage: alpha.N → N (1–19) · beta.N → 20+N (21–59) · rc.N → 60+N (61–98) · final → 99
//
// Every pre-release therefore sorts below its final release, and Android installs updates in
// order. The name comes from gradle.properties; the release workflow overrides it from the tag.
val appVersionName: String = providers.gradleProperty("anprox.versionName").get()
val appVersionCode: Int = versionCodeFor(appVersionName)

fun versionCodeFor(name: String): Int {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$""").matchEntire(name)
        ?: error("anprox.versionName '$name' must look like 1.2.3 or 1.2.3-beta.4")
    val (major, minor, patch) = match.destructured.toList().take(3).map { it.toInt() }
    val n = match.groupValues[5].toIntOrNull() ?: 0
    val stage = when (match.groupValues[4]) {
        "alpha" -> n.also { require(it in 1..19) { "alpha number must be 1–19" } }
        "beta" -> (20 + n).also { require(n in 1..39) { "beta number must be 1–39" } }
        "rc" -> (60 + n).also { require(n in 1..38) { "rc number must be 1–38" } }
        else -> 99
    }
    require(minor < 100 && patch < 100) { "minor and patch must be below 100" }
    return major * 1_000_000 + minor * 10_000 + patch * 100 + stage
}

// ---------------------------------------------------------------- signing (docs/releasing.md)
//
// The release key is never committed. Values come from environment variables (GitHub Actions
// secrets) or from a git-ignored keystore.properties file in the project root:
//   storeFile=/path/to/anprox-release.jks   storePassword=…   keyAlias=anprox   keyPassword=…
// -Panprox.signWithDebugKey=true signs release builds with the debug key instead, so CI can
// install and test the real minified APK without access to the release key.
val keystoreProperties = java.util.Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
        .asText.orNull?.let { load(it.reader()) }
}

fun signingValue(env: String, property: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(property)

val releaseStoreFile: String? = signingValue("ANPROX_KEYSTORE_FILE", "storeFile")
val signWithDebugKey: Boolean = providers.gradleProperty("anprox.signWithDebugKey").orNull == "true"

android {
    namespace = "io.github.lucabarbalata.anprox"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lucabarbalata.anprox"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("ANPROX_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ANPROX_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ANPROX_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // A different launcher name so debug and release builds can't be confused.
            manifestPlaceholders["appLabel"] = "AnProx Debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["appLabel"] = "@string/app_name"
            signingConfig = when {
                releaseStoreFile != null -> signingConfigs.getByName("release")
                signWithDebugKey -> signingConfigs.getByName("debug")
                else -> null // unsigned; fine for CI compile checks, not installable
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/versions/9/previous-compilation-data.bin",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
            )
        }
    }

    // F-Droid / reproducible builds: don't embed the encrypted dependency report Google Play uses.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        // Translations arrive later (questionnaire Q16); don't fail on missing ones meanwhile.
        disable += "MissingTranslation"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
