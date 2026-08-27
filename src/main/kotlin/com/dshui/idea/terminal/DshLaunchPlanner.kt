package com.dshui.idea.terminal

/**
 * 启动计划的纯逻辑（可单测）：根据设置、PATH 解析结果与 resume 文件内容，
 * 产出终端进程命令与环境变量。去掉了对「手工拷贝的 dsh-tui.cmd」的依赖：
 * 解析不到 dsh-tui 启动器时回退为 `dsh --profile dsh-tui`（官方启动器的等价
 * 形态），恢复语义改由插件自己注入 DSH_TUI_RESUME_SESSION 实现。
 */
object DshLaunchPlanner {

    data class LaunchPlan(
        /** 终端进程命令（argv 形式，无 shell 拼接）。 */
        val shellCommand: List<String>,
        /** 附加环境变量。 */
        val env: Map<String, String>,
        /** 给用户的提示（非致命）。 */
        val notes: List<String>,
        /** 使用的启动模式。 */
        val mode: Mode,
    ) {
        enum class Mode { TUI_LAUNCHER, DSH_PROFILE, EXPLICIT_COMMAND, BARE_COMMAND }
    }

    /**
     * @param commandSetting 设置页的启动命令（默认 dsh-tui）
     * @param resolvedTui PATH 上解析到的 dsh-tui 可执行文件（无则 null）
     * @param resolvedDsh PATH 上解析到的 dsh CLI 可执行文件（无则 null）
     * @param lastUsedSessionId ~/.dsh-tui/last-used.json 里最近使用的会话 id（恢复上次用；无记录时 null）
     */
    fun plan(
        commandSetting: String,
        extraArgs: List<String>,
        lang: String,
        injectEditor: Boolean,
        editorCommand: String,
        dshHome: String,
        resumeSessionId: String?,
        resumeLast: Boolean,
        resolvedTui: String?,
        resolvedDsh: String?,
        lastUsedSessionId: String?,
        isWindows: Boolean,
    ): LaunchPlan {
        val command = commandSetting.trim().ifEmpty { "dsh-tui" }
        val env = LaunchEnv.buildLaunchEnv(lang, injectEditor, editorCommand, dshHome)
        // 与官方 dsh-tui.cmd 一致：TUI 的 React 渲染层在生产模式下才不会长会话 OOM
        if (System.getenv("NODE_ENV").isNullOrEmpty()) env["NODE_ENV"] = "production"

        val notes = ArrayList<String>()
        var args = extraArgs.toMutableList()
        val isPathLike = command.contains('/') || command.contains('\\')

        // 恢复语义：指定会话走 env；恢复上次优先用 last-used.json 里最近的会话（env 对两种启动模式都成立）
        var resumeViaEnv: String? = resumeSessionId
        if (resumeSessionId == null && resumeLast) {
            if (lastUsedSessionId != null) {
                resumeViaEnv = lastUsedSessionId
            } else if (isPathLike || resolvedTui != null) {
                args.add("--resume") // 真正的 dsh-tui 启动器自己解析 --resume（读 resume.txt）
            } else {
                notes.add("没有最近使用记录，将启动新会话")
            }
        }
        if (resumeViaEnv != null) {
            env["DSH_TUI_RESUME_SESSION"] = resumeViaEnv
            env["DSH_CC_RESUME_SESSION"] = resumeViaEnv
        }

        fun wrapBatch(cmdline: List<String>): List<String> =
            if (isWindows && cmdline.first().let { it.endsWith(".cmd", true) || it.endsWith(".bat", true) }) {
                listOf("cmd.exe", "/c") + cmdline
            } else {
                cmdline
            }

        return when {
            isPathLike -> LaunchPlan(wrapBatch(listOf(command) + args), env, notes, LaunchPlan.Mode.EXPLICIT_COMMAND)
            resolvedTui != null -> LaunchPlan(wrapBatch(listOf(resolvedTui) + args), env, notes, LaunchPlan.Mode.TUI_LAUNCHER)
            resolvedDsh != null -> {
                notes.add("PATH 中未找到 dsh-tui 启动器，已回退为 dsh --profile dsh-tui（等价形态）")
                LaunchPlan(wrapBatch(listOf(resolvedDsh, "--profile", "dsh-tui") + args), env, notes, LaunchPlan.Mode.DSH_PROFILE)
            }
            else -> {
                notes.add("未找到 dsh / dsh-tui 命令，将按原样启动；可在 DeepSeek 工具窗一键配置环境")
                LaunchPlan(wrapBatch(listOf(command) + args), env, notes, LaunchPlan.Mode.BARE_COMMAND)
            }
        }
    }
}
