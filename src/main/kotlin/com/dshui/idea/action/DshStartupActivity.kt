package com.dshui.idea.action

import com.dshui.idea.ui.DshSessionsController
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 项目打开后启动监听（会话目录 watcher + 选区自动引用），不依赖工具窗是否打开。 */
class DshStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(DshSessionsController::class.java).start()
    }
}
