package com.dshui.idea.session

import com.dshui.idea.session.ZstdFrames.FrameRange
import com.github.luben.zstd.ZstdInputStream
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

/** 会话记录（会话列表的一行）。字段语义与 dsh-tui-vscode 的 SessionRecord 一致。 */
data class DshSessionRecord(
    /** 会话 id（日志头 session.id，回退到会话目录名）。 */
    val id: String,
    /** 展示标题（日志 session/title → 账本标题 → 首条人类提问，三级回退后）。 */
    val title: String?,
    /** 仅来自日志 `session/title` 事件的标题（无则 null）。 */
    val eventTitle: String?,
    val cwd: String?,
    /** 项目短名（cwd 末段，回退到分组目录名解码）。 */
    val project: String?,
    /** 创建时间（epoch ms；头里没有时回退文件 mtime）。 */
    val createdAt: Long?,
    /** TUI MRU（~/.dsh-tui/last-used.json）里的最近使用时间（epoch ms）。 */
    val lastUsed: Long?,
    /** 会话头 `origin` —— 'subagent' 表示委派的子代理运行。 */
    val origin: String?,
    /** 会话头 `parentSession`（子代理运行的父会话）。 */
    val parent: String?,
    /** 日志中是否有人类提问；未知的日志默认 true（宁可多显示，不可隐藏真会话）。 */
    val hasPrompt: Boolean,
    /** 日志文件绝对路径。 */
    val file: String,
)

/**
 * dsh 会话日志（session.jsonl.zstd）的解码与摘要读取。
 *
 * 日志结构：每行一个 JSON 事件的 jsonl，整体是「一批落盘追加一帧」的 zstd 帧链。
 * 列表场景只做有界读取：头部 64KB（会话头 + 开场提问 + 头部标题）、尾部 128KB
 * （最近追加的内容——标题会重发、最后一个生效），中间永不触碰。
 */
object DshSessionLog {

    private const val HEAD_WINDOW_BYTES = 64 * 1024
    private const val HEAD_MAX_FRAMES = 128
    private const val TAIL_WINDOW_BYTES = 128 * 1024

    internal class Window(val buffer: ByteArray, val whole: Boolean)

    class HeadResult(val rec: DshSessionRecord, val whole: Boolean)

