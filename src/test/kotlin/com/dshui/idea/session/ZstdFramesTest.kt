package com.dshui.idea.session

import com.github.luben.zstd.Zstd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZstdFramesTest {

    @Test
    fun `walks a chain of concatenated frames`() {
        val f1 = Zstd.compress("one".toByteArray(), 3)
        val f2 = Zstd.compress("two".toByteArray(), 3)
        val f3 = Zstd.compress("three".toByteArray(), 3)
        val buf = Fixture.concat(f1, f2, f3)

        val frames = ZstdFrames.zstdFrames(buf)
        assertEquals(3, frames.size)
        assertEquals(buf.size, frames.last().end)
        assertEquals("one", DshSessionLog.decompressFrame(f1)!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `resumes past a corrupt middle frame`() {
        val f1 = Zstd.compress("one".toByteArray(), 3)
        val f3 = Zstd.compress("three".toByteArray(), 3)
        val garbage = ByteArray(16) { it.toByte() }
        val buf = Fixture.concat(f1, garbage, f3)

        val frames = ZstdFrames.zstdFrames(buf)
        // f1 保留；坏帧被跳过；f3 通过尾部重同步找回
        assertEquals(2, frames.size)
        assertEquals("one", DshSessionLog.decompressFrame(buf.copyOfRange(frames[0].start, frames[0].end))!!.toString(Charsets.UTF_8))
        assertEquals("three", DshSessionLog.decompressFrame(buf.copyOfRange(frames[1].start, frames[1].end))!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `frameEnd rejects garbage`() {
        val garbage = ByteArray(64) { (it * 7).toByte() }
        assertEquals(-1, ZstdFrames.frameEnd(garbage, 0))
        assertTrue(ZstdFrames.walkFrames(garbage).isEmpty())
        val f = Zstd.compress("x".toByteArray(), 3)
        assertEquals(f.size, ZstdFrames.frameEnd(Fixture.concat(f, garbage), 0))
        // 紧跟其后的垃圾不是帧
        assertEquals(-1, ZstdFrames.frameEnd(Fixture.concat(f, garbage), f.size))
    }

    @Test
    fun `head window resuming keeps frames after a torn frame`() {
        val f1 = Zstd.compress("one".toByteArray(), 3)
        val f2 = Zstd.compress("two".toByteArray(), 3)
        // 模拟头部窗口：f1 完整 + f2 被截断（只留前半）
        val window = Fixture.concat(f1, f2.copyOfRange(0, f2.size / 2))
        val frames = ZstdFrames.walkFramesResuming(window)
        assertEquals(1, frames.size)
        assertEquals("one", DshSessionLog.decompressFrame(window.copyOfRange(frames[0].start, frames[0].end))!!.toString(Charsets.UTF_8))
    }
}
