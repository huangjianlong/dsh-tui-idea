package com.dshui.idea.terminal

import com.dshui.idea.session.DshSessionLog
import com.dshui.idea.session.DshSessionRecord
import com.dshui.idea.session.DshSessionStore
import com.dshui.idea.settings.DshSettings
import com.dshui.idea.ui.DshSessionsController
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.ui.content.Content
import org.jetbrains.plugins.terminal.startup.TerminalProcessType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh-tui 终端启动器：在右侧 DeepSeek 工具窗内以可关闭标签页运行 dsh-tui，
 * 不占用底部集成终端。终端标签经 2026.1 Reworked Terminal API 创建后放进
 * DeepSeek 工具窗自己的 ContentManager（必须 requestFocus(false)——
 * TabsManager 在 requestFocus 时固定激活 Terminal 工具窗，与标签宿主无关）。
 * 启动命令解析链：设置命令（路径形式）→ PATH 上的 dsh-tui 启动器 →
 * dsh --profile dsh-tui（官方启动器的等价回退，免手工拷贝 dsh-tui.cmd）。
 *
 * 标签与会话的绑定：恢复会话 id 已知直接绑；新会话由后台轮询（单飞循环）
 * 把启动后新建、cwd 落在本工作区、创建时间不早于该标签启动时刻的会话按
 * 时间对号；`--resume` 参数路径（dsh 自行恢复上次会话）无法预知 id，不绑
 * 定。绑定后标签名同步为会话标题，日志交给 [DshTurnNotifier] 做跑完提醒。
 * 所有文件 I/O（查记录、建基线）在后台线程；EDT 只做映射与改名。
 */
class DshTerminalLauncher(private val project: Project) {

    /** 会话 id → 运行中的终端标签（同一会话多标签时以最近绑定为准）。 */
    private val sessionTabs = ConcurrentHashMap<String, TerminalToolWindowTab>()

    /** 新会话待匹配：tab → 启动时刻（epoch ms）。 */
    private val pendingTabs = ConcurrentHashMap<TerminalToolWindowTab, Long>()

    /** 匹配轮询单飞标志：同一时刻至多一个循环在跑。 */
    private val matchLoopRunning = AtomicBoolean(false)

    private val turnNotifier = DshTurnNotifier(
        project,
        focusSession = { sessionId -> focusSession(sessionId) },
        isSessionVisible = { sessionId -> isSessionVisible(sessionId) },
    )

    private val app get() = ApplicationManager.getApplication()

    private val controller: DshSessionsController
        get() = project.getService(DshSessionsController::class.java)

    fun startNewSession() = launch(resumeSessionId = null, resumeLast = false)

    fun resumeLastSession() = launch(resumeSessionId = null, resumeLast = true)

    /** 恢复指定会话：通过 DSH_TUI_RESUME_SESSION/DSH_CC_RESUME_SESSION 注入（不加 --resume）。 */
    fun resumeSession(sessionId: String) = launch(resumeSessionId = sessionId, resumeLast = false)

    private fun launch(resumeSessionId: String?, resumeLast: Boolean) {
        val settings = DshSettings.getInstance()
        val lastUsed = DshSessionStore.readLastUsed().maxByOrNull { it.value }?.key
        val plan = DshLaunchPlanner.plan(
            commandSetting = settings.state.command,
            extraArgs = settings.parsedExtraArgs(),
            lang = settings.state.lang,
            injectEditor = settings.state.injectEditor,
            editorCommand = settings.state.editorCommand,
            dshHome = settings.state.dshHome,
            resumeSessionId = resumeSessionId,
            resumeLast = resumeLast,
            resolvedTui = LaunchEnv.resolveLaunchCommand("dsh-tui"),
            resolvedDsh = LaunchEnv.resolveLaunchCommand("dsh"),
            lastUsedSessionId = lastUsed,
            isWindows = isWindows(),
        )
        when (plan.mode) {
            DshLaunchPlanner.LaunchPlan.Mode.BARE_COMMAND ->
                notify("未找到 dsh / dsh-tui 命令，将按原样启动；如失败请在 DeepSeek 工具窗执行「一键配置环境」。", NotificationType.WARNING)
            DshLaunchPlanner.LaunchPlan.Mode.DSH_PROFILE ->
                notify("PATH 中未找到 dsh-tui 启动器，已回退为 dsh --profile dsh-tui（等价形态）。", NotificationType.INFORMATION)
            else -> if (plan.notes.isNotEmpty()) {
                notify(plan.notes.joinToString("\n"), NotificationType.INFORMATION)
            }
        }

        val toolWindow = deepSeekToolWindow()
        if (toolWindow == null) {
            notify("DeepSeek 工具窗不可用，无法启动会话。", NotificationType.ERROR)
            return
        }
        val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
        // launching 标记：createTab 会同步触发 tabAdded，守卫需借此放行自己的标签
        DshTerminalTabs.launching = true
        val tab = try {
            tabsManager.createTabBuilder()
                .tabName(TAB_NAME)
                .shellCommand(plan.shellCommand)
                .envVariables(plan.env)
                .workingDirectory(project.basePath ?: System.getProperty("user.home"))
                .processType(TerminalProcessType.NON_SHELL)
                .contentManager(toolWindow.contentManager)
                .requestFocus(false)
                .createTab()
        } finally {
            DshTerminalTabs.launching = false
        }
        DshTerminalTabs.register(tab.content)
        tab.content.isCloseable = true
        // dsh-tui 首帧前盖鲸鱼喷水过场，遮住空终端的闪烁光标
        DshWhaleLoadingPanel.attach(tab)
        bindLaunchedTab(tab, plan, resumeSessionId, resumeLast, lastUsed)
        Disposer.register(tab.content as Disposable) {
            pendingTabs.remove(tab)
            val entry = sessionTabs.entries.firstOrNull { it.value === tab } ?: return@register
            sessionTabs.remove(entry.key)
            // 同一会话的其他标签仍存活时不撤销通知监听
            if (!sessionTabs.containsKey(entry.key)) turnNotifier.unbind(entry.key)
        }
        activateDeepSeekToolWindow(tab.content)
    }

