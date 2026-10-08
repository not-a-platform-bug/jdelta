package jdelta.core

public data class WorkPlan(
    public val items: List<WorkItem>,
    public val fallbacks: List<Fallback>,
)

public sealed interface WorkItem {
    public val confidence: Confidence
    public val reasons: List<Reason>

    public enum class Scope { LOCAL, DOWNSTREAM }

    /** v0.1에서는 권고일 뿐이다. 실제 recompile 여부는 build tool이 정한다. */
    public data class RecompileModule(
        public val module: ModuleId,
        public val scope: Scope,
        override val confidence: Confidence,
        override val reasons: List<Reason>,
    ) : WorkItem

    public data class RunTestsFirst(
        public val tests: List<RankedTest>,
        override val confidence: Confidence,
        override val reasons: List<Reason>,
    ) : WorkItem

    /** [modules]가 null이면 전체 suite. */
    public data class RunFullSuite(
        public val modules: Set<ModuleId>?,
        override val confidence: Confidence,
        override val reasons: List<Reason>,
    ) : WorkItem

    public data class ReviewManually(
        public val subject: NodeId,
        override val confidence: Confidence,
        override val reasons: List<Reason>,
    ) : WorkItem
}

public data class RankedTest(
    public val test: TestId,
    public val rank: Int,
    public val confidence: Confidence,
    public val path: List<NodeId>,
    public val reasons: List<Reason>,
)

public data class Fallback(
    public val trigger: NodeId,
    public val code: ReasonCode,
    public val action: WorkItem,
)
