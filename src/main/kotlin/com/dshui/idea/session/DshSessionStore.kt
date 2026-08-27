package com.dshui.idea.session

import com.dshui.idea.session.DshSessionLog.HeadResult
import com.github.luben.zstd.Zstd
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * dsh 会话的发现、元数据与变更操作（对齐 dsh-tui-vscode 的 sessions.ts）：
 * 会话树 `$DSH_HOME/sessions/<cwd编码分组>/<会话id>/session.jsonl.zstd`、
 * TUI MRU（`~/.dsh-tui/last-used.json`）、存储账本（`storages/session_projcache.json`）、
 * 工作区归档集（`storages/workspace.json`）。
 */
object DshSessionStore {

    data class SessionFile(val id: String, val group: String, val file: Path)

    class StorageMeta(
        val titles: Map<String, String> = emptyMap(),
        val cwds: Map<String, String> = emptyMap(),
        val blanks: Map<String, Boolean> = emptyMap(),
    )

    class ListOptions(
        /** 只显示 cwd 属于这些目录（相等或后代）的会话；无可解析 cwd 的会话被排除。 */
        val workspaceDirs: List<String>? = null,
        /** 隐藏没有任何人类提问的纯启动会话。 */
        val hideEmpty: Boolean = false,
        /** 隐藏委派的子代理运行（会话头 origin == 'subagent'）。 */
        val hideSubagents: Boolean = false,
        /** 隐藏工作区域归档集里的会话（与 dsh web 会话列表一致）。 */
        val hideArchived: Boolean = false,
    )

    /** DSH home 解析：显式覆盖（非空）→ $DSH_HOME → ~/.dsh。 */
    fun resolveDshHome(override: String?): Path {
        val trimmed = override?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return Path.of(trimmed)
        val env = System.getenv("DSH_HOME")
        if (!env.isNullOrBlank()) return Path.of(env)
        return Path.of(System.getProperty("user.home"), ".dsh")
    }

    /** 遍历 DSH 会话树，返回每个会话的日志文件。 */
    fun findSessionFiles(dshHome: Path): List<SessionFile> {
        val root = dshHome.resolve("sessions")
        val out = ArrayList<SessionFile>()
        val groups = try {
            Files.list(root).use { it.filter(Files::isDirectory).toList() }
        } catch (e: IOException) {
            return out
        }
        for (groupPath in groups) {
            val entries = try {
                Files.list(groupPath).use { it.filter(Files::isDirectory).toList() }
            } catch (e: IOException) {
                continue
            }
            for (sessionDir in entries) {
                for (name in listOf("session.jsonl.zstd", "session.jsonl")) {
                    val file = sessionDir.resolve(name)
                    if (Files.isRegularFile(file)) {
                        out.add(SessionFile(sessionDir.fileName.toString(), groupPath.fileName.toString(), file))
                        break
                    }
                }
            }
        }
        return out
    }

    private val sep = File.separator

    /** 解码 cwd 编码的分组目录名：`--D-a-b--` → `D:\a\b`（尽力而为，连字符本就有歧义）。 */
    fun decodeGroupDir(name: String): String? {
        val m = Regex("^--(.+)--$").find(name) ?: return null
        val inner = m.groupValues[1]
        val parts = inner.split('-')
        if (parts.isNotEmpty() && parts[0].length == 1 && parts[0].matches(Regex("[A-Za-z]"))) {
            return parts[0] + ":" + sep + parts.drop(1).joinToString(sep)
        }
        return inner.replace("-", sep)
    }

    /** 路径末段（分隔符无关）；空路径返回 null。 */
    fun pathBase(p: String): String? {
        val parts = p.trimEnd('/', '\\').split('/', '\\').filter { it.isNotEmpty() }
        return parts.lastOrNull()
    }

    /** 列表行的回退标签：标题 → cwd 末段 → 占位符。 */
    fun sessionLabel(rec: DshSessionRecord): String {
        rec.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        rec.cwd?.trim()?.let { pathBase(it)?.let { b -> return b } }
        return "未命名会话"
    }

