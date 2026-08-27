package com.dshui.idea.session

import com.intellij.openapi.Disposable
import com.intellij.util.Alarm
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap

/**
 * 监听 dsh 会话目录（root + 每个分组目录，WatchService 不递归），变化后防抖
 * 回调刷新（对齐 VSCode 版的 fs.watch + 500ms 防抖 + 每轮补挂新分组目录）。
 * 额外监听 storages 目录与 ~/.dsh-tui（MRU/归档集变化即时反映）。
 */
class DshSessionWatcher(
    dshHomeProvider: () -> Path,
    parentDisposable: Disposable,
    private val onChange: () -> Unit,
) : Disposable {

    private val watchService = FileSystems.getDefault().newWatchService()
    private val watched = ConcurrentHashMap<Path, WatchKey>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    @Volatile private var stopped = false

    private val thread = Thread({
        while (!stopped) {
            val key = try {
                watchService.take()
            } catch (e: Exception) {
                return@Thread // closed / interrupted
            }
            var hasEvents = false
            for (event in key.pollEvents()) {
                if (event.kind().name() != "OVERFLOW") hasEvents = true
            }
            if (!key.reset()) {
                watched.entries.removeIf { it.value == key }
            }
            if (hasEvents) {
                syncWatchers(dshHomeProvider())
                scheduleRefresh()
            }
        }
    }, "dsh-tui-session-watcher").apply {
        isDaemon = true
    }

    fun start(dshHome: Path) {
        syncWatchers(dshHome)
        thread.start()
    }

    /** 幂等地监听 sessions root 与所有分组目录；每轮刷新后重挂新出现的分组。 */
    private fun syncWatchers(dshHome: Path) {
        val dirs = ArrayList<Path>()
        val sessionsRoot = dshHome.resolve("sessions")
        dirs.add(sessionsRoot)
        try {
            FilesList.list(sessionsRoot).filter { FilesList.isDirectory(it) }.forEach { dirs.add(it) }
        } catch (e: IOException) {
            // 目录不存在 —— 只挂 root 本身（挂不上就跳过）
        }
        dirs.add(dshHome.resolve("storages"))
        dirs.add(Path.of(System.getProperty("user.home"), ".dsh-tui"))
        for (dir in dirs) {
            if (watched.containsKey(dir)) continue
            try {
                val key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                watched[dir] = key
            } catch (e: IOException) {
                // 目录缺失/不可读 —— 忽略
            }
        }
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({ if (!stopped) onChange() }, 500)
    }

    override fun dispose() {
        stopped = true
        try {
            watchService.close()
        } catch (e: IOException) {
            // 已关闭
        }
        watched.clear()
    }

    private object FilesList {
        fun list(dir: Path): List<Path> =
            java.nio.file.Files.list(dir).use { it.toList() }

        fun isDirectory(p: Path): Boolean = java.nio.file.Files.isDirectory(p)
    }
}
