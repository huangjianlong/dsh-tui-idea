package com.dshui.idea.session

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 会话改动清单提取（tool/call → 文件）的单元测试。 */
class DshSessionChangesTest {

    private fun toolCall(name: String, argsJson: String, time: Long = 1000): String =
        Fixture.line(
            "type" to "tool/call",
            "seq" to 1,
            "time" to time,
            "data" to JsonObject().also {
                it.addProperty("name", name)
                it.addProperty("arguments", argsJson)
            },
        )

    @Test
    fun `extracts edit write and str_replace_editor changes`() {
        val file = Fixture.writeSession(
            Fixture.tempHome(), "g", "s1",
            Fixture.concat(
                Fixture.frame(
                    Fixture.sessionHeader("s1", "E:/proj", 1),
                    toolCall("edit", """{"file_path":"E:\\proj\\A.java","old_string":"x","new_string":"y"}""", time = 2000),
                    toolCall("write", """{"file_path":"E:\\proj\\B.md","content":"hello"}""", time = 3000),
                    toolCall("str_replace_editor", """{"command":"create","path":"E:/proj/C.txt"}""", time = 4000),
                    toolCall("str_replace_editor", """{"command":"view","path":"E:/proj/D.txt"}""", time = 5000),
                    toolCall("read", """{"file_path":"E:\\proj\\D.txt"}""", time = 6000),
                    toolCall("bash", """{"command":"ls"}""", time = 7000),
                ),
            ),
        )
        val changes = assertNotNull(DshSessionChanges.extract(file))
        // read / view / bash 不计入
        assertEquals(3, changes.size)
        assertEquals(listOf("E:/proj/C.txt", "E:\\proj\\B.md", "E:\\proj\\A.java"), changes.map { it.path })
        val a = changes.first { it.path.endsWith("A.java") }
        assertEquals(1, a.edits)
        assertEquals(0, a.creates)
        val c = changes.first { it.path.endsWith("C.txt") }
        assertEquals(0, c.edits)
        assertEquals(1, c.creates)
    }

    @Test
    fun `empty and null cases`() {
        val noChanges = Fixture.writeSession(
            Fixture.tempHome(), "g", "s2",
            Fixture.frame(Fixture.sessionHeader("s2", "E:/p", 1), Fixture.userMessage("hi")),
        )
        assertTrue(DshSessionChanges.extract(noChanges)!!.isEmpty())

        val missing = Fixture.tempHome().resolve("none.jsonl.zstd")
        assertNull(DshSessionChanges.extract(missing))
    }
}