    /** 会话的项目短名：cwd 末段，回退到分组目录名解码。 */
    fun projectNameOf(cwd: String?, group: String?): String? {
        cwd?.trim()?.let { pathBase(it)?.let { b -> return b } }
        group?.let { decodeGroupDir(it)?.let { d -> pathBase(d)?.let { b -> return b } } }
        return null
    }

    /** TUI 的最近使用 MRU 表（会话 id → epoch ms）。 */
    fun readLastUsed(): Map<String, Long> = readJsonMap(Path.of(System.getProperty("user.home"), ".dsh-tui", "last-used.json"))

    private fun readJsonMap(file: Path): Map<String, Long> {
        return try {
            val parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
            if (!parsed.isJsonObject) return emptyMap()
            val out = HashMap<String, Long>()
            for ((k, v) in parsed.asJsonObject.entrySet()) {
                if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) out[k] = v.asLong
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun JsonElement?.strOrNull(): String? =
        if (this != null && !isJsonNull && isJsonPrimitive && asJsonPrimitive.isString) asString else null

    /**
     * dsh-storage 账本（storages/session_projcache.json）：展示标题
     * （tables.sessions[<id>].rows.title.val —— dsh web 会话列表显示的来源）、
     * 权威 identity.cwd、sessionListMetadata.blank（无提问的纯启动会话）标记。
     * 账本只覆盖 TUI 近期落盘的会话，因此只是回退来源、不是主来源。
     */
    fun readStorageMeta(dshHome: Path): StorageMeta {
        val titles = HashMap<String, String>()
        val cwds = HashMap<String, String>()
        val blanks = HashMap<String, Boolean>()
        try {
            val file = dshHome.resolve("storages").resolve("session_projcache.json")
            val parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
            val sessions = parsed.asJsonObject
                .get("tables")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("sessions")?.takeIf { it.isJsonObject }?.asJsonObject
            if (sessions != null) {
                for ((id, entryEl) in sessions.entrySet()) {
                    val entry = entryEl?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val title = entry.get("rows")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("title")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("val").strOrNull()
                    if (!title.isNullOrEmpty()) titles[id] = title.trim()
                    val cwd = entry.get("identity")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("cwd").strOrNull()
                    if (!cwd.isNullOrEmpty()) cwds[id] = cwd.trim()
                    val blank = entry.get("rows")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("sessionListMetadata")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("val")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("blank")
                    if (blank?.isJsonPrimitive == true && blank.asJsonPrimitive.isBoolean && blank.asBoolean) blanks[id] = true
                }
            }
        } catch (e: Exception) {
            // 账本缺失或不可读 —— 日志仍是来源
        }
        return StorageMeta(titles, cwds, blanks)
    }

    /** 工作区域元数据（storages/workspace.json）里的全局归档集。 */
    fun readWorkspaceMeta(dshHome: Path): List<String> = try {
        val file = dshHome.resolve("storages").resolve("workspace.json")
        val parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
        val archived = parsed.asJsonObject
            .get("global")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("archivedSessionIds")
        if (archived?.isJsonArray == true) archived.asJsonArray
            .mapNotNull { it.strOrNull() } else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 归档/取消归档：编辑工作区域的全局归档集（dsh web 会话列表的同款来源）。
     * 写前立即重读、原子替换（tmp + rename，dsh-storage-json 后端自己的纪律）。
     * @return 是否成功（工作区域缺失/不可写时 false）。
     */
    fun setSessionArchived(sessionId: String, archived: Boolean, dshHome: Path): Boolean {
        return try {
            val file = dshHome.resolve("storages").resolve("workspace.json")
            val parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
            if (!parsed.isJsonObject) return false
            val root = parsed.asJsonObject
            val global = root.get("global")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
            val existing = global.get("archivedSessionIds")
                ?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.strOrNull() }?.toMutableList() ?: mutableListOf()
            if (archived && sessionId !in existing) existing.add(sessionId)
            if (!archived) existing.remove(sessionId)
            val arr = com.google.gson.JsonArray()
            for (id in existing) arr.add(id)
            global.add("archivedSessionIds", arr)
            atomicWriteJson(file, root)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun atomicWriteJson(file: Path, json: JsonObject) {
        val tmp = file.parent.resolve(".${UUID.randomUUID()}.tmp")
        Files.writeString(tmp, prettyJson(json) + "\n", StandardCharsets.UTF_8)
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun prettyJson(json: JsonObject): String =
        com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json)

    /** 归一化 cwd 用于比较：正斜杠、去尾斜杠；大小写不敏感平台再折大小写。 */
    private fun normalizeCwd(path: String, caseInsensitive: Boolean): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        return if (caseInsensitive) normalized.lowercase() else normalized
    }

    private fun isContainer(path: String, home: String): Boolean =
        (home.isNotEmpty() && path == home) ||
            Regex("^[a-z]:$").containsMatchIn(path) || // 盘根：C:
            Regex("^//[^/]+/[^/]+$").containsMatchIn(path) || // UNC 共享根：//server/share
            Regex("^//\\?/[a-z]:$", RegexOption.IGNORE_CASE).containsMatchIn(path) || // 扩展盘根：//?/C:
            Regex("^//\\?/unc/[^/]+/[^/]+$", RegexOption.IGNORE_CASE).containsMatchIn(path) // 扩展 UNC 根

    /**
     * 会话的记录 cwd 是否属于某个工作区目录：相等，或记录为子目录（升级前的
     * 启动把子目录记成了 cwd——它们属于同一工作区）。容器目录（$HOME、盘根、
     * UNC 共享根）不是任何人的工作区：边界上只允许精确相等，`~` 永不匹配全机。
     */
    fun sessionCwdMatches(workspaceDir: String, recordedCwd: String, caseInsensitive: Boolean): Boolean {
        val cwd = normalizeCwd(workspaceDir, caseInsensitive)
        val recorded = normalizeCwd(recordedCwd, caseInsensitive)
        if (recorded.isEmpty() || cwd.isEmpty()) return false
        val home = normalizeCwd(System.getProperty("user.home"), caseInsensitive)
        if (isContainer(cwd, home) || isContainer(recorded, home)) return recorded == cwd
        return recorded == cwd || recorded.startsWith("$cwd/")
    }

    /** 压缩一个日志帧并验证（往返一致才可用，坏帧绝不写进共享日志）。 */
    fun compressFrame(text: String): ByteArray? {
        return try {
            val input = text.toByteArray(StandardCharsets.UTF_8)
            val out = Zstd.compress(input, 3)
            if (out.size < 4 || !ZstdFrames.hasMagic(out, 0)) return null
            val back = DshSessionLog.decompressFrame(out) ?: return null
            if (back.contentEquals(input)) out else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 向会话日志追加一个 `session/title` 事件（dsh-TUI /resume 选择器的重命名契约）：
     * 在 EOF 追加一帧 zstd，seq = maxSeq + 1，读取侧最后一个标题生效。只追加、
     * 从不改写已有字节，并发写同一日志的会话安全。
     * @return 是否成功（日志缺失/不可解码/无法产出合法帧时 false）。
     */
    fun appendSessionTitle(file: Path, title: String): Boolean {
        return try {
            val buf = Files.readAllBytes(file)
            if (buf.size <= 4 || !ZstdFrames.hasMagic(buf, 0)) return false
            val text = DshSessionLog.decodeSessionLog(file) ?: return false
        var maxSeq = -1L
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            try {
                val event = JsonParser.parseString(line)
                if (event.isJsonObject) {
                    val seq = event.asJsonObject.get("seq")
                    if (seq?.isJsonPrimitive == true && seq.asJsonPrimitive.isNumber && seq.asLong > maxSeq) {
                        maxSeq = seq.asLong
                    }
                }
            } catch (e: Exception) {
                // 不可解析的行不是 seq 见证
            }
        }
        val event = JsonObject().apply {
            addProperty("type", "session/title")
            addProperty("seq", maxSeq + 1)
            addProperty("time", System.currentTimeMillis())
            add("data", JsonObject().apply { addProperty("title", title) })
        }
        val frame = compressFrame(com.google.gson.Gson().toJson(event) + "\n") ?: return false
        Files.write(file, frame, java.nio.file.StandardOpenOption.APPEND)
        true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 删除会话的日志目录（/resume 选择器的删除契约）。解析后的目录逃出
     * sessions 根时拒绝（符号链接的分组目录可能把递归删除引到根之外）。
     */
    fun deleteSessionLog(file: Path, dshHome: Path): Boolean {
        return try {
            val root = dshHome.resolve("sessions")
            val dir = file.toRealPath().parent
            val realRoot = root.toRealPath()
            val norm: (String) -> String = { p -> if (isWindows()) p.lowercase() else p }
            if (!norm(dir.toString()).startsWith(norm(realRoot.toString()) + sep)) return false
            dir.toFile().deleteRecursively()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isWindows(): Boolean = FileSystems.getDefault().supportedFileAttributeViews().contains("dos") ||
        System.getProperty("os.name", "").lowercase().contains("windows")

    /**
     * 全部会话（带 lastUsed 标注），按 lastUsed 降序、createdAt 降序（TUI /resume
     * 的 MRU 惯例）。标题优先级：日志 `session/title` → 账本标题 → 首条人类提问
     * （web 会话列表自己的来源）。未知 hasPrompt（不可解码日志）按 true 处理——
     * 隐藏真会话是缺陷、显示启动残留只是噪音。
     */
    fun listSessions(dshHome: Path, options: ListOptions): List<DshSessionRecord> {
        val lastUsed = readLastUsed()
        val storage = readStorageMeta(dshHome)
        val archivedIds = readWorkspaceMeta(dshHome).toHashSet()

        val out = ArrayList<DshSessionRecord>()
        for (sf in findSessionFiles(dshHome)) {
            // 只做有界头部读取（64KB）；尾部（当前标题）只为过了过滤器的会话付钱
            val head: HeadResult = DshSessionLog.readSessionHead(sf.file, sf.group) ?: continue
            var rec = head.rec
            if (rec.cwd == null) {
                storage.cwds[rec.id]?.let { ledgerCwd ->
                    rec = rec.copy(cwd = ledgerCwd, project = projectNameOf(ledgerCwd, sf.group))
                }
            }
            val subagent = options.hideSubagents && rec.origin == "subagent"
            val empty = options.hideEmpty && !rec.hasPrompt
            val cwd = rec.cwd
            val outOfWorkspace = !options.workspaceDirs.isNullOrEmpty() &&
                (cwd == null || options.workspaceDirs.none { sessionCwdMatches(it, cwd, isWindows()) })
            if (!subagent && !empty && !outOfWorkspace) {
                rec = DshSessionLog.attachTailTitle(rec, head.whole)
            }
            lastUsed[rec.id]?.let { rec = rec.copy(lastUsed = it) }
            if (storage.titles.containsKey(rec.id) && rec.eventTitle == null) {
                // web 会话列表显示账本标题；让它赢过原始的首条提问回退
                rec = rec.copy(title = storage.titles[rec.id])
            }
            if (storage.blanks[rec.id] == true && rec.hasPrompt) {
                rec = rec.copy(hasPrompt = false)
            }
            out.add(rec)
        }
        return out
            .filter { !options.hideSubagents || it.origin != "subagent" }
            .filter { !options.hideEmpty || it.hasPrompt }
            .filter { !options.hideArchived || it.id !in archivedIds }
            .filter { s ->
                if (options.workspaceDirs.isNullOrEmpty()) return@filter true
                val cwd = s.cwd ?: return@filter false
                options.workspaceDirs.any { sessionCwdMatches(it, cwd, isWindows()) }
            }
            .sortedWith(
                compareByDescending<DshSessionRecord> { it.lastUsed ?: Long.MIN_VALUE }
                    .thenByDescending { it.createdAt ?: 0L },
            )
    }
}
