package com.dshui.idea.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AtMentionTest {

    @Test
    fun `whole file reference when nothing is selected`() {
        assertEquals("@D:/repo/src/a.ts", AtMention.buildAtMention("D:/repo/src/a.ts", isEmpty = true, startLine = 0, endLine = 0))
    }

    @Test
    fun `single-line and multi-line ranges are 1-based`() {
        assertEquals("@D:/repo/a.ts L12", AtMention.buildAtMention("D:/repo/a.ts", false, 11, 11))
        assertEquals("@D:/repo/a.ts L12-14", AtMention.buildAtMention("D:/repo/a.ts", false, 11, 13))
    }

    @Test
    fun `paths with whitespace use the quoted form`() {
        assertEquals("""@"D:/my repo/a.ts"""", AtMention.buildAtMention("D:/my repo/a.ts", true, 0, 0))
    }

    @Test
    fun `windows backslash paths normalize to forward slashes`() {
        assertEquals("D:/repo/a.ts", AtMention.normalizeMentionPath("""D:\repo\a.ts"""))
    }
}
