package dev.promptbundler.plugin.context

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Discreet balloons reporting attachment outcomes, routed through the `PromptBundler` group. */
object AttachmentNotifications {
    private const val GROUP_ID = "PromptBundler"

    /** Tells the user that [skipped] files were left out (no balloon when nothing was skipped). */
    fun notifySkipped(
        project: Project,
        skipped: Int,
    ) {
        if (skipped <= 0) return
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "Skipped $skipped file(s) (binary, too large, or ignored)",
                NotificationType.INFORMATION,
            ).notify(project)
    }
}
