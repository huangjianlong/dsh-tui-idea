package com.dshui.idea.setup

import com.dshui.idea.session.DshSessionStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 环境体检：检测 dsh-tui 运行的全部前提（可注入 PATH/home 以便单测）。
 * 覆盖换机失败的各类暗坑：node、pnpm、dsh CLI、dsh-tui profile、
 * 全局 cordis.patch.yml 插入包（皮肤类插件必须存在于每个 profile）、登录凭据。
 */
object DshEnvironmentCheck {

    class Problem(
        val id: String,
        val title: String,
        val hint: String,
        /** 一键配置能否自动修复（node/登录只能引导）。 */
        val fixable: Boolean,
    )

    class Report(
        val nodePath: String?,
        val dshPath: String?,
        val pnpmPath: String?,
        val tuiLauncherPath: String?,
        val profileInstalled: Boolean,
        val missingInsertPackages: List<String>,
        val hasCredentials: Boolean,
        val problems: List<Problem>,
    ) {
        /** 无可自动修复的问题即视为就绪（node/登录属引导项，不阻塞启动尝试）。 */
        val ready: Boolean get() = problems.none { it.fixable }
    }

    fun check(): Report = check(
        pathEnv = System.getenv("PATH"),
        dshHome = DshSessionStore.resolveDshHome(null),
        isWindows = File.pathSeparatorChar == ';',
    )

    fun check(pathEnv: String?, dshHome: Path, isWindows: Boolean): Report {
        val dirs = (pathEnv ?: "").split(File.pathSeparatorChar).filter { it.isNotBlank() }
        val nodePath = findExecutable(dirs, "node", isWindows)
        val dshPath = findExecutable(dirs, "dsh", isWindows)
        val pnpmPath = findExecutable(dirs, "pnpm", isWindows)
        val tuiLauncherPath = findExecutable(dirs, "dsh-tui", isWindows)

        val profilePackage = dshHome.resolve("profiles").resolve("dsh-tui")
            .resolve("node_modules").resolve("@deepseek-harness-tui").resolve("dsh-tui")
        val profileInstalled = Files.isDirectory(profilePackage)

        val insertNames = readInsertPackageNames(dshHome)
        val profileNodeModules = dshHome.resolve("profiles").resolve("dsh-tui").resolve("node_modules")
        val missingInserts = insertNames.filter { name ->
            val dir = profileNodeModules.resolve(stripVersionSpec(name))
            !Files.isDirectory(dir)
        }

        val hasCredentials = !System.getenv("DEEPSEEK_API_KEY").isNullOrBlank() ||
            Files.isRegularFile(dshHome.resolve(".credentials.yaml"))

        val problems = ArrayList<Problem>()
        if (nodePath == null) {
            problems += Problem(
                "node",
                "未检测到 Node.js",
                "dsh 全家桶依赖 Node.js，请先安装 LTS 版：https://nodejs.org/zh-cn （装完重开 IDE）",
                fixable = false,
            )
        }
        if (pnpmPath == null) {
            problems += Problem("pnpm", "未检测到 pnpm", "dsh 的插件安装走 pnpm，可自动安装（npm install -g pnpm）", fixable = true)
        }
        if (dshPath == null) {
            problems += Problem("dsh", "未检测到 dsh CLI", "可自动安装（npm install -g @deepseek-ai/dsh）", fixable = true)
        }
        if (dshPath != null && !profileInstalled) {
            problems += Problem(
                "profile",
                "未安装 dsh-tui profile",
                "可自动安装（dsh plugin --profile dsh-tui add @deepseek-harness-tui/dsh-tui）",
                fixable = true,
            )
        }
        if (missingInserts.isNotEmpty()) {
            problems += Problem(
                "inserts",
                "全局补丁插入的 ${missingInserts.size} 个包不在 profile 内（TUI 会启动即退）",
                "来自 ~/.dsh/cordis.patch.yml 的皮肤等插入包：${missingInserts.joinToString("、")}；可自动补装",
                fixable = true,
            )
        }
        if (!hasCredentials) {
            problems += Problem(
                "auth",
                "未检测到 DeepSeek 凭据",
                "请先在终端运行一次 dsh 完成登录，或设置 DEEPSEEK_API_KEY 环境变量",
                fixable = false,
            )
        }
        return Report(nodePath, dshPath, pnpmPath, tuiLauncherPath, profileInstalled, missingInserts, hasCredentials, problems)
    }

    /** PATH 目录里找可执行文件（Windows 试 name / .cmd / .bat / .exe）。 */
    private fun findExecutable(dirs: List<String>, name: String, isWindows: Boolean): String? {
        val candidates = if (isWindows) {
            listOf(name, "$name.cmd", "$name.bat", "$name.exe")
        } else {
            listOf(name)
        }
        for (dir in dirs) {
            for (candidate in candidates) {
                val f = File(dir, candidate)
                if (f.isFile) return f.absolutePath
            }
        }
        return null
    }

    /**
     * 解析 ~/.dsh/cordis.patch.yml 里 insert 条目的包名（name: 'pkg' 形式；
     * dsh-skin 管理块生成的皮肤插入即此形态）。宽容解析，失败返回空。
     */
    fun readInsertPackageNames(dshHome: Path): List<String> {
        val file = dshHome.resolve("cordis.patch.yml")
        val text = try {
            Files.readString(file)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = LinkedHashSet<String>()
        for (line in text.lines()) {
            val m = Regex("""^\s*-?\s*name:\s*['"]?([^'"\s]+)['"]?\s*$""").find(line) ?: continue
            out += m.groupValues[1]
        }
        return out.toList()
    }

    /** 去掉包名尾部的版本说明（@scope/name@1.2 与 pkg@1.2 → 目录名）。 */
    fun stripVersionSpec(pkg: String): String =
        if (pkg.startsWith("@")) {
            val second = pkg.indexOf('@', 1)
            if (second > 0) pkg.substring(0, second) else pkg
        } else {
            val at = pkg.indexOf('@')
            if (at > 0) pkg.substring(0, at) else pkg
        }
}
