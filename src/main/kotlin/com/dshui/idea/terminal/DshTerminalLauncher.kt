package com.dshui.idea.terminal

import com.dshui.idea.session.DshSessionStore
import com.dshui.idea.settings.DshSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import org.jetbrains.plugins.terminal.startup.TerminalProcessType

/**
 * dsh-tui 终端启动器：在集成终端里以独立标签页运行 dsh-tui。
 * 启动命令解析链：设置命令（路径形式）→ PATH 上的 dsh-tui 启动器 →
 * dsh --profile dsh-tui（官方启动器的等价回退，免手工拷贝 dsh-tui.cmd）。
 */
class DshTerminalLauncher(private val project: Project) {

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

        TerminalToolWindowTabsManager.getInstance(project)
            .createTabBuilder()
            .tabName(TAB_NAME)
            .shellCommand(plan.shellCommand)
            .envVariables(plan.env)
            .workingDirectory(project.basePath ?: System.getProperty("user.home"))
            .processType(TerminalProcessType.NON_SHELL)
            .requestFocus(true)
            .createTab()
        activateTerminalToolWindow()
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dsh-tui")
            .createNotification(message, type)
            .notify(project)
    }

    private fun activateTerminalToolWindow() {
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.activate(null)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("windows")

    /** 最近创建的 DeepSeek 标签页（并发多会话时插入目标是最新那个）。 */
    fun findTab(): TerminalToolWindowTab? =
        TerminalToolWindowTabsManager.getInstance(project)
            .tabs.lastOrNull { it.content.tabName == TAB_NAME || it.content.displayName == TAB_NAME }

    /** 是否存在运行中的 DeepSeek 会话终端。 */
    fun hasTerminal(): Boolean = findTab() != null

    /**
     * 把文本键入运行中的会话输入框（不自动提交）。@引用插入用。
     * @return 是否有终端接收了输入。
     */
    fun sendInput(text: String): Boolean {
        val tab = findTab() ?: return false
        activateTerminalToolWindow()
        tab.view.sendText(text)
        return true
    }

    /** 聚焦现有会话；没有则启动新会话（对齐 VSCode 版 focus 命令）。 */
    fun focusOrStart() {
        val tab = findTab()
        if (tab != null) {
            activateTerminalToolWindow()
            ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                ?.contentManager?.setSelectedContent(tab.content)
        } else {
            startNewSession()
        }
    }

    companion object {
        const val TAB_NAME = "DeepSeek"
        const val RESUME_ENV = "DSH_TUI_RESUME_SESSION"
        const val RESUME_ENV_CC = "DSH_CC_RESUME_SESSION"
    }
}
