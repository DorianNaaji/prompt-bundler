package dev.promptbundler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import dev.promptbundler.plugin.context.Attachments
import dev.promptbundler.plugin.context.ContextBundleService

/**
 * Editor action: attaches the current selection as a snippet, tagged with its file path and
 * 1-based, inclusive line range. Enabled only while text is selected.
 */
class AddSelectionToContextAction :
    AnAction(),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val selection = editor.selectionModel
        val text = selection.selectedText ?: return

        val document = editor.document
        val startLine = document.getLineNumber(selection.selectionStart) + 1
        val endLine = document.getLineNumber(selection.selectionEnd) + 1
        val item = Attachments.snippet(project, file, text, startLine..endLine)
        ContextBundleService.getInstance(project).add(listOf(item))
    }
}
