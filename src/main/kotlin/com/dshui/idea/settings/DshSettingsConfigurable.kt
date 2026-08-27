package com.dshui.idea.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** 设置页（Settings | Tools | DeepSeek Harness (dsh-tui)），文案纯中文。 */
class DshSettingsConfigurable : SearchableConfigurable {

    private val settings = DshSettings.getInstance()
    private lateinit var snapshot: DshSettings.PluginState

    override fun getId(): String = "dsh-tui.settings"

    override fun getDisplayName(): String = "DeepSeek Harness (dsh-tui)"

    override fun createComponent(): JComponent {
        snapshot = settings.state.copy()
        return panel {
            row("启动命令：") {
                textField()
                    .bindText(settings.state::command)
                    .comment("启动 dsh-TUI 的命令。裸命令名按 IDE 进程的 PATH 解析（Windows 下解析到 dsh-tui.cmd/.bat/.exe），也可填绝对路径。")
                    .columns(36)
            }
            row("附加参数：") {
                textField()
                    .bindText(settings.state::extraArgs)
                    .comment("追加到每次启动的 CLI 参数，空白分隔、支持引号，例如：--lang en")
                    .columns(36)
            }
            row("界面语言：") {
                comboBox(listOf("", "zh", "en"))
                    .bindItem({ settings.state.lang }, { settings.state.lang = it ?: "" })
                    .comment("设置 DSH_TUI_LANG：留空不设置（dsh-tui 用自己的默认值），zh 强制中文，en 强制英文。")
            }
            row("外置编辑器命令：") {
                textField()
                    .bindText(settings.state::editorCommand)
                    .comment("VISUAL/EDITOR 均未设置时导出为 VISUAL，供 TUI 的外置编辑器拉起并等待编辑完成。")
                    .columns(36)
            }
            row {
                checkBox("注入外置编辑器（VISUAL）")
                    .bindSelected(settings.state::injectEditor)
            }
            row("DSH Home 覆盖：") {
                textField()
                    .bindText(settings.state::dshHome)
                    .comment("可选的 \$DSH_HOME 覆盖（留空 = 继承 IDE 进程值，默认 ~/.dsh）。会话列表按此目录读取。")
                    .columns(36)
            }
            row {
                checkBox("选区变化时自动插入 @引用（实验性）")
                    .bindSelected(settings.state::autoInsertMention)
                    .comment("选中代码变化时自动把 @绝对路径 L起-止 键入运行中的 dsh-tui 输入框（300ms 防抖；默认关闭以避免抢占输入框/刷屏）。")
            }
        }
    }

    private fun DshSettings.PluginState.differsFrom(other: DshSettings.PluginState): Boolean =
        command != other.command ||
            extraArgs != other.extraArgs ||
            lang != other.lang ||
            injectEditor != other.injectEditor ||
            editorCommand != other.editorCommand ||
            dshHome != other.dshHome ||
            autoInsertMention != other.autoInsertMention

    override fun isModified(): Boolean =
        if (::snapshot.isInitialized) settings.state.differsFrom(snapshot) else false

    override fun apply() {
        // 绑定器已把 UI 值写回 state；更新快照，持久化由平台完成
        snapshot = settings.state.copy()
    }

    override fun reset() {
        if (!::snapshot.isInitialized) return
        val s = settings.state
        s.command = snapshot.command
        s.extraArgs = snapshot.extraArgs
        s.lang = snapshot.lang
        s.injectEditor = snapshot.injectEditor
        s.editorCommand = snapshot.editorCommand
        s.dshHome = snapshot.dshHome
        s.autoInsertMention = snapshot.autoInsertMention
    }
}
