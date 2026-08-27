package com.dshui.idea.session

import java.nio.file.Path
import kotlin.test.Test

/** 对本机真实 ~/.dsh 数据的只读冒烟测试：验证 zstd 帧链解析在真实语料上工作。 */
class RealDataSmokeTest {

    @Test
    fun `parses real local sessions`() {
        val home = Path.of(System.getProperty("user.home"), ".dsh")
        if (!java.nio.file.Files.isDirectory(home.resolve("sessions"))) {
            println("本机无 ~/.dsh/sessions，跳过")
            return
        }
        val files = DshSessionStore.findSessionFiles(home)
        println("发现 ${files.size} 个会话文件")
        for (sf in files) {
            val rec = DshSessionLog.readSessionSummary(sf.file, sf.group)
            println(
                "  [${sf.group}] ${rec?.id} | title=${rec?.title} | cwd=${rec?.cwd} | hasPrompt=${rec?.hasPrompt} " +
                    "| createdAt=${rec?.createdAt} | project=${rec?.project}",
            )
        }
        val list = DshSessionStore.listSessions(home, DshSessionStore.ListOptions())
        println("listSessions（无过滤）共 ${list.size} 条：")
        for (rec in list) {
            println("  ${rec.id.take(20)}… | ${rec.title} | lastUsed=${rec.lastUsed}")
        }
    }
}
