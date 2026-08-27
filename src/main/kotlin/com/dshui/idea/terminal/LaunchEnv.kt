package com.dshui.idea.terminal

import java.io.File

/**
 * 启动环境的纯逻辑（对齐 dsh-tui-vscode 的 session.ts，便于脱离 IDE 单测）。
 */
object LaunchEnv {

    /** 在宿主（IDE 进程）的 PATH 里把裸命令名解析为绝对可执行路径。 */
    fun resolveLaunchCommand(command: String): String? {
        if (command.contains('/') || command.contains('\\')) return null // 已是路径形式，交给终端处理
        val pathEnv = System.getenv("PATH") ?: return null
        val isWindows = File.pathSeparatorChar == ';' &&
            System.getProperty("os.name", "").lowercase().contains("windows")
        for (dir in pathEnv.split(File.pathSeparatorChar).filter { it.isNotEmpty() }) {
            if (isWindows) {
                for (ext in listOf(".cmd", ".bat", ".exe")) {
                    val candidate = File(dir, command + ext)
                    if (candidate.isFile) return candidate.absolutePath
                }
            } else {
                val candidate = File(dir, command)
                if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            }
        }
        return null
    }

    /**
     * 组装 dsh-tui 进程的附加环境变量（对齐 VSCode 版 buildLaunchEnv）：
     * DSH_TUI_LANG（语言）、DSH_HOME（覆盖）、VISUAL（外置编辑器，仅当
     * VISUAL/EDITOR 均未设置时注入，让 TUI 的 Ctrl+X 外置编辑器拉起 IDE）。
     */
    fun buildLaunchEnv(
        lang: String,
        injectEditor: Boolean,
        editorCommand: String,
        dshHome: String,
    ): LinkedHashMap<String, String> {
        val env = LinkedHashMap<String, String>()
        val langTrim = lang.trim()
        if (langTrim.isNotEmpty()) env["DSH_TUI_LANG"] = langTrim
        val homeTrim = dshHome.trim()
        if (homeTrim.isNotEmpty()) env["DSH_HOME"] = homeTrim
        if (injectEditor && System.getenv("VISUAL").isNullOrEmpty() && System.getenv("EDITOR").isNullOrEmpty()) {
            env["VISUAL"] = editorCommand.trim().ifEmpty { "idea --wait" }
        }
        return env
    }

    /** 按空白拆分参数串，支持 "双引号" 与 '单引号'。 */
    fun splitArgs(raw: String): List<String> {
        val out = ArrayList<String>()
        var current = StringBuilder()
        var quote: Char? = null
        for (ch in raw) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current = StringBuilder()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }
}
