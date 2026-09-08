package com.dshui.idea.terminal

import com.dshui.idea.session.DshSessionLog
import com.dshui.idea.session.DshSessionRecord
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 「agent 跑完了」提醒：终端收在侧栏里，dsh-tui 跑完一轮等待输入时用户
 * 无感知。对已绑定的会话日志做增量尾部扫描——`turn/end` 事件且
 * `reason.kind == completed`（aborted/interrupted 是用户主动断的，不打扰）、
 * 且 `seq` 超过上次基线（日志只追加，旧事件仍在尾窗内，不按 seq 过滤会在
 * 之后任何一次文件变化时重复弹通知）。会话标签可见性判断与通知发送都在
 * EDT；mtime 未变不重扫；绑定即建基线（历史事件不回放）。
 *
 * 已知权衡：单轮追加超过 128KB 尾窗会把 turn/end 挤出窗口而漏通知（tail
 * 扫描的固有限制）；设置开关关闭期间基线照常推进，重新打开后不会补弹。
 */
class DshTurnNotifier(
    private val project: Project,
    private val focusSession: (String) -> Unit,
    private val isSessionVisible: (String) -> Boolean,
) {

    private class Watched(val sessionId: String, val file: Path) {
        var lastSeq: Long = 0
        var lastMtime: Long = 0
        var title: String? = null
    }

    private val watched = ConcurrentHashMap<String, Watched>()

    /**
     * 绑定（新建/恢复会话）：建立扫描基线，历史事件静默吸收。
     * 必须在后台线程调用（含文件 mtime + 尾窗 zstd 解压 I/O）。
     */
    fun bindPooled(rec: DshSessionRecord) {
        val w = Watched(rec.id, Path.of(rec.file))
        w.title = rec.title
        scanWatched(w, silent = true)
        watched[rec.id] = w
    }

    fun unbind(sessionId: String) {
        watched.remove(sessionId)
    }

    /** 会话目录有变化时调用（后台线程执行扫描）。 */
    fun scan() {
        if (watched.isEmpty() || project.isDisposed) return
        for (w in watched.values) scanWatched(w, silent = false)
    }

    private fun number(element: com.google.gson.JsonElement?): Long? =
        element?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun scanWatched(w: Watched, silent: Boolean) {
        val mtime = try {
            Files.getLastModifiedTime(w.file).toMillis()
        } catch (e: Exception) {
            return
        }
        if (!silent && mtime == w.lastMtime) return
        w.lastMtime = mtime
        val baseline = w.lastSeq
        var completed = false
        for (event in DshSessionLog.tailEvents(w.file)) {
            val seq = number(event.get("seq")) ?: continue
            if (seq > w.lastSeq) w.lastSeq = seq
            when (event.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
                "turn/end" -> {
                    val kind = event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("reason")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("kind")?.takeIf { it.isJsonPrimitive }?.asString
                    if (kind == "completed" && seq > baseline) completed = true
                }
                "session/title" -> {
                    event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("title")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() }?.let { w.title = it }
                }
            }
        }
        if (silent || !completed) return
        if (!com.dshui.idea.settings.DshSettings.getInstance().state.notifyOnTurnEnd) return
        notify(w)
    }

    private fun notify(w: Watched) {
        val title = w.title?.takeIf { it.isNotBlank() } ?: w.sessionId.take(8)
        val sessionId = w.sessionId
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            // 可见性判断访问 ToolWindow/ContentManager，必须在 EDT
            if (isSessionVisible(sessionId)) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup("dsh-tui")
                .createNotification("dsh 会话「$title」已跑完，等待你的输入", NotificationType.INFORMATION)
                .addAction(object : AnAction("查看") {
                    override fun actionPerformed(e: AnActionEvent) {
                        focusSession(sessionId)
                    }
                })
                .notify(project)
        }
    }
}
