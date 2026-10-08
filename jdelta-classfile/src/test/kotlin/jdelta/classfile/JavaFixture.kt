package jdelta.classfile

import jdelta.core.DeltaKind
import jdelta.core.ModuleId
import jdelta.core.SemanticDelta
import jdelta.core.SourceSetId
import jdelta.core.SourceSetModel
import org.assertj.core.api.Assertions.assertThat
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** before/after Java source를 실제 javac로 compile하고 diff한다 (ARCHITECTURE.md §13.1). */
object JavaFixture {
    val MODULE = ModuleId(":app")
    val MAIN = SourceSetId(MODULE, "main")

    fun compile(sources: Map<String, String>, vararg options: String): Path {
        val root = Files.createTempDirectory("jdelta-fixture")
        val src = root.resolve("src")
        val out = root.resolve("classes").createDirectories()
        val files = sources.map { (path, code) ->
            src.resolve(path).also { it.parent.createDirectories(); it.writeText(code.trimIndent()) }
        }
        val compiler = ToolProvider.getSystemJavaCompiler()
        val errors = StringWriter()
        compiler.getStandardFileManager(null, null, Charsets.UTF_8).use { fm ->
            val units = fm.getJavaFileObjectsFromPaths(files)
            val args = listOf("--release", "17", "-d", out.toString(), "-proc:none") + options.ifEmpty { arrayOf("-g") }
            val ok = compiler.getTask(errors, fm, null, args, null, units).call()
            check(ok) { "fixture compile failed:\n$errors" }
        }
        return out
    }

    fun snapshot(sources: Map<String, String>, vararg options: String): ProjectSnapshot =
        snapshotOf(compile(sources, *options))

    fun snapshotOf(classesDir: Path): ProjectSnapshot =
        ProjectSnapshot(listOf(SnapshotCollector().collect(SourceSetModel(MAIN, classesDirs = listOf(classesDir)))))

    fun diff(before: Map<String, String>, after: Map<String, String>): List<SemanticDelta> {
        val deltas = DeltaClassifier().diff(snapshot(before), snapshot(after))
        // 분류기가 설명하지 못한 변경은 fixture에서 0건이어야 한다 (§13.1)
        assertThat(deltas.filter { it.kind == DeltaKind.UNKNOWN }).describedAs("unexplained deltas").isEmpty()
        return deltas
    }

    fun diff(path: String, before: String, after: String): List<SemanticDelta> = diff(mapOf(path to before), mapOf(path to after))
}