    /** 解压单个 zstd 帧；失败（撕裂/不完整）返回 null，由调用方跳过该帧。 */
    fun decompressFrame(frame: ByteArray): ByteArray? = try {
        ZstdInputStream(ByteArrayInputStream(frame)).use { input ->
            val out = ByteArrayOutputStream(maxOf(64, frame.size * 4))
            input.copyTo(out)
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    /** 解压一段字节里的所有帧并拼接为一段文本（跳过失败帧；全部失败返回 null）。 */
    private fun decodeFramesText(buffer: ByteArray, frames: List<FrameRange>): String? {
        val parts = ArrayList<ByteArray>()
        for (frame in frames) {
            val slice = buffer.copyOfRange(frame.start, frame.end)
            decompressFrame(slice)?.let { parts.add(it) }
        }
        if (parts.isEmpty()) return null
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        for (p in parts) out.write(p)
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * 整体解压一份会话日志（zstd 帧链 / gzip / 纯 jsonl 三种格式）。
     * 结构完整的帧全部解压失败时返回 null（日志实质不可读），交由调用方
     * 按未知处理而不是当作「读到末尾的空日志」。
     */
    fun decodeSessionLog(path: Path): String? {
        val buf = try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            return null
        }
        if (buf.size > 4 && ZstdFrames.hasMagic(buf, 0)) {
            val frames = ZstdFrames.zstdFrames(buf)
            if (frames.isNotEmpty()) {
                return decodeFramesText(buf, frames)
            }
            return null // 没有任何结构完整的帧 —— 无法整体解码
        }
        if (buf.size > 2 && buf[0] == 0x1f.toByte() && buf[1] == 0x8b.toByte()) {
            return try {
                GZIPInputStream(ByteArrayInputStream(buf)).use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: IOException) {
                null
            }
        }
        return buf.toString(Charsets.UTF_8)
    }

    /** 从文件一端读取一个有界窗口；[whole] 表示窗口覆盖了整个文件。 */
    internal fun readWindow(path: Path, bytes: Int, tail: Boolean): Window? = try {
        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            val size = ch.size()
            val length = minOf(bytes.toLong(), size).toInt()
            if (length == 0) return Window(ByteArray(0), whole = true)
            val buf = ByteArray(length)
            ch.position(if (tail) size - length else 0L)
            var read = 0
            while (read < length) {
                val n = ch.read(ByteBuffer.wrap(buf, read, length - read))
                if (n < 0) break
                read += n
            }
            Window(if (read == length) buf else buf.copyOf(read), whole = read.toLong() == size)
        }
    } catch (e: IOException) {
        null
    }

    /** 把帧解码为 JSON 事件（宽容：坏帧跳过、坏行跳过、只收 JSON 对象）。 */
    private fun decodeFrames(buffer: ByteArray, frames: List<FrameRange>): List<JsonObject> {
        val events = ArrayList<JsonObject>()
        for (frame in frames) {
            val text = decompressFrame(buffer.copyOfRange(frame.start, frame.end))
                ?.toString(Charsets.UTF_8) ?: continue
            for (line in text.split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                try {
                    val parsed = JsonParser.parseString(trimmed)
                    if (parsed.isJsonObject) events.add(parsed.asJsonObject)
                } catch (e: Exception) {
                    // 尾部半写的行；前面的行仍然有效
                }
            }
        }
        return events
    }

    /** 尾部窗口解码：窗口覆盖整个文件时直接前向遍历，否则以 EOF 重同步。 */
    private fun decodeTail(window: Window): List<JsonObject> =
        decodeFrames(
            window.buffer,
            if (window.whole) ZstdFrames.walkFrames(window.buffer) else ZstdFrames.resyncFrames(window.buffer),
        )

    private fun JsonElement?.strOrNull(): String? =
        if (this != null && !isJsonNull && isJsonPrimitive && asJsonPrimitive.isString) asString else null

    /** content（字符串或 {type:"text"} 块数组）里的第一段文本，截 80 字符。 */
    private fun firstTextOfContent(content: JsonElement?): String? {
        if (content == null || content.isJsonNull) return null
        if (content.isJsonPrimitive && content.asJsonPrimitive.isString) {
            val t = content.asString.trim()
            return if (t.isNotEmpty()) t.take(80) else null
        }
        if (content is JsonArray) {
            for (block in content) {
                if (block == null || !block.isJsonObject) continue
                val obj = block.asJsonObject
                val type = obj.get("type").strOrNull()
                val text = obj.get("text").strOrNull()
                if (type == "text" && text != null && text.trim().isNotEmpty()) {
                    return text.trim().take(80)
                }
            }
        }
        return null
    }

    /**
     * source 是否标记「人在键盘上敲的」。插件注入、指令快照、子代理回报也会
     * 以 user 角色消息进入日志，把它们算上会虚报「有过对话」（与 dsh-TUI 的
     * sessions/digest 同一规则）。
     */
    private fun isHumanSource(source: JsonElement?): Boolean {
        if (source == null || source.isJsonNull) return true
        if (!source.isJsonObject) return false
        return source.asJsonObject.get("kind").strOrNull() == "user"
    }

    /** 一行日志携带的人类提问（user/message 或 agent/inbox/spliced 两种形态）。 */
    private fun humanPromptOf(event: JsonObject): String? {
        val data = event.get("data")
        if (data == null || !data.isJsonObject) return null
        val obj = data.asJsonObject
        when (event.get("type").strOrNull()) {
            "user/message" ->
                return if (isHumanSource(obj.get("source"))) firstTextOfContent(obj.get("content")) else null
            "agent/inbox/spliced" -> {
                val inserted = obj.get("inserted")
                if (inserted == null || !inserted.isJsonArray) return null
                for (message in inserted.asJsonArray) {
                    if (message == null || !message.isJsonObject) continue
                    val entry = message.asJsonObject
                    if (entry.get("role").strOrNull() != "user") continue
                    if (!isHumanSource(entry.get("source"))) continue
                    val text = firstTextOfContent(entry.get("content"))
                    if (text != null) return text
                }
                return null
            }
        }
        return null
    }

    private class Header {
        var id: String? = null
        var cwd: String? = null
        var createdAt: Long? = null
        var origin: String? = null
        var parentSession: String? = null
    }

    private fun applyEvent(event: JsonObject, header: Header, state: TitleAndPrompt) {
        val type = event.get("type").strOrNull()
        when {
            type == "session" && event.get("id") != null -> {
                header.id = event.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                header.cwd = event.get("cwd").strOrNull()
                header.createdAt = event.get("createdAt")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                header.origin = event.get("origin").strOrNull()
                header.parentSession = event.get("parentSession").strOrNull()
            }
            type == "session/title" -> {
                val title = event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("title").strOrNull()
                if (title != null && title.trim().isNotEmpty()) state.lastTitle = title.trim()
            }
            type == "user/message" || type == "agent/inbox/spliced" -> {
                val prompt = humanPromptOf(event)
                if (prompt != null) {
                    state.sawPrompt = true
                    if (state.firstPrompt == null) state.firstPrompt = prompt
                } else if (type == "user/message") {
                    val data = event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (isHumanSource(data?.get("source"))) {
                        // 人类 user/message 但提不出文本（如纯图片提问）——仍算有过对话
                        state.sawPrompt = true
                    }
                }
            }
        }
    }

    internal class TitleAndPrompt {
        var lastTitle: String? = null
        var firstPrompt: String? = null
        var sawPrompt: Boolean = false
    }

    private fun fileMtime(path: Path): Long? = try {
        Files.getLastModifiedTime(path).toMillis()
    } catch (e: IOException) {
        null
    }

    private fun buildRecord(
        header: Header,
        state: TitleAndPrompt,
        hasPrompt: Boolean,
        path: Path,
        group: String?,
    ): DshSessionRecord {
        val createdAt = header.createdAt ?: fileMtime(path)
        return DshSessionRecord(
            id = header.id ?: path.parent.fileName.toString(),
            title = state.lastTitle ?: state.firstPrompt,
            eventTitle = state.lastTitle,
            cwd = header.cwd,
            project = DshSessionStore.projectNameOf(header.cwd, group),
            createdAt = createdAt,
            lastUsed = null,
            origin = header.origin,
            parent = header.parentSession,
            hasPrompt = hasPrompt,
            file = path.toString(),
        )
    }

    /**
     * 整读路径（非 zstd 日志 / 全量调用方）：头部 + 标题（最后一个 session/title
     * 生效）+ 是否有人类提问。宽容：无会话头也产出记录（id 取目录名、cwd 取
     * 分组解码、createdAt 取 mtime）。
     */
    fun readSessionRecord(path: Path, group: String?): DshSessionRecord? {
        val text = decodeSessionLog(path) ?: return null
        val header = Header()
        val state = TitleAndPrompt()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val event = try {
                val parsed = JsonParser.parseString(line)
                if (parsed.isJsonObject) parsed.asJsonObject else continue
            } catch (e: Exception) {
                continue
            }
            applyEvent(event, header, state)
        }
        return buildRecord(header, state, state.sawPrompt, path, group)
    }

