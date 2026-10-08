package jdelta.core

/** semantic delta에서 graph를 따라 안전한 work plan을 만드는 계약. 구현은 jdelta-impact. */
public interface ImpactSolver {
    public fun solve(input: ImpactInput): ImpactResult
}

public data class ImpactInput(
    public val graph: SemanticGraph,
    public val deltas: List<SemanticDelta>,
    public val project: ProjectModel,
    public val policy: ImpactPolicy = ImpactPolicy(),
)

public data class ImpactPolicy(
    public val mode: Mode = Mode.IMPACTED_FIRST,
    public val maxStaticDepth: Int = 1,
) {
    /** v0.1에는 test skip mode가 없다 (ARCHITECTURE.md §6.7). */
    public enum class Mode { REPORT_ONLY, IMPACTED_FIRST }
}

public data class ImpactedNode(
    public val node: NodeId,
    public val confidence: Confidence,
    public val path: List<NodeId>,
    public val reasons: List<Reason>,
)

public data class ImpactResult(
    public val impacted: List<ImpactedNode>,
    public val plan: WorkPlan,
)
