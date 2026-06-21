package dev.promptbundler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import dev.promptbundler.plugin.context.Attachments
import dev.promptbundler.plugin.context.ContextBundleService

/**
 * Console action: captures the output of a Run / Services / test-runner console and attaches
 * it as a path-less snippet. The selection is captured when present, otherwise the whole
 * console text. Enabled only while a non-empty console editor is in context.
 *
 * The backing console editor is the stable source of truth across IDE versions: it is read
 * from [CommonDataKeys.EDITOR], which the console popup menu provides.
 */
class AddConsoleOutputToContextAction :
    AnAction(),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && editor.document.textLength > 0
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = capture(editor)
        if (text.isEmpty()) return
        val item = Attachments.consoleSnippet(text, labelFor(e))
        ContextBundleService.getInstance(project).add(listOf(item))
    }

    /** Selection when one exists, otherwise the full console document. */
    private fun capture(editor: Editor): String {
        val selected = editor.selectionModel.selectedText
        return if (!selected.isNullOrEmpty()) selected else editor.document.text
    }

    /** `Console output`, suffixed with the run configuration name when one is available. */
    private fun labelFor(e: AnActionEvent): String {
        val runName = e.getData(LangDataKeys.RUN_PROFILE)?.name
        return if (runName.isNullOrBlank()) "Console output" else "Console output (Run: $runName)"
    }
}
