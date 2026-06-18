package dev.promptbundler.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TreeRendererTest {
    @Test
    fun `empty input renders empty string`() {
        assertEquals("", TreeRenderer.render(emptyList()))
    }

    @Test
    fun `single root file`() {
        assertEquals("README.md", TreeRenderer.render(listOf("README.md")))
    }

    @Test
    fun `directories come before files and connectors are used for descendants`() {
        val tree =
            TreeRenderer.render(
                listOf(
                    "README.md",
                    "src/test/AppTest.kt",
                    "src/main/App.kt",
                ),
            )
        val expected =
            """
            src
            ├── main
            │   └── App.kt
            └── test
                └── AppTest.kt
            README.md
            """.trimIndent()
        assertEquals(expected, tree)
    }
}
