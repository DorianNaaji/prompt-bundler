package dev.promptbundler.plugin.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.promptbundler.engine.BundleRequest
import dev.promptbundler.engine.ContextItem
import dev.promptbundler.engine.PromptAssembler
import dev.promptbundler.plugin.context.ContextBundleService
import java.awt.datatransfer.DataFlavor

class PromptChatControllerTest : BasePlatformTestCase() {
    private lateinit var controller: PromptChatController

    override fun setUp() {
        super.setUp()
        // The light fixture reuses one project across methods, so reset the shared context.
        ContextBundleService.getInstance(project).clear()
        controller = PromptChatController(project)
    }

    fun testBuildMetaPromptMatchesEngineOutput() {
        val query = "Explain the assembly engine"
        val expected = PromptAssembler.assemble(BundleRequest(query, emptyList())).text

        assertEquals(expected, controller.buildMetaPrompt(query))
    }

    fun testBuildMetaPromptFoldsInAttachedContext() {
        val item = ContextItem("README.md", "# Demo\n")
        ContextBundleService.getInstance(project).add(listOf(item))

        val query = "Summarize the project"
        val expected = PromptAssembler.assemble(BundleRequest(query, listOf(item))).text

        val actual = controller.buildMetaPrompt(query)
        assertEquals(expected, actual)
        assertTrue("attached file should appear in the prompt", actual.contains("<file path=\"README.md\">"))
    }

    fun testCopyToClipboardPutsExactTextOnSystemClipboard() {
        val text = controller.buildMetaPrompt("Anything")

        controller.copyToClipboard(text)

        val clipboard = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        assertEquals(text, clipboard)
    }
}
