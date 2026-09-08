package com.dshui.idea.action

import com.dshui.idea.session.DshSessionStore
import com.dshui.idea.ui.AtMention
import com.dshui.idea.ui.DshSessionsController
import com.dshui.idea.ui.SELECTED_SESSION
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

private fun controller(project: Project?): DshSessionsController? =
    project?.getService(DshSessionsController::class.java)

private fun notifyError(project: Project?, message: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("dsh-tui")
        .createNotification(message, NotificationType.ERROR)
        .notify(project)
}

private fun notifyInfo(project: Project?, message: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("dsh-tui")
        .createNotification(message, NotificationType.INFORMATION)
        .notify(project)
}

/** 启动新会话：每次点击在 DeepSeek 工具窗开一个新终端标签+会话，已有会话各自继续（对齐 Claude Code）。 */
class DshStartSessionAction : AnAction("新建会话", "在 DeepSeek 工具窗中启动新的 dsh-tui 会话", AllIcons.Actions.AddFile) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        controller(e.project)?.launcher?.startNewSession()
    }
}

/** 恢复上次会话：--resume 读取 ~/.dsh-tui/resume.txt。 */
class DshResumeLastAction : AnAction("恢复上次会话", "在 DeepSeek 工具窗中恢复上次的 dsh-tui 会话", AllIcons.Actions.Restart) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        controller(e.project)?.launcher?.resumeLastSession()
    }
}

/** 全局快捷键（默认 Alt+D）：聚焦运行中的会话；没有则新建（对齐 VSCode 版 focus 命令）。 */
class DshFocusOrStartAction : AnAction("聚焦/新建会话", "聚焦运行中的 DeepSeek 会话；没有则新建（Alt+D）", AllIcons.Actions.Find) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        controller(e.project)?.launcher?.focusOrStart()
    }
}

class DshRefreshAction : AnAction("刷新", "刷新会话列表", AllIcons.Actions.Refresh) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        controller(e.project)?.refresh()
    }
}

/** 归档会话（隐藏但保留日志与位置，可随时恢复）。 */
class DshArchiveSessionAction : AnAction("归档", "归档会话（隐藏但保留日志，可恢复）", AllIcons.Nodes.Favorite) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(SELECTED_SESSION) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val rec = e.getData(SELECTED_SESSION) ?: return
        val c = controller(project) ?: return
        if (DshSessionStore.setSessionArchived(rec.id, archived = true, dshHome = c.dshHome())) {
            c.refresh()
        } else {
            notifyError(project, "归档失败：无法写入会话域存储")
        }
    }
}

/** 重命名：向日志追加一帧 session/title 事件（读取侧最后标题生效）。 */
class DshRenameSessionAction : AnAction("重命名", "重命名会话标题", AllIcons.Actions.Edit) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(SELECTED_SESSION) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val rec = e.getData(SELECTED_SESSION) ?: return
        val c = controller(project) ?: return
        val title = Messages.showInputDialog(
            project,
            "重命名会话 ${rec.id.take(8)}…",
            "输入新标题",
            null,
            rec.title ?: "",
            null,
        )?.trim() ?: return // 取消
        if (title.isEmpty()) return
        if (DshSessionStore.appendSessionTitle(java.nio.file.Path.of(rec.file), title)) {
            c.refresh()
        } else {
            notifyError(project, "重命名失败：会话日志不可写")
        }
    }
}

/** 永久删除：递归删除会话日志目录（带路径包含校验，不可恢复）。 */
class DshDeleteSessionAction : AnAction("删除", "永久删除会话日志目录", AllIcons.General.Delete) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(SELECTED_SESSION) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val rec = e.getData(SELECTED_SESSION) ?: return
        val c = controller(project) ?: return
        val answer = Messages.showYesNoDialog(
            project,
            "永久删除会话 ${rec.id.take(8)}…？其日志目录将被彻底移除，此操作不可撤销。建议先归档。",
            "永久删除会话",
            "永久删除",
            "取消",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        if (DshSessionStore.deleteSessionLog(java.nio.file.Path.of(rec.file), c.dshHome())) {
            c.refresh()
        }
    }
}

