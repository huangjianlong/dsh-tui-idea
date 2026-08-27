package com.dshui.idea.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchEnvTest {

    @Test
    fun `splitArgs honors quotes`() {
        assertEquals(listOf("--lang", "en"), LaunchEnv.splitArgs("""--lang en"""))
        assertEquals(listOf("--prompt", "hello world"), LaunchEnv.splitArgs("""--prompt "hello world""""))
        assertEquals(listOf("-m", "it works"), LaunchEnv.splitArgs("-m 'it works'"))
        assertEquals(emptyList(), LaunchEnv.splitArgs("   "))
    }

    @Test
    fun `buildLaunchEnv injects lang and dshHome when set`() {
        val env = LaunchEnv.buildLaunchEnv(lang = "zh", injectEditor = false, editorCommand = "", dshHome = """D:\dsh-home""")
        assertEquals("zh", env["DSH_TUI_LANG"])
        assertEquals("""D:\dsh-home""", env["DSH_HOME"])
        assertNull(env["VISUAL"])
    }

    @Test
    fun `buildLaunchEnv stays empty for defaults`() {
        val env = LaunchEnv.buildLaunchEnv(lang = " ", injectEditor = false, editorCommand = "", dshHome = "")
        assertEquals(0, env.size)
    }

    @Test
    fun `path-like commands skip PATH resolution`() {
        assertNull(LaunchEnv.resolveLaunchCommand("""C:\tools\dsh-tui.cmd"""))
        assertNull(LaunchEnv.resolveLaunchCommand("/usr/local/bin/dsh-tui"))
    }
}
