// :tools:mock-server — a fake Proxmox VE + Backup Server API built with Ktor.
//  * Unit tests in :core:network / :core:data start it in-process on a random port.
//  * Run it by hand to try the app without real hardware:
//        ./gradlew :tools:mock-server:run
//    then add a server in the app at https://10.0.2.2:8006 (Android emulator → host machine)
//    with "Allow insecure certificate" switched on. See docs/testing.md.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("io.github.lucabarbalata.anprox.mock.MainKt")
}

dependencies {
    implementation(project(":core:model"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.network.tls.certificates)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
