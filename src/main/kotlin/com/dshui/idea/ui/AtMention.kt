package com.dshui.idea.ui

/**
 * 生成插入到 dsh-tui 输入框的 @引用（对齐 dsh-tui-vscode 的 at-mention.ts）：
 * dsh-tui 的 @ 提及把相对路径按「会话自己的 cwd」解析，所以传正斜杠绝对路径
 * 直通；不认 `#L` 行区间，行号退化为空格分隔的纯文本提示；路径含空白用
 * `@"路径"` 形式。输出：`@D:/repo/a.ts` / `@D:/repo/a.ts L12` / `@D:/repo/a.ts L12-14`。
 */
object AtMention {

    /** 平台路径归一化为正斜杠。 */
    fun normalizeMentionPath(fsPath: String): String = fsPath.replace('\\', '/')

    /**
     * @param isEmpty 选区是否为空（空则引用整个文件）
     * @param startLine 选区起始行（0 基，编辑器语义）
     * @param endLine 选区结束行（0 基）
     */
    fun buildAtMention(path: String, isEmpty: Boolean, startLine: Int, endLine: Int): String {
        val reference = if (Regex("\\s").containsMatchIn(path)) "@\"$path\"" else "@$path"
        if (isEmpty) return reference
        val start = startLine + 1 // 转 1 基
        val end = endLine + 1
        val range = if (start != end) "L$start-$end" else "L$start"
        return "$reference $range"
    }

    /** 紧凑相对时间（Claude Code 风格）：刚刚 / 12m / 3h / 2d。 */
    fun relativeTime(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        val minutes = diff / 60000
        if (minutes < 1) return "刚刚"
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h"
        return "${hours / 24}d"
    }
}
