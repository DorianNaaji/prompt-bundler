plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

version = "0.1.0"

kotlin {
    jvmToolchain(21)
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
    }
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
}
