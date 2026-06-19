package dev.promptbundler.plugin.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.promptbundler.engine.BundleRequest
import dev.promptbundler.engine.PromptAssembler
import dev.promptbundler.plugin.context.ContextBundleService
import java.awt.datatransfer.StringSelection

/**
 * Non-UI seam between the chat panel and the engine.
 *
 * It is deliberately free of Swing so the wiring (query plus attached context -> engine ->
 * meta-prompt) and the clipboard behaviour can be covered by platform tests without driving
 * widgets.
 */
class PromptChatController(
    private val project: Project,
) {
    /**
     * Assembles the meta-prompt for a user [query], pulling the live attached context from
     * [ContextBundleService]. With nothing attached the bundle carries an empty item list, so
     * the question alone drives the output.
     */
    fun buildMetaPrompt(query: String): String {
        val items = ContextBundleService.getInstance(project).items
        return PromptAssembler.assemble(BundleRequest(query, items)).text
    }

    /** Places [text] verbatim on the system clipboard. */
    fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
}
