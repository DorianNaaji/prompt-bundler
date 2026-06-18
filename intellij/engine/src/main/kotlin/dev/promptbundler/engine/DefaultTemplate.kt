package dev.promptbundler.engine

/**
 * Loads the default prompt template embedded from `assets/prompts/`.
 *
 * The template file is the single source of truth shared by every editor integration; the
 * Gradle build copies it onto the engine classpath (see `build.gradle.kts`). Keeping it as
 * a resource, rather than a string literal, guarantees the IntelliJ plugin and any future
 * port assemble against the exact same template.
 */
object DefaultTemplate {
    private const val RESOURCE_PATH = "/dev/promptbundler/engine/default-template.md"

    /** The default template text, with `{tree}`, `{files}` and `{query}` placeholders. */
    val text: String by lazy { load() }

    private fun load(): String {
        val stream =
            DefaultTemplate::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: error("Default template not found on classpath: $RESOURCE_PATH")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
