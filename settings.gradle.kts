plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "prompt-bundler"

include(
    ":engine",
    ":plugin",
)

project(":engine").projectDir = file("intellij/engine")
project(":plugin").projectDir = file("intellij/plugin")