    /** 启动后的绑定路由：显式/last-used 恢复按 id 绑；--resume 参数路径不绑；新会话走轮询。 */
    private fun bindLaunchedTab(
        tab: TerminalToolWindowTab,
        plan: DshLaunchPlanner.LaunchPlan,
        resumeSessionId: String?,
        resumeLast: Boolean,
        lastUsed: String?,
    ) {
        val resumeId = planResumeId(plan, resumeSessionId, resumeLast, lastUsed)
        when {
            resumeId != null -> bindInBackground(tab, resumeId)
            // --resume 参数路径（resumeLast 且无 last-used）：dsh 自行恢复上次会话，
            // 插件无法预知会话 id，不做绑定（标签名保持 DeepSeek）
            resumeLast && lastUsed == null && plan.shellCommand.contains("--resume") -> Unit
            else -> {
                pendingTabs[tab] = System.currentTimeMillis()
                schedulePendingMatch()
            }
        }
    }

    /** plan 里实际用于恢复的会话 id（显式指定 > last-used）。 */
    private fun planResumeId(
        plan: DshLaunchPlanner.LaunchPlan,
        resumeSessionId: String?,
        resumeLast: Boolean,
        lastUsed: String?,
    ): String? {
        if (resumeSessionId != null) return resumeSessionId
        if (!resumeLast) return null
        return lastUsed?.takeIf { plan.env.containsKey(RESUME_ENV) }
    }

    // ==== 会话绑定与标题同步 ====

    /** 后台：查会话记录、建通知基线（全部 I/O 离开 EDT），EDT 只做映射与改名。 */
    private fun bindInBackground(tab: TerminalToolWindowTab, sessionId: String) {
        app.executeOnPooledThread {
            val rec = findRecord(sessionId)
            app.invokeLater {
                if (project.isDisposed || Disposer.isDisposed(tab.content)) return@invokeLater
                if (rec != null) {
                    bindTab(tab, rec)
                } else {
                    // 记录暂不可读（如日志尚未落盘）：降级为轮询匹配
                    pendingTabs[tab] = System.currentTimeMillis()
                    schedulePendingMatch()
                }
            }
            if (rec != null) turnNotifier.bindPooled(rec)
        }
    }

    /** EDT：绑定 tab ↔ 会话并改标签名（纯 UI 操作）。 */
    private fun bindTab(tab: TerminalToolWindowTab, rec: DshSessionRecord) {
        sessionTabs[rec.id] = tab
        pendingTabs.remove(tab)
        val label = DshSessionStore.sessionLabel(rec)
        if (label.isNotBlank()) tab.content.displayName = label
    }

    /** 按 id 找会话记录（后台线程；扫全部会话文件做头尾有界读，量级 ~几十个）。 */
    private fun findRecord(sessionId: String): DshSessionRecord? {
        if (project.isDisposed) return null
        val home = runCatching { controller.dshHome() }.getOrNull() ?: return null
        for (sf in DshSessionStore.findSessionFiles(home)) {
            if (sf.id != sessionId) continue
            return DshSessionLog.readSessionSummary(sf.file, sf.group)
        }
        // 头部 id 与目录名不一致的兜底：全量摘要匹配
        for (sf in DshSessionStore.findSessionFiles(home)) {
            val rec = DshSessionLog.readSessionSummary(sf.file, sf.group) ?: continue
            if (rec.id == sessionId) return rec
        }
        return null
    }

    /**
     * 新会话轮询匹配（单飞循环，后台线程，2s 一轮，90s 放弃）：候选为工作区
     * 内、创建时间不早于「对应标签启动时刻 - 3s」的新会话，按时间与待定
     * 标签顺序对号；逐标签下界约束避免把更早的会话（外部进程开的）绑上来。
     */
    private fun schedulePendingMatch() {
        if (!matchLoopRunning.compareAndSet(false, true)) return
        app.executeOnPooledThread {
            try {
                while (!project.isDisposed && pendingTabs.isNotEmpty()) {
                    val earliestLaunch = pendingTabs.values.minOrNull() ?: return@executeOnPooledThread
                    if (System.currentTimeMillis() > earliestLaunch + 90_000) return@executeOnPooledThread
                    matchOnce()
                    if (pendingTabs.isNotEmpty()) Thread.sleep(2_000)
                }
            } finally {
                matchLoopRunning.set(false)
                // 循环退出后又有新标签入队：补一轮
                if (!project.isDisposed && pendingTabs.isNotEmpty()) schedulePendingMatch()
            }
        }
    }

