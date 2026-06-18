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

// The default template lives in assets/prompts/ (the single source of truth shared by
// every editor integration). Embed it on the engine classpath so DefaultTemplate can load
// it, and ship it inside the engine jar that the plugin bundles.
tasks.processResources {
    from("../../assets/prompts/templates/default-template.md") {
        into("dev/promptbundler/engine")
    }
}

// Golden files validate the assembled output. They are test-only: never shipped in the jar.
tasks.named<ProcessResources>("processTestResources") {
    from("../../assets/prompts/golden") {
        into("golden")
    }
}

tasks.test {
    useJUnitPlatform()
    // Forward the golden-file record switch to the forked test JVM.
    System.getProperty("golden.record")?.let { systemProperty("golden.record", it) }
}
