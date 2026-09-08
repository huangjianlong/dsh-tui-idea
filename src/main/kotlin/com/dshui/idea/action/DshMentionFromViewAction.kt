package com.dshui.idea.action

import com.dshui.idea.ui.AtMention
import com.dshui.idea.ui.DshSessionsController
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

/**
 * 项目树右键「插入 @引用」：把选中的文件/目录（支持多选）以 `@绝对路径`
 * 形式键入运行中的 dsh-tui 输入框；目录引用不带行号。没有运行中的会话时
 * 回退复制到剪贴板（对齐编辑器侧 insertAtMention 的行为）。
 */
class DshMentionFromViewAction :
    AnAction("插入 @引用", "把选中的文件/目录以 @引用 形式插入 dsh-tui 输入框", AllIcons.Actions.MenuPaste) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = !e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (files.isNullOrEmpty()) return
        val mention = files.joinToString(" ") { vf ->
            AtMention.buildAtMention(AtMention.normalizeMentionPath(vf.path), isEmpty = true, startLine = 0, endLine = 0)
        }
        val controller = project.getService(DshSessionsController::class.java)
        if (controller.launcher.sendInput(mention)) return
        CopyPasteManager.getInstance().setContents(StringSelection(mention))
        notify(project, "已复制 ${files.size} 个 @引用，请粘贴到 dsh-tui 输入框")
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dsh-tui")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
