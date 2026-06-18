package dev.promptbundler.plugin.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import dev.promptbundler.engine.BundleRequest
import dev.promptbundler.engine.PromptAssembler
import java.awt.datatransfer.StringSelection

/**
 * Non-UI seam between the chat panel and the engine.
 *
 * It is deliberately free of Swing so the wiring (query -> engine -> meta-prompt) and the
 * clipboard behaviour can be covered by platform tests without driving widgets.
 */
class PromptChatController {
    /**
     * Assembles the meta-prompt for a user [query]. Context attachment lands in M3, so for
     * now the bundle carries an empty item list: the question alone drives the output.
     */
    fun buildMetaPrompt(query: String): String = PromptAssembler.assemble(BundleRequest(query, emptyList())).text

    /** Places [text] verbatim on the system clipboard. */
    fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}
