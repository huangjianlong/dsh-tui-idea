package com.dshui.idea.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * 会话改动清单：流式读取会话日志，从 tool/call 事件提取该会话编辑过的文件
 * （不整体解压——JSONL 压缩比常见 5-15x，整体解压会无界占用堆）。逐帧/
 * 逐行处理并累计解压字节数，超过 [MAX_DECOMPRESSED_BYTES] 即中止。
 * 编辑类工具：`edit`（参数 file_path）、`write`（file_path）、
 * `str_replace_editor`（path + command；view 是只读，不计入）。
 */
object DshSessionChanges {

    /** 单个文件的改动汇总（次数按事件计）。 */
    class Change(
        val path: String,
        val edits: Int,
        val creates: Int,
        val lastAtMs: Long,
    )

    private const val MAX_LOG_BYTES = 32L * 1024 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 128L * 1024 * 1024

    private class Acc {
        var edits = 0
        var creates = 0
        var lastAtMs = 0L
    }

    private class Collector {
        val acc = LinkedHashMap<String, Acc>()
        var consumed = 0L
        fun line(line: String) {
            if (line.isEmpty() || !line.contains("\"tool/call\"")) return
            val event = try {
                JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject ?: return
            } catch (e: Exception) {
                return
            }
            val data = event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val name = data.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return
            val args = parseArguments(data.get("arguments")) ?: return
            val time = event.get("time")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L
            when (name) {
                "edit" -> accumulate(argPath(args, "file_path"), creates = false, time)
                "write" -> accumulate(argPath(args, "file_path"), creates = true, time)
                "str_replace_editor" -> {
                    val command = args.get("command")?.takeIf { it.isJsonPrimitive }?.asString
                    if (command != "view") {
                        accumulate(argPath(args, "path"), creates = command == "create", time)
                    }
                }
            }
        }

        fun result(): List<Change> =
            acc.map { (path, a) -> Change(path, a.edits, a.creates, a.lastAtMs) }
                .sortedByDescending { it.lastAtMs }

        private fun accumulate(path: String?, creates: Boolean, time: Long) {
            if (path == null) return
            val a = acc.getOrPut(path) { Acc() }
            if (creates) a.creates++ else a.edits++
            if (time > a.lastAtMs) a.lastAtMs = time
        }
    }

    /**
     * 提取会话改动文件列表（按最近操作时间降序）。
     * @return null 表示日志过大（压缩超 32MB / 解压超 128MB）或不可解码；空列表表示无编辑事件。
     */
    fun extract(file: Path): List<Change>? {
        val size = try {
            Files.size(file)
        } catch (e: Exception) {
            return null
        }
        if (size > MAX_LOG_BYTES) return null
        val buf = try {
            Files.readAllBytes(file)
        } catch (e: Exception) {
            return null
        }
        val collector = Collector()
        return when {
            buf.size > 4 && ZstdFrames.hasMagic(buf, 0) -> extractZstd(buf, collector)
            buf.size > 2 && buf[0] == 0x1f.toByte() && buf[1] == 0x8b.toByte() -> extractGzip(file, collector)
            else -> extractPlain(buf, collector)
        }
    }

    /** zstd 帧链：逐帧解压逐行扫（跨帧行缓冲），解压累计超限返回 null。 */
    private fun extractZstd(buf: ByteArray, collector: Collector): List<Change>? {
        val frames = ZstdFrames.zstdFrames(buf)
        if (frames.isEmpty()) return null
        var carry = StringBuilder()
        for (frame in frames) {
            val text = DshSessionLog.decompressFrame(buf.copyOfRange(frame.start, frame.end))
                ?.toString(Charsets.UTF_8) ?: continue
            collector.consumed += text.length
            if (collector.consumed > MAX_DECOMPRESSED_BYTES) return null
            val lines = text.split('\n')
            for ((i, piece) in lines.withIndex()) {
                if (i < lines.size - 1) {
                    if (carry.isNotEmpty()) {
                        carry.append(piece)
                        collector.line(carry.toString().trim())
                        carry = StringBuilder()
                    } else {
                        collector.line(piece.trim())
                    }
                } else {
                    carry.append(piece) // 帧尾半行，留给下一帧
                }
            }
        }
        if (carry.isNotBlank()) collector.line(carry.toString().trim())
        return collector.result()
    }

    private fun extractGzip(file: Path, collector: Collector): List<Change>? {
        try {
            GZIPInputStream(Files.newInputStream(file)).use { gz ->
                BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).useLines { lines ->
                    for (line in lines) {
                        collector.consumed += line.length + 1
                        if (collector.consumed > MAX_DECOMPRESSED_BYTES) return null
                        collector.line(line.trim())
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return collector.result()
    }

    private fun extractPlain(buf: ByteArray, collector: Collector): List<Change>? {
        if (buf.size.toLong() > MAX_DECOMPRESSED_BYTES) return null
        for (line in buf.toString(Charsets.UTF_8).split('\n')) collector.line(line.trim())
        return collector.result()
    }

    private fun parseArguments(element: com.google.gson.JsonElement?): JsonObject? =
        try {
            val raw = element?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        }

    /** 参数里的路径（Windows 反斜杠由 JSON 解码还原）。 */
    private fun argPath(args: JsonObject, key: String): String? =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