class DshCopySessionIdAction : AnAction("复制会话 ID", "复制会话 ID 到剪贴板", AllIcons.Actions.Copy) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(SELECTED_SESSION) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val rec = e.getData(SELECTED_SESSION) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(rec.id))
        notifyInfo(e.project, "已复制会话 ID：${rec.id.take(16)}…")
    }
}

/** 双击/菜单「恢复会话」：注入 DSH_TUI_RESUME_SESSION 在 DeepSeek 工具窗开新标签恢复该会话。 */
class DshResumeSessionAction : AnAction("恢复会话", "在 DeepSeek 工具窗中恢复此会话", AllIcons.Actions.Execute) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(SELECTED_SESSION) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val rec = e.getData(SELECTED_SESSION) ?: return
        controller(e.project)?.launcher?.resumeSession(rec.id)
    }
}

/** 管理已归档会话：列出归档集，选择后可恢复或永久删除。 */
class DshManageArchivedAction : AnAction("管理已归档", "查看/恢复/彻底删除已归档会话", AllIcons.Nodes.Folder) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val c = controller(project) ?: return
        val dshHome = c.dshHome()
        val archivedIds = DshSessionStore.readWorkspaceMeta(dshHome)
        if (archivedIds.isEmpty()) {
            notifyInfo(project, "没有已归档的会话")
            return
        }
        val all = DshSessionStore.listSessions(dshHome, DshSessionStore.ListOptions())
        val byId = all.associateBy { it.id }
        data class ArchivedItem(val id: String, val rec: com.dshui.idea.session.DshSessionRecord?, val label: String)
        val items = archivedIds.map { id ->
            val rec = byId[id]
            val whenMs = rec?.lastUsed ?: rec?.createdAt
            val whenText = if (rec != null && whenMs != null) AtMention.relativeTime(whenMs) else "日志缺失"
            val title = rec?.title?.trim().takeUnless { it.isNullOrEmpty() } ?: id.take(12)
            ArchivedItem(id, rec, "$title（$whenText）")
        }
        val labels = items.map { it.label }.toTypedArray()
        val picked = Messages.showChooseDialog(
            project,
            "选择要管理的已归档会话",
            "已归档会话",
            null,
            labels,
            labels.firstOrNull(),
        )
        if (picked < 0 || picked >= items.size) return
        val item = items[picked]
        val action = Messages.showChooseDialog(
            project,
            "会话：${item.label}",
            "选择操作",
            null,
            arrayOf("恢复会话", "彻底删除"),
            "恢复会话",
        )
        when (action) {
            0 -> {
                if (DshSessionStore.setSessionArchived(item.id, archived = false, dshHome = dshHome)) {
                    c.refresh()
                    notifyInfo(project, "会话已恢复")
                } else {
                    notifyError(project, "恢复失败：无法写入会话域存储")
                }
            }
            1 -> {
                val rec = item.rec
                if (rec == null) {
                    notifyError(project, "日志缺失，无法删除")
                    return
                }
                val confirm = Messages.showYesNoDialog(
                    project,
                    "永久删除归档会话 ${item.id.take(8)}…？日志目录将被彻底移除，不可恢复。",
                    "永久删除会话",
                    "永久删除",
                    "取消",
                    Messages.getWarningIcon(),
                )
                if (confirm != Messages.YES) return
                if (DshSessionStore.deleteSessionLog(java.nio.file.Path.of(rec.file), dshHome)) {
                    // 日志已删，同步移出归档集
                    DshSessionStore.setSessionArchived(item.id, archived = false, dshHome = dshHome)
                    c.refresh()
                }
            }
        }
    }
}
