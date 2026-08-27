package com.dshui.idea.action

import com.dshui.idea.ui.DshSessionsController
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager

/** 一键配置 dsh-tui 运行环境（打开工具窗并执行体检+自动安装）。 */
class DshSetupAction : AnAction("一键配置环境", "检测并自动安装 dsh/dsh-tui 运行所需环境", AllIcons.General.GearPlain) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("DeepSeek") ?: return
        toolWindow.show {
            val panel = project.service<DshSessionsController>().panel
            if (panel != null) {
                panel.runSetupInteractive()
            } else {
                // 面板尚未创建（极少见）：直接跑，结果进通知
                project.service<DshSessionsController>().runSetup(
                    onLine = {},
                    onFinished = { ok ->
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("dsh-tui")
                            .createNotification(
                                if (ok) "环境配置完成" else "环境配置未完成，请打开 DeepSeek 工具窗查看",
                                if (ok) com.intellij.notification.NotificationType.INFORMATION else com.intellij.notification.NotificationType.WARNING,
                            )
                            .notify(project)
                    },
                )
            }
        }
    }
}
