package com.dshui.idea.action

import com.dshui.idea.settings.DshSettings
import com.dshui.idea.ui.AtMention
import com.dshui.idea.ui.DshSessionsController
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.awt.datatransfer.StringSelection

/**
 * 编辑器右键「插入 @文件引用」：把当前文件/选中代码以 `@绝对路径 L起-止`
 * 形式键入运行中的 dsh-tui 输入框（不自动提交，用户可继续补问题）；没有
 * 运行中的会话时回退复制到剪贴板（对齐 VSCode 版 insertAtMention）。
 */
class DshInsertAtMentionAction :
    AnAction("插入 @文件引用", "把当前文件/选中代码以 @引用 形式插入 dsh-tui 输入框", AllIcons.Actions.MenuPaste) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            notify(project, "请先聚焦一个编辑器，再插入 @文件引用")
            return
        }
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        if (file == null) {
            notify(project, "当前编辑器不是文件，无法生成 @引用")
            return
        }
        val mention = AtMention.buildAtMention(
            AtMention.normalizeMentionPath(file.path),
            selectionIsEmpty(editor),
            selectionStartLine(editor),
            selectionEndLine(editor),
        )
        val controller = project.getService(DshSessionsController::class.java)
        if (controller.launcher.sendInput(mention)) return
        // 无运行中的 dsh-tui 会话：回退为复制到剪贴板
        CopyPasteManager.getInstance().setContents(StringSelection(mention))
        notify(project, "已复制 $mention，请粘贴到 dsh-tui 输入框")
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dsh-tui")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun selectionIsEmpty(editor: com.intellij.openapi.editor.Editor): Boolean =
        !editor.selectionModel.hasSelection()

    private fun selectionStartLine(editor: com.intellij.openapi.editor.Editor): Int =
        editor.document.getLineNumber(editor.selectionModel.selectionStart)

    private fun selectionEndLine(editor: com.intellij.openapi.editor.Editor): Int =
        editor.document.getLineNumber(editor.selectionModel.selectionEnd)
}

/**
 * 选区变化自动引用（实验性、默认关，对齐 VSCode 版 autoInsertMention 的降级
 * 近似）：选中代码变化 → 300ms 防抖 → 把 `@绝对路径 L起-止` 键入运行中的
 * dsh-tui 输入框；仅当存在运行中的会话时注入、同一选区去重，避免抢占/刷屏。
 */
class DshAutoMention(private val project: Project, parentDisposable: Disposable) {

    private val alarm = Alarm(parentDisposable)
    private var lastInserted: String? = null

    init {
        com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    val settings = DshSettings.getInstance().state
                    if (!settings.autoInsertMention) return
                    val editor = e.editor
                    if (editor.project !== project) return
                    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
                    if (e.newRange.isEmpty) return // 光标移动（无选区）不注入
                    val startLine = editor.document.getLineNumber(e.newRange.startOffset)
                    val endLine = editor.document.getLineNumber(e.newRange.endOffset)
                    val mention = AtMention.buildAtMention(
                        AtMention.normalizeMentionPath(file.path),
                        isEmpty = false,
                        startLine = startLine,
                        endLine = endLine,
                    )
                    if (mention == lastInserted) return
                    alarm.cancelAllRequests()
                    alarm.addRequest({
                        val controller = project.getService(DshSessionsController::class.java)
                        if (controller.launcher.hasTerminal() && controller.launcher.sendInput(mention)) {
                            lastInserted = mention
                        }
                    }, 300)
                }
            },
            parentDisposable,
        )
    }
}
