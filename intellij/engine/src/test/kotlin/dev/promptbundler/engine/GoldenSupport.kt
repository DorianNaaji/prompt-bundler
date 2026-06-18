package dev.promptbundler.engine

import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File

/**
 * Golden-file helper for the engine tests.
 *
 * A golden test runs the assembler on a fixed input and compares the output, byte for
 * byte, against a checked-in reference in `assets/prompts/golden/`. Those files are the
 * shared contract: the IntelliJ engine and any future port must reproduce them exactly.
 *
 * Run with `-Dgolden.record=true` to (re)generate the reference files from the current
 * engine output; the regenerated files must then be reviewed by a human before commit.
 */
object GoldenSupport {
    private val record: Boolean = System.getProperty("golden.record") == "true"

    /** Source directory of the golden files, relative to the engine project dir. */
    private val goldenSourceDir = File("../../assets/prompts/golden")

    fun assertMatches(
        name: String,
        actual: String,
    ) {
        if (record) {
            val target = File(goldenSourceDir, name)
            target.parentFile.mkdirs()
            target.writeText(actual, Charsets.UTF_8)
            return
        }

        val expected =
            GoldenSupport::class.java
                .getResourceAsStream("/golden/$name")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Missing golden file: $name (run with -Dgolden.record=true to seed it)")

        assertEquals(expected, actual, "Golden mismatch for $name")
    }
}
