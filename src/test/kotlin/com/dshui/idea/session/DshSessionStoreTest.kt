package com.dshui.idea.session

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshSessionStoreTest {

    @Test
    fun `decodeGroupDir maps encoded names back to paths`() {
        val decoded = DshSessionStore.decodeGroupDir("--E-project--")!!
        assertEquals("project", DshSessionStore.pathBase(decoded))
        assertEquals("proj", DshSessionStore.pathBase("""D:\w\proj\""")) // 尾分隔符被裁掉
        assertEquals(null, DshSessionStore.pathBase("/"))
    }

    @Test
    fun `cwd matching is exact-or-descendant and case-insensitive on Windows`() {
        val ci = true
        assertTrue(DshSessionStore.sessionCwdMatches("""D:\Work\Proj""", """d:\work\proj""", ci))
        assertTrue(DshSessionStore.sessionCwdMatches("""D:\Work\Proj""", """D:\work\proj\sub""", ci)) // 子目录属于同一工作区
        assertFalse(DshSessionStore.sessionCwdMatches("""D:\Work\Proj""", """D:\Work""", ci)) // 父目录属于别的工作区
        val home = System.getProperty("user.home").replace('\\', '/')
        assertFalse(DshSessionStore.sessionCwdMatches("""C:\Users\x""", home, ci)) // 容器目录只允许精确相等
        assertTrue(DshSessionStore.sessionCwdMatches(home, home, ci))
    }

    @Test
    fun `listSessions filters and sorts by workspace, subagent, blank and archive`() {
        val home = Fixture.tempHome()
        Fixture.writeSession(
            home, "--D-w-proj--", "session-a",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-a", """D:\w\proj""", 1000L)),
                Fixture.frame(Fixture.userMessage("A 的提问")),
            ),
        )
        Fixture.writeSession(
            home, "--D-w-proj--", "session-b",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-b", """D:\w\proj\sub""", 2000L)),
                Fixture.frame(Fixture.userMessage("B 的提问")),
            ),
        )
        Fixture.writeSession(
            home, "--D-other--", "session-c",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-c", """D:\other""", 3000L)),
                Fixture.frame(Fixture.userMessage("C 的提问")),
            ),
        )
        Fixture.writeSession(
            home, "--D-w-proj--", "session-sub",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-sub", """D:\w\proj""", 4000L, origin = "subagent")),
                Fixture.frame(Fixture.userMessage("子代理的提问")),
            ),
        )
        Fixture.writeSession(
            home, "--D-w-proj--", "session-blank",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-blank", """D:\w\proj""", 5000L)),
            ),
        )
        Fixture.writeSession(
            home, "--D-w-proj--", "session-arch",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-arch", """D:\w\proj""", 6000L)),
                Fixture.frame(Fixture.userMessage("要被归档的提问")),
            ),
        )
        Fixture.writeStorages(
            home,
            """{"tables":{"sessions":{}}}""",
            """{"global":{"archivedSessionIds":["session-arch"]},"other":{"keep":1}}""",
        )

        val list = DshSessionStore.listSessions(
            home,
            DshSessionStore.ListOptions(
                workspaceDirs = listOf("""D:\w\proj"""),
                hideEmpty = true,
                hideSubagents = true,
                hideArchived = true,
            ),
        )
        val ids = list.map { it.id }
        // b 比 a 新（无 lastUsed 时按 createdAt 降序）；c 不在工作区、sub 被过滤、blank 被过滤、arch 被归档
        assertEquals(listOf("session-b", "session-a"), ids)
    }

    @Test
    fun `storage ledger title wins over first prompt but not over log title`() {
        val home = Fixture.tempHome()
        Fixture.writeSession(
            home, "--D-p--", "session-t1",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-t1", "/w/p", 100L)),
                Fixture.frame(Fixture.userMessage("原始提问")),
            ),
        )
        Fixture.writeSession(
            home, "--D-p--", "session-t2",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-t2", "/w/p", 200L)),
                Fixture.frame(Fixture.userMessage("原始提问")),
                Fixture.frame(Fixture.titleEvent(1, "日志标题")),
            ),
        )
        Fixture.writeStorages(
            home,
            """{"tables":{"sessions":{
                "session-t1":{"rows":{"title":{"val":"账本标题"}}},
                "session-t2":{"rows":{"title":{"val":"账本标题"}}}
            }}}""",
            """{"global":{"archivedSessionIds":[]}}""",
        )
        val list = DshSessionStore.listSessions(home, DshSessionStore.ListOptions(workspaceDirs = listOf("/w/p")))
        val byId = list.associateBy { it.id }
        assertEquals("账本标题", byId["session-t1"]!!.title) // 无日志标题 → 账本赢
        assertEquals("日志标题", byId["session-t2"]!!.title) // 日志标题最高优先
    }

    @Test
    fun `ledger blank flag hides a session the log alone would list`() {
        val home = Fixture.tempHome()
        Fixture.writeSession(
            home, "--D-p--", "session-blankish",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-blankish", "/w/p", 100L)),
                // 日志没读到提问，但整个日志未读完时 hasPrompt 默认 true；账本看过全程：blank
                Fixture.frame("x".repeat(200 * 1024)),
            ),
        )
        Fixture.writeStorages(
            home,
            """{"tables":{"sessions":{"session-blankish":{"rows":{"sessionListMetadata":{"val":{"blank":true}}},"title":{"val":"空白会话"}}}}}}""",
            """{"global":{"archivedSessionIds":[]}}""",
        )
        val visible = DshSessionStore.listSessions(
            home,
            DshSessionStore.ListOptions(workspaceDirs = listOf("/w/p"), hideEmpty = true),
        )
        assertTrue(visible.isEmpty()) // blank 标记把「未读完整」的会话判定为空并隐藏
    }

    @Test
    fun `archive toggles edit the workspace domain and preserve other fields`() {
        val home = Fixture.tempHome()
        val ws = home.resolve("storages").resolve("workspace.json")
        Files.createDirectories(ws.parent)
        Files.writeString(ws, """{"global":{"archivedSessionIds":["keep-me"]},"other":{"a":1}}""")

        assertTrue(DshSessionStore.setSessionArchived("s1", archived = true, dshHome = home))
        val after1 = JsonParser.parseString(Files.readString(ws)).asJsonObject
        val ids1 = after1.getAsJsonObject("global").getAsJsonArray("archivedSessionIds").map { it.asString }
        assertEquals(listOf("keep-me", "s1"), ids1)
        assertEquals(1, after1.getAsJsonObject("other").get("a").asInt)

        assertTrue(DshSessionStore.setSessionArchived("s1", archived = false, dshHome = home))
        val ids2 = JsonParser.parseString(Files.readString(ws)).asJsonObject
            .getAsJsonObject("global").getAsJsonArray("archivedSessionIds").map { it.asString }
        assertEquals(listOf("keep-me"), ids2)
    }

    @Test
    fun `rename appends a verified title frame with next seq`() {
        val home = Fixture.tempHome()
        val file = Fixture.writeSession(
            home, "--D-p--", "session-r",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-r", "/w/p", 100L), Fixture.userMessage("提问")),
                Fixture.frame(Fixture.titleEvent(4, "旧标题")),
            ),
        )
        assertTrue(DshSessionStore.appendSessionTitle(file, "新名字"))
        val text = DshSessionLog.decodeSessionLog(file)!!
        val lastLine = text.trimEnd().lines().last()
        val event = JsonParser.parseString(lastLine).asJsonObject
        assertEquals("session/title", event.get("type").asString)
        assertEquals(5L, event.get("seq").asLong) // maxSeq+1
        assertEquals("新名字", event.getAsJsonObject("data").get("title").asString)
        // 重读摘要：新标题生效
        assertEquals("新名字", DshSessionLog.readSessionSummary(file, "--D-p--")!!.title)
    }

    @Test
    fun `delete removes the session dir and refuses paths outside the sessions root`() {
        val home = Fixture.tempHome()
        val file = Fixture.writeSession(
            home, "--D-p--", "session-d",
            Fixture.concat(Fixture.frame(Fixture.sessionHeader("session-d", "/w/p", 100L))),
        )
        assertTrue(DshSessionStore.deleteSessionLog(file, home))
        assertFalse(Files.exists(file.parent))

        // 会话根之外的文件（storages 下的伪造日志）必须被拒绝
        Files.createDirectories(home.resolve("storages"))
        val outside = home.resolve("storages").resolve("fake.jsonl.zstd")
        Files.writeString(outside, "garbage")
        assertFalse(DshSessionStore.deleteSessionLog(outside, home))
        assertTrue(Files.exists(outside))
    }

    @Test
    fun `legacy plain jsonl logs are discovered and decoded`() {
        val home = Fixture.tempHome()
        val dir = home.resolve("sessions").resolve("--D-p--").resolve("session-plain")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("session.jsonl"),
            listOf(
                Fixture.sessionHeader("session-plain", "/w/p", 100L),
                Fixture.userMessage("纯文本日志的提问"),
            ).joinToString("") { it + "\n" },
        )
        val files = DshSessionStore.findSessionFiles(home)
        assertEquals(1, files.size)
        val rec = DshSessionLog.readSessionSummary(files[0].file, files[0].group)!!
        assertEquals("纯文本日志的提问", rec.title)
    }
}
