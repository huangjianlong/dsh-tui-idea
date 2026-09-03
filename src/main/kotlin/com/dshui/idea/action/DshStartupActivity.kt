package com.dshui.idea.action

import com.dshui.idea.terminal.DshTerminalTabGuard
import com.dshui.idea.ui.DshSessionsController
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 项目打开后启动监听（会话目录 watcher + 选区自动引用），不依赖工具窗是否打开；
 * 同时安装终端恢复守卫，保证 IDEA 启动时不会自动弹出/恢复任何 dsh 终端标签。
 */
class DshStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(DshSessionsController::class.java).start()
        withContext(Dispatchers.EDT) {
            DshTerminalTabGuard.install(project)
        }
    }
}
