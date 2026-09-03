package com.dshui.idea.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.ui.content.Content
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 本插件亲手创建的终端标签登记处。用于把「我们自己开的会话标签」与
 * 终端排列持久化恢复出的同名标签区分开（恢复出的标签会自动拉起
 * dsh-tui 进程，违反点开插件才加载的约定，出现即应关闭）。
 */
object DshTerminalTabs {

    /** launch() 进行中标记：createTab 同步触发 tabAdded，此时不可按恢复标签处理。 */
    @Volatile
    var launching: Boolean = false

    private val owned: MutableSet<Content> =
        Collections.newSetFromMap(ConcurrentHashMap<Content, Boolean>())

    fun register(content: Content) {
        owned.add(content)
        Disposer.register(content, Disposable { owned.remove(content) })
    }

    fun isOwned(content: Content): Boolean = content in owned
}

/**
 * 终端恢复守卫：v0.2.x 把会话标签开在 Terminal 工具窗，重启后会被终端
 * 排列持久化恢复并自动启动 dsh-tui、弹开底部终端；0.3.0 起标签只应存在于
 * DeepSeek 工具窗。凡不是本插件登记过的 "DeepSeek" 标签（含 Terminal 窗里
 * 懒恢复的旧标签）出现即关闭，保证 IDEA 启动时不加载任何会话。
 */
object DshTerminalTabGuard {

    fun install(project: Project) {
        val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
        closeForeignTabs(tabsManager, tabsManager.tabs.toList())
        project.messageBus.connect(project).subscribe(
            TerminalTabsManagerListener.TOPIC,
            object : TerminalTabsManagerListener {
                override fun tabAdded(tab: TerminalToolWindowTab) {
                    closeForeignTabs(tabsManager, listOf(tab))
                }
            },
        )
    }

    private fun closeForeignTabs(
        tabsManager: TerminalToolWindowTabsManager,
        tabs: List<TerminalToolWindowTab>,
    ) {
        if (DshTerminalTabs.launching) return
        for (tab in tabs) {
            if (DshTerminalTabs.isOwned(tab.content)) continue
            if (tab.content.tabName != DshTerminalLauncher.TAB_NAME &&
                tab.content.displayName != DshTerminalLauncher.TAB_NAME
            ) continue
            tabsManager.closeTab(tab)
        }
    }
}
