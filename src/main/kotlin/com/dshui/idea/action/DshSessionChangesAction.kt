package com.dshui.idea.action

import com.dshui.idea.session.DshSessionChanges
import com.dshui.idea.session.DshSessionRecord
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.dshui.idea.ui.SELECTED_SESSION
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * 会话右键「查看改动」：整读会话日志，从 tool/call 提取编辑过的文件列表
 * （edit/write/str_replace_editor 非 view），弹窗展示每文件的编辑/新建次数
 * 与最近操作时间，双击或「打开」在编辑器中打开该文件。
 */
class DshSessionChangesAction :
    AnAction("查看改动", "列出此会话编辑/新建过的文件", AllIcons.Actions.PreviewDetails) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(SELECTED_SESSION) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val rec = e.getData(SELECTED_SESSION) ?: return
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val changes = DshSessionChanges.extract(Path.of(rec.file))
            app.invokeLater {
                if (project.isDisposed) return@invokeLater
                when {
                    changes == null -> notify(project, "会话日志过大或不可解码，无法提取改动清单。")
                    changes.isEmpty() -> notify(project, "会话「${rec.title ?: rec.id.take(8)}」没有文件改动记录。")
                    else -> DshChangesDialog(project, rec, changes).show()
                }
            }
        }
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dsh-tui")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}

/** 改动清单对话框：文件 × 编辑/新建次数 × 最近时间；双击打开文件。 */
class DshChangesDialog(
    private val project: Project,
    rec: DshSessionRecord,
    private val changes: List<DshSessionChanges.Change>,
) : DialogWrapper(project) {

    private val table: JBTable

    init {
        title = "改动 · ${rec.title ?: rec.id.take(8)}"
        val model = object : DefaultTableModel(
            arrayOf("文件", "编辑", "新建", "最近操作"),
            changes.size,
        ) {
            override fun isCellEditable(row: Int, column: Int) = false

            override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
                1, 2 -> java.lang.Integer::class.java
                3 -> java.lang.Long::class.java
                else -> String::class.java
            }
        }
        for ((i, c) in changes.withIndex()) {
            model.setValueAt(c.path, i, 0)
            model.setValueAt(c.edits, i, 1)
            model.setValueAt(c.creates, i, 2)
            model.setValueAt(c.lastAtMs, i, 3)
        }
        table = JBTable(model)
        table.autoCreateRowSorter = true
        table.setDefaultRenderer(java.lang.Long::class.java, object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                t: javax.swing.JTable?,
                v: Any?,
                sel: Boolean,
                foc: Boolean,
                row: Int,
                col: Int,
            ): Component {
                super.getTableCellRendererComponent(t, v, sel, foc, row, col)
                text = relativeTime((v as? Number)?.toLong() ?: 0L)
                return this
            }
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected(project)
            }
        })
        setOKButtonText("打开")
        init()
    }

    private fun openSelected(project: Project) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val modelRow = table.convertRowIndexToModel(row)
        val path = changes.getOrNull(modelRow)?.path ?: return
        val vf = VirtualFileManager.getInstance().findFileByNioPath(Path.of(path))
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        } else {
            notifyMissing(project, path)
        }
    }

    private fun notifyMissing(project: Project, path: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dsh-tui")
            .createNotification("文件不存在：$path", NotificationType.WARNING)
            .notify(project)
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(5, 5)).apply {
        add(
            JBScrollPane(table).apply {
                preferredSize = java.awt.Dimension(680, 360)
            },
            BorderLayout.CENTER,
        )
    }

    override fun doOKAction() {
        openSelected(project)
        close(OK_EXIT_CODE)
    }

    private fun relativeTime(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        val diff = System.currentTimeMillis() - epochMs
        val minutes = diff / 60000
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            else -> "${minutes / 60 / 24} 天前"
        }
    }
}
