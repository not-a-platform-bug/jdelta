package jdelta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import jdelta.core.ModuleId
import jdelta.core.ModuleModel
import jdelta.core.ProjectModel
import jdelta.core.SourceSetId
import jdelta.core.SourceSetModel
import jdelta.engine.JDeltaEngine
import jdelta.report.ReportFormat
import jdelta.report.RevisionInfo
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

/**
 * `jdelta diff [<base>..<head>]`: 두 revision의 build output을 비교해 semantic delta report를 만든다.
 *
 * - git mode(기본): base는 `git merge-base`, head는 현재 build output(working tree). baseline은 snapshot 저장소에서 찾는다 (§4.4).
 * - directory mode: `--base-classes`/`--head-classes`로 두 output directory를 직접 비교한다. git이 필요 없다.
 */
internal class DiffCommand : RevisionCommand(name = "diff") {
    override fun help(context: Context): String = """
        Report semantic deltas between two revisions.

        By default compares the merge-base of the base revision (origin/HEAD, main or master) with the
        current build output. The baseline comes from .jdelta/snapshots, --baseline-dir or --build-baseline.
        When a test trace exists (jdelta record), the report also ranks impacted tests.

        Directory mode compares two output directories directly. Directory specs take the form
        [:module[@sourceSet]=]path, e.g. :core@main=core/build/classes/java/main. Without a module the
        spec belongs to ':' and the 'main' source set.
    """.trimIndent()

    private val baseClasses by option("--base-classes", metavar = "SPEC", help = "directory mode: baseline classes directory (repeatable)").multiple()
    private val headClasses by option("--head-classes", metavar = "SPEC", help = "directory mode: current classes directory (repeatable)").multiple()
    private val baseResources by option("--base-resources", metavar = "SPEC", help = "directory mode: baseline resources directory (repeatable)").multiple()
    private val headResources by option("--head-resources", metavar = "SPEC", help = "directory mode: current resources directory (repeatable)").multiple()
    private val baseLabel by option("--base-label", help = "directory mode: label for the baseline in the report").default("base")
    private val headLabel by option("--head-label", help = "directory mode: label for the current side in the report").default("head")

    private val format by option("--format", help = "report format").choice("markdown" to ReportFormat.MARKDOWN, "json" to ReportFormat.JSON).default(ReportFormat.MARKDOWN)
    private val outFile by option("--out", metavar = "FILE", help = "write the report to FILE instead of stdout")

    override fun run() {
        val directoryMode = baseClasses.isNotEmpty() || headClasses.isNotEmpty()
        val report = if (directoryMode) directoryDiff() else gitDiff()
        val target = outFile
        if (target == null) {
            echo(report, trailingNewline = false)
        } else {
            val path = Path(target)
            path.toAbsolutePath().parent?.createDirectories()
            path.writeText(report)
            echo("report written to ${path.absolute()}", err = true)
        }
    }

    private fun gitDiff(): String {
        val analysis = analyze(requireImpact = false)
        return JDeltaEngine().report(analysis.result, analysis.resolved.baseInfo, analysis.resolved.headInfo, format, analysis.notes, analysis.impact)
    }

    private fun directoryDiff(): String {
        if (baseClasses.isEmpty() || headClasses.isEmpty()) {
            throw UsageError("directory mode needs both --base-classes and --head-classes")
        }
        if (range != null) throw UsageError("a revision range cannot be combined with --base-classes/--head-classes")
        val engine = JDeltaEngine()
        val base = engine.snapshot(model(baseClasses, baseResources, "base"))
        val head = engine.snapshot(model(headClasses, headResources, "head"))
        val result = engine.diff(base, head)
        val notes = listOf("compared ${result.baseClassCount} -> ${result.headClassCount} classes from build output directories")
        return engine.report(result, RevisionInfo(baseLabel), RevisionInfo(headLabel), format, notes)
    }

    private fun model(classes: List<String>, resources: List<String>, side: String): ProjectModel {
        val classSpecs = classes.map { DirSpec.parse(it) }
        val resourceSpecs = resources.map { DirSpec.parse(it) }
        (classSpecs + resourceSpecs).forEach {
            if (!it.path.isDirectory()) throw JDeltaFailure("$side directory does not exist: ${it.path}", ExitCode.USAGE)
        }
        val sourceSets = (classSpecs.map { it.sourceSet } + resourceSpecs.map { it.sourceSet }).distinct()
        val modules = sourceSets.groupBy { it.module }.map { (module, ids) ->
            ModuleModel(
                id = module,
                projectDir = Path("."),
                sourceSets = ids.map { id ->
                    SourceSetModel(
                        id = id,
                        classesDirs = classSpecs.filter { it.sourceSet == id }.map { it.path },
                        resourcesDirs = resourceSpecs.filter { it.sourceSet == id }.map { it.path },
                    )
                },
            )
        }
        return ProjectModel(Path(".").absolute(), modules)
    }
}

internal data class DirSpec(val sourceSet: SourceSetId, val path: Path) {
    companion object {
        fun parse(spec: String): DirSpec {
            val eq = spec.indexOf('=')
            if (eq < 0 || !spec.startsWith(":")) return DirSpec(SourceSetId(ModuleId(":"), "main"), Path(spec))
            val target = spec.substring(0, eq)
            val at = target.indexOf('@')
            val module = ModuleId(if (at >= 0) target.substring(0, at) else target)
            val sourceSet = if (at >= 0) target.substring(at + 1) else "main"
            return DirSpec(SourceSetId(module, sourceSet), Path(spec.substring(eq + 1)))
        }
    }
}
