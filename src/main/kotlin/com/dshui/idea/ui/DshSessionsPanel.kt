package com.dshui.idea.ui

import com.dshui.idea.action.DshArchiveSessionAction
import com.dshui.idea.action.DshCopySessionIdAction
import com.dshui.idea.action.DshDeleteSessionAction
import com.dshui.idea.action.DshManageArchivedAction
import com.dshui.idea.action.DshRefreshAction
import com.dshui.idea.action.DshRenameSessionAction
import com.dshui.idea.action.DshResumeLastAction
import com.dshui.idea.action.DshResumeSessionAction
import com.dshui.idea.action.DshSetupAction
import com.dshui.idea.action.DshStartSessionAction
import com.dshui.idea.session.DshSessionRecord
import com.dshui.idea.session.DshSessionStore
import com.dshui.idea.setup.DshEnvironmentCheck
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES
import com.intellij.ui.SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** 选中会话在数据上下文里的传递键（右键/双击动作取参用）。 */
val SELECTED_SESSION: DataKey<DshSessionRecord> = DataKey.create("dsh-tui.selectedSession")

/**
 * 侧边栏会话列表：按项目分组、最近使用排序、双击恢复、右键管理
 * （形状对齐 VSCode 版的 SessionsTreeProvider）。环境未就绪时切换为
 * 「体检报告 + 一键配置」视图。
 */
class DshSessionsPanel(private val project: Project) : SimpleToolWindowPanel(true) {

    private val controller: DshSessionsController get() = project.service()

    private class ProjectGroup(val name: String, val sessions: MutableList<DshSessionRecord>)

