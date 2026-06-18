package dev.promptbundler.plugin.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.promptbundler.engine.BundleRequest
import dev.promptbundler.engine.PromptAssembler
import java.awt.datatransfer.DataFlavor

class PromptChatControllerTest : BasePlatformTestCase() {
    private val controller = PromptChatController()

    fun testBuildMetaPromptMatchesEngineOutput() {
        val query = "Explain the assembly engine"
        val expected = PromptAssembler.assemble(BundleRequest(query, emptyList())).text

        assertEquals(expected, controller.buildMetaPrompt(query))
    }

    fun testCopyToClipboardPutsExactTextOnSystemClipboard() {
        val text = controller.buildMetaPrompt("Anything")

        controller.copyToClipboard(text)

        val clipboard = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        assertEquals(text, clipboard)
    }
}
