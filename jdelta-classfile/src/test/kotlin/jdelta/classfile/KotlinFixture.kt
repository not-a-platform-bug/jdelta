package jdelta.classfile

import jdelta.core.DeltaKind
import jdelta.core.SemanticDelta
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** before/after Kotlin source를 실제 kotlinc로 compile하고 diff한다 (ARCHITECTURE.md §13.1). */
object KotlinFixture {
    private val stdlib: String = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI()).path

    fun compile(sources: Map<String, String>): Path {
        val root = Files.createTempDirectory("jdelta-kt-fixture")
        val src = root.resolve("src")
        val out = root.resolve("classes").createDirectories()
        val files = sources.map { (path, code) ->
            src.resolve(path).also { it.parent.createDirectories(); it.writeText(code.trimIndent()) }
        }
        val messages = ByteArrayOutputStream()
        val args = listOf(
            "-d", out.toString(),
            "-classpath", stdlib,
            "-no-stdlib", "-no-reflect",
            "-jvm-target", "17",
            "-module-name", "app",
            "-Xsuppress-version-warnings",
        ) + files.map { it.toString() }
        val code = K2JVMCompiler().exec(PrintStream(messages), *args.toTypedArray())
        check(code == ExitCode.OK) { "kotlin fixture compile failed:\n$messages" }
        return out
    }

    fun snapshot(sources: Map<String, String>): ProjectSnapshot = JavaFixture.snapshotOf(compile(sources))

    fun diff(before: Map<String, String>, after: Map<String, String>): List<SemanticDelta> {
        val deltas = DeltaClassifier().diff(snapshot(before), snapshot(after))
        assertThat(deltas.filter { it.kind == DeltaKind.UNKNOWN }).describedAs("unexplained deltas").isEmpty()
        return deltas
    }

    fun diff(path: String, before: String, after: String): List<SemanticDelta> = diff(mapOf(path to before), mapOf(path to after))
}
