package dev.promptbundler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import dev.promptbundler.plugin.context.AttachmentNotifications
import dev.promptbundler.plugin.context.Attachments
import dev.promptbundler.plugin.context.ContextBundleService

/**
 * Project view action: attaches the selected file(s) or folder(s) to PromptBundler. Folders
 * are added recursively; ineligible files are skipped with a discreet balloon.
 */
class AddFilesToBundleAction :
    AnAction(),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = e.project != null && !files.isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        val result = Attachments.collect(project, files)
        ContextBundleService.getInstance(project).add(result.items)
        AttachmentNotifications.notifySkipped(project, result.skipped)
    }
}
