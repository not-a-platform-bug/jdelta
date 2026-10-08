package jdelta.cli

import jdelta.core.ModuleId
import jdelta.core.SourceSetId
import org.assertj.core.api.Assertions.assertThat
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test

class DiffCommandTest {
    private fun compile(code: String): Path {
        val root = Files.createTempDirectory("jdelta-cli")
        val src = root.resolve("src/com/acme/Timeouts.java").also { it.parent.createDirectories(); it.writeText(code) }
        val out = root.resolve("classes").createDirectories()
        val ok = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-d", out.toString(), src.toString())
        check(ok == 0)
        return out
    }

    private val base = compile("package com.acme; public class Timeouts { public static final long DEFAULT_MS = 1000L; }")
    private val head = compile("package com.acme; public class Timeouts { public static final long DEFAULT_MS = 3000L; }")

    @Test
    fun `diff prints a markdown report`() {
        val out = StringWriter()
        val code = run(arrayOf("diff", "--base-classes", "$base", "--head-classes", "$head", "--base-label", "main"), out)
        assertThat(code).isEqualTo(ExitCode.OK)
        assertThat(out.toString()).contains("# jdelta report: main .. head")
        assertThat(out.toString()).contains("CONSTANT_VALUE_CHANGED")
    }

    @Test
    fun `diff writes json to a file`() {
        val target = Files.createTempDirectory("jdelta-out").resolve("nested/report.json")
        val code = run(arrayOf("diff", "--base-classes", ":core=$base", "--head-classes", ":core=$head", "--format", "json", "--out", "$target"), StringWriter(), StringWriter())
        assertThat(code).isEqualTo(ExitCode.OK)
        assertThat(target.readText()).contains("\":core|com/acme/Timeouts.DEFAULT_MS:J\"")
    }

    @Test
    fun `missing directory is a usage error`() {
        val err = StringWriter()
        val code = run(arrayOf("diff", "--base-classes", "/does/not/exist", "--head-classes", "$head"), StringWriter(), err)
        assertThat(code).isEqualTo(ExitCode.USAGE)
        assertThat(err.toString()).contains("does not exist")
    }

    @Test
    fun `directory mode needs both sides`() {
        assertThat(run(arrayOf("diff", "--base-classes", "$base"), StringWriter(), StringWriter())).isEqualTo(ExitCode.USAGE)
    }

    @Test
    fun `directory specs`() {
        assertThat(DirSpec.parse("build/classes")).isEqualTo(DirSpec(SourceSetId(ModuleId(":"), "main"), Path("build/classes")))
        assertThat(DirSpec.parse(":core=out")).isEqualTo(DirSpec(SourceSetId(ModuleId(":core"), "main"), Path("out")))
        assertThat(DirSpec.parse(":core@test=out")).isEqualTo(DirSpec(SourceSetId(ModuleId(":core"), "test"), Path("out")))
    }
}
