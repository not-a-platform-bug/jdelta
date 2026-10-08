package jdelta.report

import jdelta.core.ImpactResult
import jdelta.core.SemanticDelta

public enum class ReportFormat { MARKDOWN, JSON }

/** report가 비교한 한쪽 revision. [sha]는 git 연동 전에는 null일 수 있다. */
public data class RevisionInfo(
    public val label: String,
    public val sha: String? = null,
    public val resolution: String? = null,
    public val dirty: Boolean = false,
)

public data class ReportInput(
    public val base: RevisionInfo,
    public val head: RevisionInfo,
    public val deltas: List<SemanticDelta>,
    /** test trace가 없으면 null. report는 그 사실을 숨기지 않고 적는다. */
    public val impact: ImpactResult? = null,
    public val notes: List<String> = emptyList(),
)

public object Reports {
    public fun render(input: ReportInput, format: ReportFormat): String = when (format) {
        ReportFormat.MARKDOWN -> MarkdownReport.render(input)
        ReportFormat.JSON -> JsonReport.render(input)
    }
}
