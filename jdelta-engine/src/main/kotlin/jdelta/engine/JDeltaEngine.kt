package jdelta.engine

import jdelta.classfile.DeltaClassifier
import jdelta.classfile.ProjectSnapshot
import jdelta.classfile.SnapshotCollector
import jdelta.core.ImpactInput
import jdelta.core.ImpactPolicy
import jdelta.core.ImpactResult
import jdelta.core.ImpactSolver
import jdelta.core.ProjectModel
import jdelta.impact.RuleBasedImpactSolver
import jdelta.trace.TraceStore
import jdelta.core.SemanticDelta
import jdelta.report.ReportFormat
import jdelta.report.ReportInput
import jdelta.report.Reports
import jdelta.report.RevisionInfo

/**
 * CLI와 Gradle plugin이 공유하는 진입점 (ARCHITECTURE.md §7).
 * engine은 git도 Gradle도 모른다. revision, project model, 변경 파일 목록은 호출자가 만든다.
 */
public class JDeltaEngine(
    private val collector: SnapshotCollector = SnapshotCollector(),
    private val classifier: DeltaClassifier = DeltaClassifier(),
    private val solver: ImpactSolver = RuleBasedImpactSolver(),
) {
    public fun snapshot(model: ProjectModel): ProjectSnapshot = collector.collect(model)

    /** 저장된 snapshot을 현재 reader로 다시 해석한다. resource는 저장된 hash를 쓴다. */
    public fun snapshot(stored: StoredSnapshot): ProjectSnapshot {
        val parsed = collector.collect(stored.model)
        return ProjectSnapshot(parsed.sourceSets.map { it.copy(resources = stored.resources[it.id].orEmpty()) })
    }

    /**
     * [model]이 있으면 annotation processor가 있는 module을 알 수 있어 processor-sensitive 판단(§6.4)까지 한다.
     */
    public fun diff(base: ProjectSnapshot, head: ProjectSnapshot, model: ProjectModel? = null): DiffResult {
        val facts = FrameworkFacts.collect(base, head, model)
        val deltas = FrameworkEnricher.enrich(classifier.diff(base, head), facts)
        return DiffResult(deltas, base.classCount, head.classCount)
    }

    /**
     * delta에서 impacted test와 work plan을 만든다 (ARCHITECTURE.md §6).
     * trace가 비어 있어도 동작한다. 그때는 모든 test가 `NO_TRACE_FOR_TEST`로 포함된다.
     */
    public fun impact(
        result: DiffResult,
        base: ProjectSnapshot,
        head: ProjectSnapshot,
        model: ProjectModel,
        traces: TraceStore,
        policy: ImpactPolicy = ImpactPolicy(),
    ): ImpactResult {
        val graph = GraphFactory(base, head, model, traces, result.deltas, FrameworkFacts.collect(base, head, model)).build()
        return solver.solve(ImpactInput(graph, result.deltas, model, policy))
    }

    public fun report(
        result: DiffResult,
        base: RevisionInfo,
        head: RevisionInfo,
        format: ReportFormat,
        notes: List<String> = emptyList(),
        impact: ImpactResult? = null,
    ): String = Reports.render(ReportInput(base, head, result.deltas, impact = impact, notes = notes), format)
}

public data class DiffResult(
    public val deltas: List<SemanticDelta>,
    public val baseClassCount: Int,
    public val headClassCount: Int,
)
