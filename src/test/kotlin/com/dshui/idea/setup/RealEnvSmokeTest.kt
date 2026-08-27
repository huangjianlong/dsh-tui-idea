package com.dshui.idea.setup

import kotlin.test.Test

/** 对本机真实环境的只读体检冒烟：验证检测项与真实状态一致。 */
class RealEnvSmokeTest {

    @Test
    fun `real machine environment report`() {
        val report = DshEnvironmentCheck.check()
        println("node=${report.nodePath}")
        println("dsh=${report.dshPath}")
        println("pnpm=${report.pnpmPath}")
        println("dsh-tui 启动器=${report.tuiLauncherPath}")
        println("profile 已装=${report.profileInstalled}")
        println("缺失插入包=${report.missingInsertPackages}")
        println("凭据=${report.hasCredentials}")
        println("problems=${report.problems.map { it.id }}")
        println("ready=${report.ready}")
    }
}
