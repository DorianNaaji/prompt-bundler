package dev.promptbundler.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Golden tests for the full assembly pipeline. Each test pins the assembled meta-prompt
 * against a reference file in `assets/prompts/golden/`.
 */
class PromptAssemblerTest {
    @Test
    fun `single file at root`() {
        val request =
            BundleRequest(
                query = "Explain what this project does.",
                items = listOf(ContextItem("README.md", "# Demo\n\nA tiny project.\n")),
            )
        GoldenSupport.assertMatches("single-file.txt", PromptAssembler.assemble(request).text)
    }

    @Test
    fun `multiple files at root`() {
        val request =
            BundleRequest(
                query = "Review these files.",
                items =
                    listOf(
                        ContextItem("build.gradle.kts", "plugins { kotlin(\"jvm\") }\n"),
                        ContextItem("README.md", "# Demo\n"),
                    ),
            )
        GoldenSupport.assertMatches("multiple-files.txt", PromptAssembler.assemble(request).text)
    }

    @Test
    fun `nested directories`() {
        val request =
            BundleRequest(
                query = "Why does the test fail?",
                items =
                    listOf(
                        ContextItem("src/main/kotlin/App.kt", "fun main() = println(\"hi\")\n"),
                        ContextItem("src/test/kotlin/AppTest.kt", "// TODO\n"),
                        ContextItem("README.md", "# Demo\n"),
                    ),
            )
        GoldenSupport.assertMatches("nested-dirs.txt", PromptAssembler.assemble(request).text)
    }

    @Test
    fun `empty query`() {
        val request =
            BundleRequest(
                query = "",
                items = listOf(ContextItem("notes.txt", "just some notes\n")),
            )
        GoldenSupport.assertMatches("empty-query.txt", PromptAssembler.assemble(request).text)
    }

    @Test
    fun `selection snippet carries its line range`() {
        val request =
            BundleRequest(
                query = "Why does this loop never terminate?",
                items =
                    listOf(
                        ContextItem("README.md", "# Demo\n"),
                        ContextItem(
                            relativePath = "src/main/kotlin/App.kt",
                            content = "while (true) {\n    work()\n}\n",
                            lines = 10..12,
                        ),
                    ),
            )
        GoldenSupport.assertMatches("selection-snippet.txt", PromptAssembler.assemble(request).text)
    }

    @Test
    fun `paths with special characters`() {
        val request =
            BundleRequest(
                query = "Check the localized assets.",
                items =
                    listOf(
                        ContextItem("docs/résumé final.md", "Café\n"),
                        ContextItem("a.b.c/d.e.f.txt", "dots\n"),
                    ),
            )
        GoldenSupport.assertMatches("special-chars.txt", PromptAssembler.assemble(request).text)
    }

    @Test
    fun `output is independent of item order`() {
        val a = ContextItem("src/main/App.kt", "main\n")
        val b = ContextItem("src/test/AppTest.kt", "test\n")
        val c = ContextItem("README.md", "readme\n")

        val one = PromptAssembler.assemble(BundleRequest("Q", listOf(a, b, c))).text
        val two = PromptAssembler.assemble(BundleRequest("Q", listOf(c, a, b))).text
        val three = PromptAssembler.assemble(BundleRequest("Q", listOf(b, c, a))).text

        assertEquals(one, two, "order must not affect output")
        assertEquals(one, three, "order must not affect output")
        GoldenSupport.assertMatches("stable-order.txt", one)
    }

    @Test
    fun `content containing a placeholder token is not re-expanded`() {
        val request =
            BundleRequest(
                query = "Q",
                items = listOf(ContextItem("tricky.txt", "literally {query} and {tree}\n")),
            )
        val text = PromptAssembler.assemble(request).text
        // The file content keeps its literal braces; only the template tokens were filled.
        assert(text.contains("literally {query} and {tree}")) { "placeholder token in content was wrongly expanded" }
    }
}
