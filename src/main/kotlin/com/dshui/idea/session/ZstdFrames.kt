package com.dshui.idea.session

/**
 * zstd 帧链的结构化遍历（RFC 8878 §3.1）。
 *
 * dsh 的会话日志是「每次落盘一批、追加一帧」的 zstd 帧链；对整个文件做一次
 * 整体解压只会解出第一帧（或在超过首帧声明的内容大小时直接失败），所以必须
 * 按帧结构定位边界、逐帧解压。移植自 dsh-tui-vscode 的 sessions.ts（其又移植
 * 自 dsh-TUI 的 sessions/frames.ts：31 MB 语料上与魔数扫描结果完全一致、零误报）。
 */
object ZstdFrames {

    /** zstd 帧魔数（小端 28 B5 2F FD）。 */
    const val ZSTD_MAGIC: Long = 0xFD2FB528L

    /** 一个结构完整帧的字节区间；[end] 为排他边界。 */
    data class FrameRange(val start: Int, val end: Int)

    fun readUInt32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    fun hasMagic(b: ByteArray, off: Int): Boolean =
        off >= 0 && off + 4 <= b.size && readUInt32LE(b, off) == ZSTD_MAGIC

    /**
     * 定位自 [start] 起的帧的结束位置（不解压）。按 RFC 8878 依次走
     * Frame_Header 与每个 Block_Header；遇到 Reserved 块类型说明这不是帧。
     * @return 帧的排他结束偏移；不是完整帧时返回 -1。
     */
    fun frameEnd(buffer: ByteArray, start: Int): Int {
        var at = start
        if (at < 0 || at + 5 > buffer.size) return -1
        if (readUInt32LE(buffer, at) != ZSTD_MAGIC) return -1
        at += 4

        val descriptor = buffer[at].toInt() and 0xFF
        at += 1
        val contentSizeFlag = descriptor shr 6
        val singleSegment = (descriptor shr 5) and 1
        val hasChecksum = (descriptor shr 2) and 1
        val dictionaryIdFlag = descriptor and 3

        if (singleSegment == 0) at += 1
        at += intArrayOf(0, 1, 2, 4)[dictionaryIdFlag]
        at += if (contentSizeFlag == 0) singleSegment else intArrayOf(0, 2, 4, 8)[contentSizeFlag]
        if (at > buffer.size) return -1

        while (true) {
            if (at + 3 > buffer.size) return -1
            val header = (buffer[at].toInt() and 0xFF) or
                ((buffer[at + 1].toInt() and 0xFF) shl 8) or
                ((buffer[at + 2].toInt() and 0xFF) shl 16)
            at += 3
            val isLast = header and 1
            val blockType = (header shr 1) and 3
            val blockSize = header ushr 3
            if (blockType == 3) return -1 // Reserved —— 不是帧
            at += if (blockType == 1) 1 else blockSize
            if (at > buffer.size) return -1
            if (isLast == 1) break
        }

        if (hasChecksum == 1) at += 4
        return if (at <= buffer.size) at else -1
    }

    /** 自 [from] 向前收集所有结构完整的帧。 */
    fun walkFrames(buffer: ByteArray, from: Int = 0, maxFrames: Int = Int.MAX_VALUE): List<FrameRange> {
        val frames = ArrayList<FrameRange>()
        var at = from
        while (at < buffer.size && frames.size < maxFrames) {
            val end = frameEnd(buffer, at)
            if (end < 0) break
            frames.add(FrameRange(at, end))
            at = end
        }
        return frames
    }

    /**
     * 缓冲区内所有结构完整的帧，越过损坏帧继续：前向遍历停在坏帧上时，逐字节
     * 找下一个魔数候选再试（巧合的魔数仍须拼出合法块链才能通过 [frameEnd]，
     * 误报不构成实际风险）。头部窗口没有 EOF 锚点，只能靠它继续恢复坏帧之后
     * 的帧——正是它让「头部窗口里含损坏帧」的日志不丢后续帧。
     */
    fun walkFramesResuming(buffer: ByteArray, maxFrames: Int = Int.MAX_VALUE): List<FrameRange> {
        val frames = ArrayList<FrameRange>()
        var at = 0
        while (at < buffer.size && frames.size < maxFrames) {
            val end = frameEnd(buffer, at)
            if (end >= 0) {
                frames.add(FrameRange(at, end))
                at = end
                continue
            }
            var next = -1
            var i = at + 1
            while (i + 4 <= buffer.size) {
                if (readUInt32LE(buffer, i) == ZSTD_MAGIC) {
                    next = i
                    break
                }
                i++
            }
            if (next < 0) break
            at = next
        }
        return frames
    }

    /**
     * 在「缓冲区末字节即文件末字节」的窗口内重新同步帧边界：按序尝试每个魔数
     * 候选，取第一条恰好落在缓冲区末尾的帧链。
     */
    fun resyncFrames(buffer: ByteArray): List<FrameRange> {
        var at = 0
        while (at + 4 <= buffer.size) {
            if (readUInt32LE(buffer, at) == ZSTD_MAGIC) {
                val frames = walkFrames(buffer, at)
                val last = frames.lastOrNull()
                if (last != null && last.end == buffer.size) return frames
            }
            at++
        }
        return emptyList()
    }

    /**
     * 整个日志的全部结构完整帧：先做前向遍历；若提前停下（文件中部帧撕裂或
     * 损坏）再做尾部重同步，避免丢掉尾部帧——当前标题就在那里。
     */
    fun zstdFrames(buffer: ByteArray): List<FrameRange> {
        val head = walkFrames(buffer)
        val headEnd = if (head.isNotEmpty()) head.last().end else 0
        if (headEnd == buffer.size) return head
        val tail = resyncFrames(buffer)
        if (tail.isEmpty()) return head
        val tailStart = tail.first().start
        return if (tailStart >= headEnd) head + tail else head
    }
}