    /**
     * 有界头部读取（64KB、至多 128 帧）：会话头字段、首条人类提问、窗口内的
     * session/title（最后一个生效）。hasPrompt 遵循 TUI digest 规则：窗口内
     * 见到人类提问，或日志大到窗口不可能读到末尾。非 zstd 日志走整读路径。
     */
    fun readSessionHead(path: Path, group: String?): HeadResult? {
        val window = readWindow(path, HEAD_WINDOW_BYTES, tail = false) ?: return null
        if (!ZstdFrames.hasMagic(window.buffer, 0)) {
            val rec = readSessionRecord(path, group) ?: return null
            return HeadResult(rec, whole = true)
        }
        val events = decodeFrames(window.buffer, ZstdFrames.walkFramesResuming(window.buffer, HEAD_MAX_FRAMES))
        val header = Header()
        val state = TitleAndPrompt()
        for (event in events) applyEvent(event, header, state)
        val rec = buildRecord(header, state, state.sawPrompt || !window.whole, path, group)
        return HeadResult(rec, whole = window.whole)
    }

    /**
     * 把尾部窗口里的「当前标题」补到记录上（标题会重发，最后一个生效）。
     * 头部窗口已覆盖整个日志时为 no-op。
     */
    fun attachTailTitle(rec: DshSessionRecord, headWhole: Boolean): DshSessionRecord {
        if (headWhole) return rec
        val path = Path.of(rec.file)
        val window = readWindow(path, TAIL_WINDOW_BYTES, tail = true) ?: return rec
        var title: String? = null
        for (event in decodeTail(window)) {
            if (event.get("type").strOrNull() != "session/title") continue
            val t = event.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("title").strOrNull()
            if (t != null && t.trim().isNotEmpty()) title = t.trim()
        }
        return if (title != null) rec.copy(title = title, eventTitle = title) else rec
    }

    /** 头 + 尾的有界摘要（整读路径的窗口化对应物，至多读约 192KB）。 */
    fun readSessionSummary(path: Path, group: String?): DshSessionRecord? {
        val head = readSessionHead(path, group) ?: return null
        return attachTailTitle(head.rec, head.whole)
    }
}