    private val tree: JTree = JTree(DefaultMutableTreeNode("root")).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = SessionRenderer()
    }

    private val scrollPane = JBScrollPane(tree)
    private val startLink = HyperlinkLabel("启动新会话")
    private val resumeLink = HyperlinkLabel("恢复上次会话")
    private val emptyPanel: JPanel = JPanel(BorderLayout()).apply {
        add(JBLabel("还没有会话", UIUtil.ComponentStyle.REGULAR, UIUtil.FontColor.BRIGHTER), BorderLayout.NORTH)
        val links = JPanel(BorderLayout())
        links.add(startLink, BorderLayout.NORTH)
        links.add(resumeLink, BorderLayout.SOUTH)
        add(links, BorderLayout.CENTER)
        border = JBUI.Borders.empty(10)
    }

    @Volatile
    private var running = false

    init {
        TreeSpeedSearch(tree) { path -> speedSearchText(path) }
        startLink.addHyperlinkListener { controller.launcher.startNewSession() }
        resumeLink.addHyperlinkListener { controller.launcher.resumeLastSession() }
        setToolbar(buildToolbar().component)
        setContent(scrollPane)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !e.isConsumed) {
                    selectedRecord()?.let { controller.launcher.resumeSession(it.id) }
                }
            }
        })
        tree.componentPopupMenu = ActionManager.getInstance()
            .createActionPopupMenu(
                "dsh-tui-session-popup",
                DefaultActionGroup(
                    DshResumeSessionAction(),
                    DshArchiveSessionAction(),
                    DshRenameSessionAction(),
                    DshDeleteSessionAction(),
                    DshCopySessionIdAction(),
                ),
            ).component
    }

    private fun speedSearchText(path: TreePath): String? {
        val obj = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject ?: return null
        return when (obj) {
            is ProjectGroup -> obj.name
            is DshSessionRecord -> DshSessionStore.sessionLabel(obj)
            else -> null
        }
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            DshStartSessionAction(),
            DshResumeLastAction(),
            DshRefreshAction(),
            DshManageArchivedAction(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("dsh-tui-sessions", group, true)
        toolbar.targetComponent = this
        return toolbar
    }

    /** 数据上下文：把当前选中的会话传给右键动作（新动作数据链 uiDataSnapshot）。 */
    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        selectedRecord()?.let { sink.set(SELECTED_SESSION, it) }
    }

    private fun selectedRecord(): DshSessionRecord? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? DshSessionRecord

    /** 后台重载：会话列表 + 环境体检（都是文件级检查），EDT 里按状态切换视图。 */
    fun reloadAsync() {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val dshHome = controller.dshHome()
            val records = try {
                DshSessionStore.listSessions(
                    dshHome,
                    DshSessionStore.ListOptions(
                        workspaceDirs = controller.workspaceDirs(),
                        hideEmpty = true,
                        hideSubagents = true,
                        hideArchived = true,
                    ),
                )
            } catch (e: Exception) {
                emptyList()
            }
            val report = try {
                DshEnvironmentCheck.check()
            } catch (e: Exception) {
                null
            }
            app.invokeLater {
                if (!project.isDisposed) rebuild(records, report)
            }
        }
    }

    private fun rebuild(records: List<DshSessionRecord>, report: DshEnvironmentCheck.Report?) {
        if (report != null && !report.ready) {
            setContent(buildSetupPanel(report))
        } else {
            rebuildTree(records)
            setContent(scrollPane)
        }
    }

    private fun rebuildTree(records: List<DshSessionRecord>) {
        val root = DefaultMutableTreeNode("root")
        val groups = LinkedHashMap<String, ProjectGroup>()
        for (rec in records) {
            val key = rec.project?.trim().takeUnless { it.isNullOrEmpty() } ?: "未命名项目"
            groups.getOrPut(key) { ProjectGroup(key, mutableListOf()) }.sessions.add(rec)
        }
        val sorted = groups.values.sortedByDescending { g ->
            g.sessions.first().lastUsed ?: g.sessions.first().createdAt ?: 0L
        }
        for (g in sorted) {
            val node = DefaultMutableTreeNode(g)
            for (s in g.sessions) node.add(DefaultMutableTreeNode(s))
            root.add(node)
        }
        tree.model = DefaultTreeModel(root)
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }
        val hasSessions = records.isNotEmpty()
        setContent(if (hasSessions) scrollPane else emptyPanel)
    }

    // ==== 环境体检 + 一键配置视图 ====

    private var setupLogArea: JBTextArea? = null
    private var setupRunButton: JButton? = null

    private fun buildSetupPanel(report: DshEnvironmentCheck.Report): JPanel {
        setupLogArea = null
        setupRunButton = null
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10, 10, 10, 0)

        val header = JBLabel("环境未就绪").apply { foreground = com.intellij.ui.JBColor.RED }
        header.border = JBUI.Borders.emptyBottom(4)
        panel.add(header, BorderLayout.NORTH)

        val center = JPanel(BorderLayout())
        val problems = Box.createVerticalBox()
        problems.add(JBLabel("以下问题会影响 dsh-tui 启动："))
        for (p in report.problems) {
            problems.add(
                JBLabel(
                    "• ${p.title}${if (p.fixable) "" else "（需手动处理）"}",
                ),
            )
            problems.add(JBLabel("<html><body style='width:260px;padding-left:12px;color:#888888'>${escapeHtml(p.hint)}</body></html>"))
        }
        problems.add(Box.createVerticalStrut(8))
        val buttons = JPanel(BorderLayout())
        val run = JButton("一键配置环境", AllIcons.General.InlineRefresh)
        val recheck = JButton("重新检测")
        run.addActionListener { runSetupInteractive() }
        recheck.addActionListener { controller.refresh() }
        val row = JPanel()
        row.add(run)
        row.add(recheck)
        buttons.add(row, BorderLayout.WEST)
        problems.add(buttons)
        problems.add(Box.createVerticalStrut(8))
        center.add(problems, BorderLayout.NORTH)

        val logArea = JBTextArea(10, 40).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            emptyText.setText("配置日志将显示在这里")
        }
        setupLogArea = logArea
        setupRunButton = run
        center.add(JBScrollPane(logArea), BorderLayout.CENTER)
        panel.add(center, BorderLayout.CENTER)
        return panel
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 面板上触发的一键配置：日志写入面板日志区，结束后自动复检刷新。 */
    fun runSetupInteractive() {
        if (running) return
        running = true
        setupRunButton?.isEnabled = false
        val app = ApplicationManager.getApplication()
        appendLog("== 开始环境配置 ==")
        controller.runSetup(
            onLine = { line -> app.invokeLater { appendLog(line) } },
            onFinished = { ok -> app.invokeLater { appendLog(if (ok) "== 配置完成 ==" else "== 配置中断，请检查日志 =="); running = false; setupRunButton?.isEnabled = true } },
        )
    }

    private fun appendLog(line: String) {
        val area = setupLogArea ?: return
        area.append(line + "\n")
        area.caretPosition = area.document.length
    }

    private class SessionRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val obj = (value as? DefaultMutableTreeNode)?.userObject ?: return
            when (obj) {
                is ProjectGroup -> {
                    append(obj.name, REGULAR_BOLD_ATTRIBUTES)
                    append("（${obj.sessions.size}）", GRAYED_ATTRIBUTES)
                }
                is DshSessionRecord -> {
                    icon = AllIcons.Nodes.Console
                    append(DshSessionStore.sessionLabel(obj))
                    val whenMs = obj.lastUsed ?: obj.createdAt
                    if (whenMs != null) {
                        append("  ${AtMention.relativeTime(whenMs)}", GRAYED_ATTRIBUTES)
                    }
                    toolTipText = listOfNotNull(
                        obj.title?.trim(),
                        obj.cwd?.takeIf { it.isNotBlank() },
                        obj.id,
                    ).joinToString("\n")
                }
            }
        }
    }
}

/** 工具窗工厂：创建会话列表面板并启动监听。 */
class DshToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "DeepSeek"
        toolWindow.title = "DeepSeek"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controller = project.service<DshSessionsController>()
        val panel = DshSessionsPanel(project)
        controller.panel = panel
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer { if (controller.panel === panel) controller.panel = null }
        toolWindow.contentManager.addContent(content)
        controller.start()
        panel.reloadAsync()
    }
}
