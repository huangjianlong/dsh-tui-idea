package com.dshui.idea.setup

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshEnvironmentCheckTest {

    private fun tempHome() = Files.createTempDirectory("dsh-env-test")

    @Test
    fun `empty machine reports fixable problems`() {
        val home = tempHome()
        val report = DshEnvironmentCheck.check(pathEnv = "", dshHome = home, isWindows = true)
        assertFalse(report.ready)
        val ids = report.problems.map { it.id }
        assertTrue("node" in ids)
        assertTrue("pnpm" in ids)
        assertTrue("dsh" in ids)
        assertTrue("auth" in ids)
    }

    @Test
    fun `fully provisioned machine is ready`() {
        val home = tempHome()
        val bin = home.resolve("bin")
        Files.createDirectories(bin)
        Files.createFile(bin.resolve("node.exe"))
        Files.createFile(bin.resolve("dsh.cmd"))
        Files.createFile(bin.resolve("pnpm.cmd"))
        Files.writeString(home.resolve(".credentials.yaml"), "ok")
        Files.createDirectories(
            home.resolve("profiles").resolve("dsh-tui").resolve("node_modules")
                .resolve("@deepseek-harness-tui").resolve("dsh-tui"),
        )
        val report = DshEnvironmentCheck.check(pathEnv = bin.toString(), dshHome = home, isWindows = true)
        assertTrue(report.problems.isEmpty(), "problems: ${report.problems.map { it.id }}")
        assertTrue(report.ready)
    }

    @Test
    fun `global skin insert missing from profile is detected as fixable`() {
        val home = tempHome()
        val bin = home.resolve("bin")
        Files.createDirectories(bin)
        Files.createFile(bin.resolve("node.exe"))
        Files.createFile(bin.resolve("dsh.cmd"))
        Files.createFile(bin.resolve("pnpm.cmd"))
        Files.writeString(home.resolve(".credentials.yaml"), "ok")
        Files.createDirectories(
            home.resolve("profiles").resolve("dsh-tui").resolve("node_modules")
                .resolve("@deepseek-harness-tui").resolve("dsh-tui"),
        )
        Files.writeString(
            home.resolve("cordis.patch.yml"),
            """
            - id: ui-skin-x
              disabled: true
            - insert:
                - id: ui-skin-blue-fantasy
                  name: '@linxin666/dsh-client-ui-skin-blue-fantasy'
            """.trimIndent(),
        )
        val report = DshEnvironmentCheck.check(pathEnv = bin.toString(), dshHome = home, isWindows = true)
        assertFalse(report.ready)
        assertEquals(listOf("@linxin666/dsh-client-ui-skin-blue-fantasy"), report.missingInsertPackages)
        assertTrue(report.problems.any { it.id == "inserts" && it.fixable })
    }

    @Test
    fun `disabled entries without name are ignored by insert parsing`() {
        val home = tempHome()
        Files.writeString(
            home.resolve("cordis.patch.yml"),
            """
            - id: ui-skin-dragon-heir
              disabled: true
            - insert:
                - id: other
                  name: some-pkg@^1.0.0
            """.trimIndent(),
        )
        assertEquals(listOf("some-pkg@^1.0.0"), DshEnvironmentCheck.readInsertPackageNames(home))
    }

    @Test
    fun `version specs are stripped for directory lookup`() {
        assertEquals("some-pkg", DshEnvironmentCheck.stripVersionSpec("some-pkg@^1.0.0"))
        assertEquals("@scope/pkg", DshEnvironmentCheck.stripVersionSpec("@scope/pkg@2.1.3"))
        assertEquals("@scope/pkg", DshEnvironmentCheck.stripVersionSpec("@scope/pkg"))
    }

    @Test
    fun `setup steps follow dependency order`() {
        val report = DshEnvironmentCheck.Report(
            nodePath = "node", dshPath = null, pnpmPath = null, tuiLauncherPath = null,
            profileInstalled = false,
            missingInsertPackages = listOf("@scope/skin"),
            hasCredentials = true,
            problems = emptyList(),
        )
        val steps = DshSetupRunner.buildSteps(report)
        assertEquals(
            listOf("安装 pnpm", "安装 dsh CLI", "安装 dsh-tui profile", "补装插入包 @scope/skin"),
            steps.map { it.title },
        )
    }

    @Test
    fun `pnpm allowBuilds placeholders are filled with false`() {
        val raw = """
            packages:
              - .

            nodeLinker: hoisted
            allowBuilds:
              '@google/genai': set this to true or false
              protobufjs: set this to true or false
        """.trimIndent()
        val fixed = DshSetupRunner.sanitizeAllowBuildsPlaceholders(raw)
        assertFalse(fixed.contains("set this to true or false"))
        assertTrue(fixed.contains("'@google/genai': false"))
        assertTrue(fixed.contains("protobufjs: false"))
        // 无占位的内容保持原样
        assertEquals("plain: text", DshSetupRunner.sanitizeAllowBuildsPlaceholders("plain: text"))
    }
}