    private fun matchOnce() {
        val recs = try {
            DshSessionStore.listSessions(
                controller.dshHome(),
                DshSessionStore.ListOptions(
                    workspaceDirs = controller.workspaceDirs(),
                    hideEmpty = true,
                    hideSubagents = true,
                    hideArchived = true,
                ),
            )
        } catch (e: Exception) {
            return // 本轮扫描失败（如并发读目录）：下轮重试
        }
        val bound = sessionTabs.keys.toHashSet()
        val used = HashSet<String>()
        val pairs = ArrayList<Pair<TerminalToolWindowTab, DshSessionRecord>>()
        for ((tab, launchedAt) in pendingTabs.entries.sortedBy { it.value }) {
            val rec = recs
                .filter { it.id !in bound && it.id !in used && (it.createdAt ?: 0L) >= launchedAt - 3_000 }
                .minByOrNull { it.createdAt ?: Long.MAX_VALUE }
                ?: continue
            used += rec.id
            pairs += tab to rec
        }
        if (pairs.isEmpty()) return
        // 基线（I/O）在后台建好后再进 EDT 改名
        for ((_, rec) in pairs) turnNotifier.bindPooled(rec)
        app.invokeLater {
            if (project.isDisposed) return@invokeLater
            for ((tab, rec) in pairs) {
                if (!Disposer.isDisposed(tab.content)) bindTab(tab, rec)
            }
        }
    }

    /** 会话列表刷新后同步标签名（重命名/自动起名生效）。EDT 调用。 */
    fun syncTitles(records: List<DshSessionRecord>) {
        if (sessionTabs.isEmpty()) return
        for ((sessionId, tab) in sessionTabs) {
            if (Disposer.isDisposed(tab.content)) continue
            val rec = records.firstOrNull { it.id == sessionId } ?: continue
            val label = DshSessionStore.sessionLabel(rec)
            if (label.isNotBlank() && tab.content.displayName != label) {
                tab.content.displayName = label
            }
        }
    }

    /** 会话目录有变化（watcher 触发）：后台扫描 turn/end。 */
    fun onSessionsChanged() {
        app.executeOnPooledThread {
            if (!project.isDisposed) turnNotifier.scan()
        }
    }

    private fun isSessionVisible(sessionId: String): Boolean {
        val tab = sessionTabs[sessionId] ?: return false
        val toolWindow = deepSeekToolWindow() ?: return false
        if (!toolWindow.isActive) return false
        return toolWindow.contentManager.selectedContent === tab.content
    }

    // ==== 终端宿主与输入 ====

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dsh-tui")
            .createNotification(message, type)
            .notify(project)
    }

    private fun deepSeekToolWindow(): ToolWindow? =
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    /** 激活 DeepSeek 工具窗并选中指定标签（终端会话标签聚焦入口）。 */
    private fun activateDeepSeekToolWindow(selected: Content? = null) {
        val toolWindow = deepSeekToolWindow() ?: return
        toolWindow.activate(null)
        selected?.let { toolWindow.contentManager.setSelectedContent(it) }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("windows")

    /** 最近创建的 DeepSeek 标签页（本插件登记的；并发多会话时插入目标是最新那个）。 */
    fun findTab(): TerminalToolWindowTab? =
        TerminalToolWindowTabsManager.getInstance(project)
            .tabs.lastOrNull { DshTerminalTabs.isOwned(it.content) }

    /** 是否存在运行中的 DeepSeek 会话终端。 */
    fun hasTerminal(): Boolean = findTab() != null

    /**
     * 把文本键入运行中的会话输入框（不自动提交）。@引用插入用。
     * @return 是否有终端接收了输入。
     */
    fun sendInput(text: String): Boolean {
        val tab = findTab() ?: return false
        activateDeepSeekToolWindow(tab.content)
        tab.view.sendText(text)
        return true
    }

    /** 聚焦现有会话；没有则启动新会话（对齐 VSCode 版 focus 命令）。 */
    fun focusOrStart() {
        val tab = findTab()
        if (tab != null) {
            activateDeepSeekToolWindow(tab.content)
        } else {
            startNewSession()
        }
    }

    /** 聚焦绑定到某会话的标签（通知「查看」入口）。 */
    fun focusSession(sessionId: String) {
        val tab = sessionTabs[sessionId]
        if (tab != null && !Disposer.isDisposed(tab.content)) {
            activateDeepSeekToolWindow(tab.content)
        } else {
            activateDeepSeekToolWindow()
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "DeepSeek"
        const val TAB_NAME = "DeepSeek"
        const val RESUME_ENV = "DSH_TUI_RESUME_SESSION"
        const val RESUME_ENV_CC = "DSH_CC_RESUME_SESSION"
    }
}
