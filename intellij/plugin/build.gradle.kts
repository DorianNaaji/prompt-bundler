import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

version = "1.0.2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Compile inherited Kotlin-interface default members as real JVM defaults instead
        // of delegating bridges. Without this, implementing ToolWindowFactory generates
        // synthetic overrides of its @ApiStatus.Internal members (anchor/icon/manage),
        // which the Plugin Verifier flags as INTERNAL_API_USAGES.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":engine"))

    intellijPlatform {
        // Build against the latest stable Community release available as a Gradle
        // dependency. Compatibility with newer IDEs (2025.2+, 2026.x and beyond) comes
        // from the open untilBuild below and is confirmed by the Plugin Verifier.
        intellijIdeaCommunity("2025.1.7")

        // Platform fixtures for BasePlatformTestCase (registration + clipboard tests).
        testFramework(TestFrameworkType.Platform)
    }

    // BasePlatformTestCase is a JUnit3/4 test case, so the plugin keeps JUnit4 for its
    // platform tests. The engine module stays on JUnit5 independently.
    testImplementation(libs.junit4)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            // No upper bound: the plugin stays installable on 2026.x and every later
            // IDE without a re-release. It uses only stable platform APIs; reintroduce
            // a cap here if a future release ever breaks compatibility.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    publishing {
        token = providers.gradleProperty("intellijPublishToken")
    }
}
