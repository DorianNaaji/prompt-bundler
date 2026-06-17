plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    // The engine is pure Kotlin. It compiles against the stdlib, but the stdlib is
    // never bundled into the plugin: the IntelliJ Platform provides it at runtime.
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
