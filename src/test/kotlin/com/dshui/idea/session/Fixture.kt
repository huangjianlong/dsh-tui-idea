package com.dshui.idea.session

import com.github.luben.zstd.Zstd
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/** 测试夹具：构造 dsh 会话目录结构与 zstd 帧链日志。 */
object Fixture {

    private val gson = Gson()

    fun line(vararg pairs: Pair<String, Any?>): String {
        val obj = JsonObject()
        for ((k, v) in pairs) {
            when (v) {
                null -> {}
                is String -> obj.addProperty(k, v)
                is Number -> obj.addProperty(k, v)
                is Boolean -> obj.addProperty(k, v)
                is JsonObject -> obj.add(k, v)
            }
        }
        return gson.toJson(obj)
    }

    /** 把若干 JSON 行压成一个 zstd 帧（模拟一批落盘）。 */
    fun frame(vararg lines: String): ByteArray =
        Zstd.compress(lines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8), 3)

    fun concat(vararg frames: ByteArray): ByteArray {
        val out = ByteArray(frames.sumOf { it.size })
        var at = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, at, f.size)
            at += f.size
        }
        return out
    }

    fun writeSession(dshHome: Path, group: String, id: String, bytes: ByteArray, name: String = "session.jsonl.zstd"): Path {
        val dir = dshHome.resolve("sessions").resolve(group).resolve(id)
        Files.createDirectories(dir)
        val file = dir.resolve(name)
        Files.write(file, bytes)
        return file
    }

    fun writeGzipSession(dshHome: Path, group: String, id: String, lines: List<String>): Path {
        val dir = dshHome.resolve("sessions").resolve(group).resolve(id)
        Files.createDirectories(dir)
        val file = dir.resolve("session.jsonl")
        GZIPOutputStream(Files.newOutputStream(file)).use { gz ->
            gz.write(lines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8))
        }
        return file
    }

    fun sessionHeader(id: String, cwd: String, createdAt: Long, origin: String? = null, parent: String? = null): String =
        line(
            "type" to "session",
            "id" to id,
            "cwd" to cwd,
            "createdAt" to createdAt,
            "origin" to origin,
            "parentSession" to parent,
        )

    fun titleEvent(seq: Long, title: String): String =
        line("type" to "session/title", "seq" to seq, "data" to JsonObject().also { it.addProperty("title", title) })

    fun userMessage(text: String, sourceKind: String? = "user"): String {
        val data = JsonObject().also {
            it.addProperty("role", "user")
            it.add("content", gson.toJsonTree(text))
            sourceKind?.let { k -> it.add("source", gson.toJsonTree(mapOf("kind" to k))) }
        }
        return line("type" to "user/message", "data" to data)
    }

    fun splicedPrompt(text: String): String {
        val data = JsonObject().also {
            it.add("inserted", gson.toJsonTree(listOf(mapOf("role" to "user", "content" to text, "source" to mapOf("kind" to "user")))))
        }
        return line("type" to "agent/inbox/spliced", "data" to data)
    }

    fun writeStorages(dshHome: Path, projcache: String, workspace: String) {
        val dir = dshHome.resolve("storages")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("session_projcache.json"), projcache)
        Files.writeString(dir.resolve("workspace.json"), workspace)
    }

    fun tempHome(): Path = Files.createTempDirectory("dsh-test-home")
}
