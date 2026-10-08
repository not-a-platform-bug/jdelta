package jdelta.cli

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import jdelta.core.ImpactResult
import jdelta.core.SemanticDelta
import jdelta.engine.DiffResult
import jdelta.engine.JDeltaEngine
import jdelta.engine.SourceChanges
import jdelta.engine.Workspace
import jdelta.trace.TraceStore
import kotlin.io.path.Path

/** git revision 비교 결과와 test 영향. */
internal data class Analysis(
    val resolved: ResolvedDiff,
    val result: DiffResult,
    val impact: ImpactResult?,
    val traces: TraceStore,
    val notes: List<String>,
) {
    val deltas: List<SemanticDelta> get() = result.deltas
}

/**
 * `diff`와 `impacted-tests`가 공유하는 revision 해석 (ARCHITECTURE.md §4.4, §10).
 */
internal abstract class RevisionCommand(name: String) : CoreCliktCommand(name = name) {
    protected val range by argument("RANGE", help = "revision range such as main..HEAD (head must be HEAD or a stored snapshot)").optional()
    protected val projectDir by option("--project-dir", metavar = "DIR", help = "directory inside the git repository (default: current directory)").default(".")
    private val exactBase by option("--exact-base", help = "use the base revision as is instead of the merge-base").flag()
    private val baselineDir by option("--baseline-dir", metavar = "DIR", help = "baseline snapshot made by 'jdelta snapshot --out'")
    private val buildBaseline by option("--build-baseline", help = "build the baseline in a temporary git worktree when no snapshot exists").flag()
    private val build by option("--build", help = "build the current project before analysis").flag()
    private val buildCommand by option("--build-command", metavar = "CMD", help = "command for --build/--build-baseline (default: ./gradlew -q classes testClasses)")
    private val skipStaleness by option("--skip-staleness-check", help = "analyze even if build output is older than changed sources").flag()
    private val modelSource by modelOption()
    private val modelFile by option("--model-file", metavar = "FILE", help = "use an exported project model (.jdelta/model/project.json) instead of running Gradle")

    /**
     * delta와 test 영향을 계산한다. [requireImpact]가 false면 trace store가 비었을 때 impact를 계산하지 않는다
     * (report는 "trace 없음"을 숨기지 않고 적는다).
     */
    protected fun analyze(requireImpact: Boolean): Analysis {
        val workflow = GitWorkflow(Path(projectDir), modelSource, modelFile?.let { Path(it).toAbsolutePath() }) { echo(it, err = true) }
        if (build) workflow.build(buildCommand)
        val options = BaselineOptions(exactBase, baselineDir?.let { Path(it) }, buildBaseline, buildCommand)
        val resolved = workflow.resolve(RevisionRange.parse(range), options, checkStaleness = !skipStaleness)
        val engine = JDeltaEngine()
        val classDiff = engine.diff(resolved.base, resolved.head, resolved.headModel)
        val sourceDeltas = SourceChanges(workflow.root, resolved.headModel, resolved.head).deltas(resolved.changes, classDiff.deltas)
        val result = classDiff.copy(deltas = classDiff.deltas + sourceDeltas)

        val traces = TraceStore.load(Workspace(workflow.root).traceStore)
        val notes = ArrayList(resolved.notes)
        notes += "compared ${result.baseClassCount} -> ${result.headClassCount} classes"
        workflow.modelNote.takeIf { it.isNotEmpty() }?.let { notes += it }
        val impact = if (traces.isEmpty && !requireImpact) {
            null
        } else {
            if (traces.isEmpty) notes += "no test trace recorded yet; every test is included conservatively (run 'jdelta record -- ./gradlew test')"
            else notes += "test trace: ${traces.tests.size} observation(s)"
            engine.impact(result, resolved.base, resolved.head, resolved.headModel, traces)
        }
        return Analysis(resolved, result, impact, traces, notes)
    }
}
