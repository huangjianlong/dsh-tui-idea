package com.dshui.idea.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshSessionLogTest {

    private val home = Fixture.tempHome()

    @Test
    fun `parses header, title and prompt from a small log`() {
        val file = Fixture.writeSession(
            home, "--D-proj--", "session-1",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-1", """D:\proj""", 1000L)),
                Fixture.frame(Fixture.userMessage("帮我写个排序")),
                Fixture.frame(Fixture.titleEvent(1, "排序任务")),
                Fixture.frame(Fixture.titleEvent(2, "排序任务·改")),
            ),
        )
        val rec = DshSessionLog.readSessionSummary(file, "--D-proj--")!!
        assertEquals("session-1", rec.id)
        assertEquals("排序任务·改", rec.title) // 最后一个 session/title 生效
        assertEquals("""D:\proj""", rec.cwd)
        assertEquals("proj", rec.project)
        assertEquals(1000L, rec.createdAt)
        assertTrue(rec.hasPrompt)
    }

    @Test
    fun `falls back to first human prompt and truncates to 80 chars`() {
        val long = "很".repeat(120)
        val file = Fixture.writeSession(
            home, "--D-proj--", "session-2",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-2", "/home/u/proj", 2000L)),
                Fixture.frame(Fixture.userMessage(long)),
            ),
        )
        val rec = DshSessionLog.readSessionSummary(file, "--D-proj--")!!
        assertEquals(80, rec.title!!.length)
        assertNull(rec.eventTitle)
    }

    @Test
    fun `plugin-injected user messages are not human prompts`() {
        val file = Fixture.writeSession(
            home, "--D-proj--", "session-3",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-3", "/home/u/proj", 3000L)),
                Fixture.frame(Fixture.userMessage("插件注入", sourceKind = "plugin")),
            ),
        )
        val rec = DshSessionLog.readSessionSummary(file, "--D-proj--")!!
        assertFalse(rec.hasPrompt)
        assertNull(rec.title)
    }

    @Test
    fun `inbox splice counts as prompt when user message never lands`() {
        val file = Fixture.writeSession(
            home, "--D-proj--", "session-4",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-4", "/home/u/proj", 4000L)),
                Fixture.frame(Fixture.splicedPrompt("从 splice 来的提问")),
            ),
        )
        val rec = DshSessionLog.readSessionSummary(file, "--D-proj--")!!
        assertTrue(rec.hasPrompt)
        assertEquals("从 splice 来的提问", rec.title)
    }

    @Test
    fun `current title comes from the tail window when the log is large`() {
        val filler = "x".repeat(200 * 1024) // 超过 64KB 头部窗口
        val file = Fixture.writeSession(
            home, "--D-proj--", "session-5",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-5", "/home/u/proj", 5000L)),
                Fixture.frame(Fixture.userMessage("大日志里的提问"), filler),
                Fixture.frame(Fixture.titleEvent(9, "尾部新标题")),
            ),
        )
        val rec = DshSessionLog.readSessionSummary(file, "--D-proj--")!!
        assertEquals("尾部新标题", rec.title)
        assertEquals("尾部新标题", rec.eventTitle) // 尾部标题同时成为 eventTitle
        assertTrue(rec.hasPrompt)
    }

    @Test
    fun `decodes gzip legacy logs`() {
        val file = Fixture.writeGzipSession(
            home, "--D-proj--", "session-6",
            listOf(
                Fixture.sessionHeader("session-6", "/home/u/proj", 6000L),
                Fixture.userMessage("gzip 里的提问"),
            ),
        )
        val rec = DshSessionLog.readSessionRecord(file, "--D-proj--")!!
        assertEquals("session-6", rec.id)
        assertEquals("gzip 里的提问", rec.title)
        assertTrue(rec.hasPrompt)
    }

    @Test
    fun `subagent origin and parent are surfaced`() {
        val file = Fixture.writeSession(
            home, "--D-proj--", "session-7",
            Fixture.concat(
                Fixture.frame(Fixture.sessionHeader("session-7", "/home/u/proj", 7000L, origin = "subagent", parent = "session-1")),
            ),
        )
        val rec = DshSessionLog.readSessionSummary(file, "--D-proj--")!!
        assertEquals("subagent", rec.origin)
        assertEquals("session-1", rec.parent)
    }

    @Test
    fun `missing header still yields a listable record`() {
        val file = Fixture.writeSession(
            home, "--D-proj--", "session-8",
            Fixture.concat(Fixture.frame(Fixture.userMessage("没有头的会话"))),
        )
        val rec = DshSessionLog.readSessionSummary(file, "--D-proj--")!!
        assertEquals("session-8", rec.id) // 回退到目录名
        assertEquals("没有头的会话", rec.title)
        assertEquals("proj", rec.project) // 回退到分组目录解码
    }
}
