package com.dshui.idea.settings

import com.dshui.idea.terminal.LaunchEnv
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件设置（应用级）。字段与 dsh-tui-vscode 的 contributes.configuration 一一对应。
 */
@Service(Service.Level.APP)
@State(name = "DshTuiSettings", storages = [Storage("dsh-tui.xml")])
class DshSettings : PersistentStateComponent<DshSettings.PluginState> {

    data class PluginState(
        /** 启动 dsh-TUI 的命令（裸命令名走 PATH 解析，也可填绝对路径）。 */
        var command: String = "dsh-tui",
        /** 附加 CLI 参数（空白分隔，支持引号），每次启动都追加。 */
        var extraArgs: String = "",
        /** DSH_TUI_LANG：""（不设置）| zh | en。 */
        var lang: String = "",
        /** VISUAL/EDITOR 均未设置时注入 VISUAL，让 TUI 的外置编辑器拉起 IDE。 */
        var injectEditor: Boolean = true,
        var editorCommand: String = "idea --wait",
        /** $DSH_HOME 覆盖（空 = 继承 IDE 进程/默认 ~/.dsh）。 */
        var dshHome: String = "",
        /** 选区变化时自动插入 @引用（实验性，默认关闭）。 */
        var autoInsertMention: Boolean = false,
    )

    private var state = PluginState()

    override fun getState(): PluginState = state

    override fun loadState(state: PluginState) {
        this.state = state
    }

    fun parsedExtraArgs(): List<String> = LaunchEnv.splitArgs(state.extraArgs)

    companion object {
        @JvmStatic
        fun getInstance(): DshSettings =
            ApplicationManager.getApplication().getService(DshSettings::class.java)
    }
}
