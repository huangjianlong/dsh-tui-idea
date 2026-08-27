package com.dshui.idea.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshLaunchPlannerTest {

    private fun plan(
        resumeSessionId: String? = null,
        resumeLast: Boolean = false,
        resolvedTui: String? = null,
        resolvedDsh: String? = null,
        lastUsedSessionId: String? = null,
        isWindows: Boolean = true,
        command: String = "dsh-tui",
    ) = DshLaunchPlanner.plan(
        commandSetting = command,
        extraArgs = listOf("--flag"),
        lang = "",
        injectEditor = false,
        editorCommand = "",
        dshHome = "",
        resumeSessionId = resumeSessionId,
        resumeLast = resumeLast,
        resolvedTui = resolvedTui,
        resolvedDsh = resolvedDsh,
        lastUsedSessionId = lastUsedSessionId,
        isWindows = isWindows,
    )

    @Test
    fun `specific session resume goes through env without --resume`() {
        val plan = plan(resumeSessionId = "session-1", resolvedDsh = "C:/npm/dsh.cmd")
        assertEquals("session-1", plan.env["DSH_TUI_RESUME_SESSION"])
        assertEquals("session-1", plan.env["DSH_CC_RESUME_SESSION"])
        assertFalse(plan.shellCommand.contains("--resume"))
    }

    @Test
    fun `resume last prefers last-used id via env`() {
        val plan = plan(resumeLast = true, lastUsedSessionId = "session-9", resolvedTui = "C:/npm/dsh-tui.cmd")
        assertEquals("session-9", plan.env["DSH_TUI_RESUME_SESSION"])
        assertFalse(plan.shellCommand.contains("--resume"))
        assertEquals(DshLaunchPlanner.LaunchPlan.Mode.TUI_LAUNCHER, plan.mode)
    }

    @Test
    fun `resume last falls back to --resume flag when only a tui launcher exists`() {
        val plan = plan(resumeLast = true, lastUsedSessionId = null, resolvedTui = "C:/npm/dsh-tui.cmd")
        assertNull(plan.env["DSH_TUI_RESUME_SESSION"])
        assertTrue(plan.shellCommand.contains("--resume"))
    }

    @Test
    fun `falls back to dsh profile without tui launcher`() {
        val plan = plan(resolvedTui = null, resolvedDsh = "C:/npm/dsh.cmd")
        assertEquals(DshLaunchPlanner.LaunchPlan.Mode.DSH_PROFILE, plan.mode)
        assertEquals(
            listOf("cmd.exe", "/c", "C:/npm/dsh.cmd", "--profile", "dsh-tui", "--flag"),
            plan.shellCommand,
        )
        assertFalse(plan.shellCommand.contains("--resume"))
    }

    @Test
    fun `non-batch commands are not wrapped with cmd exe`() {
        val plan = plan(resolvedTui = null, resolvedDsh = "/usr/bin/dsh", isWindows = false)
        assertEquals(listOf("/usr/bin/dsh", "--profile", "dsh-tui", "--flag"), plan.shellCommand)
    }

    @Test
    fun `explicit path command wins`() {
        val plan = plan(command = """D:\tools\my-tui.exe""", resolvedDsh = "C:/npm/dsh.cmd")
        assertEquals(DshLaunchPlanner.LaunchPlan.Mode.EXPLICIT_COMMAND, plan.mode)
        assertEquals(listOf("""D:\tools\my-tui.exe""", "--flag"), plan.shellCommand)
    }

    @Test
    fun `bare command mode notes the missing environment`() {
        val plan = plan(resolvedTui = null, resolvedDsh = null)
        assertEquals(DshLaunchPlanner.LaunchPlan.Mode.BARE_COMMAND, plan.mode)
        assertTrue(plan.notes.isNotEmpty())
    }
}
