package com.dshui.idea.setup

import com.dshui.idea.session.DshSessionStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 一键配置执行器：按体检报告依次补齐 pnpm → dsh → dsh-tui profile →
 * 全局补丁插入包。命令行输出逐行回调（供 UI 展示日志）。
 * 自动处理 pnpm ≥11 的构建脚本审批墙（dsh plugin add 首次会在 profile 的
 * pnpm-workspace.yaml 里留下「set this to true or false」占位并失败 ——
 * 官方 README 的处理是把 @google/genai、protobufjs 填为 false 后重试）。
 */
object DshSetupRunner {

    class Step(val title: String, val command: List<String>)

    /** 根据报告生成待执行步骤（顺序即依赖顺序）。 */
    fun buildSteps(report: DshEnvironmentCheck.Report): List<Step> {
        val steps = ArrayList<Step>()
        if (report.pnpmPath == null) {
            steps += Step("安装 pnpm", listOf("npm", "install", "-g", "pnpm"))
        }
        if (report.dshPath == null) {
            steps += Step("安装 dsh CLI", listOf("npm", "install", "-g", "@deepseek-ai/dsh"))
        }
        if (report.dshPath != null || steps.any { it.title == "安装 dsh CLI" }) {
            if (!report.profileInstalled) {
                steps += Step(
                    "安装 dsh-tui profile",
                    listOf("dsh", "plugin", "--profile", "dsh-tui", "add", "@deepseek-harness-tui/dsh-tui"),
                )
            }
            for (pkg in report.missingInsertPackages) {
                steps += Step("补装插入包 $pkg", listOf("dsh", "plugin", "--profile", "dsh-tui", "add", pkg))
            }
        }
        return steps
    }

    private fun isWindows(): Boolean = File.pathSeparatorChar == ';'

    /** 把 pnpm-workspace.yaml 里 dsh 留下的 allowBuilds 占位按官方指引填为 false。 */
    fun sanitizeAllowBuildsPlaceholders(text: String): String =
        text.replace("set this to true or false", "false")

    /**
     * 执行一个步骤（全部为全局安装命令，不依赖工作目录），输出逐行回调（在调用线程）；
     * dsh plugin 步骤失败且 profile 的 pnpm-workspace.yaml 含占位时自动填补并重试一次。
     * @return 是否成功（退出码 0）。
     */
    fun runStep(step: Step, onLine: (String) -> Unit): Boolean {
        if (runOnce(step, onLine)) return true
        val isDshPluginStep = step.command.size >= 2 && step.command[0] == "dsh" && step.command[1] == "plugin"
        if (!isDshPluginStep) return false
        val yaml = dshProfileWorkspaceYaml() ?: return false
        val text = try {
            Files.readString(yaml)
        } catch (e: Exception) {
            return false
        }
        if (!text.contains("set this to true or false")) return false
        onLine("检测到 pnpm 构建脚本审批占位（pnpm ≥11），按官方指引填为 false 后重试…")
        return try {
            Files.writeString(yaml, sanitizeAllowBuildsPlaceholders(text))
            runOnce(step, onLine)
        } catch (e: Exception) {
            onLine("修复 pnpm-workspace.yaml 失败：${e.message}")
            false
        }
    }

    private fun dshProfileWorkspaceYaml(): Path? {
        val home = DshSessionStore.resolveDshHome(null)
        val yaml = Path.of(home.toString(), "profiles", "dsh-tui", "pnpm-workspace.yaml")
        return if (Files.isRegularFile(yaml)) yaml else null
    }

    private fun runOnce(step: Step, onLine: (String) -> Unit): Boolean {
        return try {
            val command = if (isWindows()) listOf("cmd.exe", "/c") + step.command else step.command
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) onLine(line)
            }
            val code = process.waitFor()
            code == 0
        } catch (e: Exception) {
            onLine("执行失败：${e.message}")
            false
        }
    }
}
