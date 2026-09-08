package com.dshui.idea.ui

import com.dshui.idea.session.DshSessionStore
import com.dshui.idea.session.DshSessionWatcher
import com.dshui.idea.settings.DshSettings
import com.dshui.idea.terminal.DshTerminalLauncher
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * 项目级控制器：串起启动器、会话列表面板与文件监听。面板由工具窗创建时注册。
 */
@Service(Service.Level.PROJECT)
class DshSessionsController(private val project: Project) : Disposable {

    val launcher = DshTerminalLauncher(project)

    @Volatile
    var panel: DshSessionsPanel? = null

    private var watcher: DshSessionWatcher? = null
    private var autoMention: com.dshui.idea.action.DshAutoMention? = null

    fun dshHome(): Path =
        DshSessionStore.resolveDshHome(DshSettings.getInstance().state.dshHome.ifBlank { null })

    fun start() {
        if (watcher == null) {
            val w = DshSessionWatcher({ dshHome() }, this) { refresh() }
            w.start(dshHome())
            watcher = w
        }
        if (autoMention == null) {
            autoMention = com.dshui.idea.action.DshAutoMention(project, this)
        }
    }

    fun refresh() {
        panel?.reloadAsync()
        launcher.onSessionsChanged()
    }

    /**
     * 一键配置环境（后台任务）：按体检报告依次执行 pnpm → dsh → profile →
     * 插入包补装。onLine/onFinished 可能在后台线程回调，UI 侧自行切 EDT。
     */
    fun runSetup(onLine: (String) -> Unit, onFinished: (Boolean) -> Unit) {
        val report = com.dshui.idea.setup.DshEnvironmentCheck.check()
        for (p in report.problems.filter { !it.fixable }) {
            onLine("【需手动处理】${p.title} —— ${p.hint}")
        }
        val steps = com.dshui.idea.setup.DshSetupRunner.buildSteps(report)
        if (steps.isEmpty()) {
            onLine("没有需要自动安装的项目。")
            onFinished(true)
            return
        }
        object : com.intellij.openapi.progress.Task.Backgroundable(project, "配置 dsh-tui 环境", true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                var ok = true
                for (step in steps) {
                    if (indicator.isCanceled) {
                        ok = false
                        onLine("已取消。")
                        break
                    }
                    indicator.text = step.title
                    onLine("\$ ${step.command.joinToString(" ")}")
                    if (!com.dshui.idea.setup.DshSetupRunner.runStep(step, onLine)) {
                        ok = false
                        onLine("步骤「${step.title}」失败，已停止后续步骤。")
                        break
                    }
                    onLine("✔ ${step.title} 完成")
                }
                onFinished(ok)
            }
        }.queue()
    }

    /** 工作区目录集合（对齐 VSCode 的 workspaceFolders：项目根 + 内容根）。 */
    fun workspaceDirs(): List<String> {
        val roots = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots
        val dirs = roots.map { it.presentableUrl ?: return@map it.path }
        if (dirs.isEmpty()) project.basePath?.let { return listOf(it) }
        return dirs
    }

    override fun dispose() {
        watcher?.dispose()
        watcher = null
    }
}
