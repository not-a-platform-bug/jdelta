package jdelta.report

import jdelta.core.Confidence
import jdelta.core.DeltaKind
import jdelta.core.ImpactLevel
import jdelta.core.ModuleId
import jdelta.core.NodeId
import jdelta.core.SemanticDelta

/**
 * 사람용 report (ARCHITECTURE.md §11.1).
 * 모든 결론 옆에 confidence를 쓰고, fallback은 summary에서 숨기지 않는다.
 */
internal object MarkdownReport {
    private const val COLLAPSE_THRESHOLD = 20

    /** 이 비율을 넘으면 impacted-first 효과가 낮다고 알린다 (ARCHITECTURE.md §6.6). */
    private const val CONSERVATIVE_WARNING_PERCENT = 70

    fun render(input: ReportInput): String = buildString {
        val deltas = input.deltas
        val bySubject = deltas.groupBy { it.subject }
        val modules = deltas.mapNotNull { Display.module(it.subject) }.toSortedSet(compareBy { it.path })
        val unknowns = deltas.filter { it.confidence == Confidence.CONSERVATIVE_UNKNOWN }

        appendLine("# jdelta report: ${revision(input.base)} .. ${revision(input.head)}")
        appendLine()
        appendLine("## Summary")
        appendLine()
        if (deltas.isEmpty()) {
            appendLine("No semantic changes.")
        } else {
            appendLine("- ${deltas.size} semantic change(s) on ${bySubject.size} symbol(s) in ${modules.size} module(s)")
            appendLine("- Downstream compile impact: ${count(deltas) { it.compileImpact == ImpactLevel.DOWNSTREAM }}, binary: ${count(deltas) { it.binaryImpact == ImpactLevel.DOWNSTREAM }}")
            appendLine("- Conservative fallbacks: ${unknowns.size}")
        }
        input.notes.forEach { appendLine("- Note: $it") }
        appendLine()

        if (deltas.isNotEmpty()) {
            semanticDeltas(bySubject)
            abiImpact(deltas)
        }
        testImpact(input)
        buildImpact(deltas, input)
        fallbacks(unknowns)
        confidenceSummary(deltas)
        nextActions(input)
    }.trimEnd() + "\n"

    private fun StringBuilder.semanticDeltas(bySubject: Map<NodeId, List<SemanticDelta>>) {
        appendLine("## Semantic Deltas")
        appendLine()
        bySubject.entries.forEachIndexed { index, (subject, list) ->
            appendLine("### ${index + 1}. `${Display.name(subject)}` — ${list.joinToString(", ") { it.kind.name }}")
            appendLine()
            val combined = Combined(list)
            appendLine("| layer | impact |")
            appendLine("|---|---|")
            appendLine("| compile ABI | ${level(combined.compile)} |")
            appendLine("| binary ABI | ${level(combined.binary)} |")
            appendLine("| reflection ABI | ${level(combined.reflection)} |")
            appendLine("| framework | ${level(combined.framework)} |")
            appendLine("| tests | ${level(combined.test)} |")
            appendLine()
            appendLine("- Confidence: ${combined.confidence}")
            list.flatMap { it.reasons }.distinct().forEach { reason ->
                append("- Why (`${reason.code}`): ${reason.message}")
                if (reason.evidence.isNotEmpty()) append(" — evidence: ${reason.evidence.joinToString { "`${Display.name(it)}`" }}")
                appendLine()
            }
            appendLine("- ID: `${subject.canonical}`")
            appendLine()
        }
    }

    private fun StringBuilder.abiImpact(deltas: List<SemanticDelta>) {
        appendLine("## ABI Impact")
        appendLine()
        appendLine("| layer | NONE | LOCAL | RUNTIME | DOWNSTREAM | UNKNOWN |")
        appendLine("|---|---|---|---|---|---|")
        val layers = listOf<Pair<String, (SemanticDelta) -> ImpactLevel>>(
            "compile" to { it.compileImpact },
            "binary" to { it.binaryImpact },
            "reflection" to { it.reflectionImpact },
            "framework" to { it.frameworkImpact },
            "tests" to { it.testImpact },
        )
        for ((name, selector) in layers) {
            val counts = deltas.groupingBy(selector).eachCount()
            appendLine("| $name | ${counts[ImpactLevel.NONE] ?: 0} | ${counts[ImpactLevel.LOCAL] ?: 0} | ${counts[ImpactLevel.RUNTIME] ?: 0} | ${counts[ImpactLevel.DOWNSTREAM] ?: 0} | ${counts[ImpactLevel.UNKNOWN] ?: 0} |")
        }
        appendLine()
    }

    private fun StringBuilder.testImpact(input: ReportInput) {
        appendLine("## Test Impact")
        appendLine()
        val impact = input.impact
        if (impact == null) {
            appendLine("No test trace available. Impacted tests were not computed; run the full suite.")
            appendLine("Record a trace with `jdelta record -- ./gradlew test` to enable impacted-first ordering.")
            appendLine()
            return
        }
        val ranked = impact.plan.items.filterIsInstance<jdelta.core.WorkItem.RunTestsFirst>().flatMap { it.tests }
        appendLine("${ranked.size} impacted test(s), run first; the full suite runs afterwards.")
        appendLine()
        if (ranked.isEmpty()) return
        val conservative = ranked.count { it.confidence == Confidence.CONSERVATIVE_UNKNOWN }
        val share = conservative * 100 / ranked.size
        appendLine("Conservative share: $share% ($conservative of ${ranked.size} included only because their impact could not be bounded).")
        if (share > CONSERVATIVE_WARNING_PERCENT) {
            val causes = ranked.filter { it.confidence == Confidence.CONSERVATIVE_UNKNOWN }.flatMap { it.reasons }.map { it.code }.distinct()
            appendLine()
            appendLine("> **Impacted-first is of limited use for this change**: most tests are included conservatively. Causes: ${causes.joinToString { "`$it`" }}.")
        }
        appendLine()
        val collapse = ranked.size > COLLAPSE_THRESHOLD
        if (collapse) appendLine("<details><summary>${ranked.size} tests</summary>\n")
        appendLine("| # | test | confidence | via |")
        appendLine("|---|---|---|---|")
        ranked.forEach { t ->
            val via = if (t.path.size > 1) t.path.joinToString(" → ") { "`${Display.name(it)}`" } else ""
            val why = t.reasons.firstOrNull()?.code?.name ?: ""
            appendLine("| ${t.rank} | `${Display.name(t.test)}` | ${t.confidence} | `$why` $via |")
        }
        if (collapse) appendLine("\n</details>")
        appendLine()
    }

    private fun StringBuilder.buildImpact(deltas: List<SemanticDelta>, input: ReportInput) {
        appendLine("## Build Impact")
        appendLine()
        val recompile = input.impact?.plan?.items?.filterIsInstance<jdelta.core.WorkItem.RecompileModule>()
        if (recompile != null) {
            if (recompile.isEmpty()) appendLine("No compile-relevant changes. Dependent modules do not need recompilation.")
            recompile.forEach { r ->
                val scope = if (r.scope == jdelta.core.WorkItem.Scope.DOWNSTREAM) "module and its compile dependents" else "module only"
                appendLine("- `${r.module.path}`: recompile $scope (${r.confidence}). ${r.reasons.joinToString { it.message }}")
            }
            appendLine()
            return
        }
        val downstream = deltas.filter { it.compileImpact == ImpactLevel.DOWNSTREAM || it.binaryImpact == ImpactLevel.DOWNSTREAM }
            .mapNotNull { Display.module(it.subject) }.toSortedSet(compareBy<ModuleId> { it.path })
        val local = deltas.filter { it.compileImpact != ImpactLevel.NONE }
            .mapNotNull { Display.module(it.subject) }.toSortedSet(compareBy<ModuleId> { it.path })
        if (downstream.isEmpty() && local.isEmpty()) {
            appendLine("No compile-relevant changes. Dependent modules do not need recompilation.")
        } else {
            if (downstream.isNotEmpty()) appendLine("- Recompile modules depending on: ${downstream.joinToString { "`${it.path}`" }} (recommendation; the build tool decides)")
            (local - downstream).takeIf { it.isNotEmpty() }?.let { appendLine("- Local recompile only: ${it.joinToString { m -> "`${m.path}`" }}") }
            val constants = deltas.filter { it.kind == DeltaKind.CONSTANT_VALUE_CHANGED && it.compileImpact == ImpactLevel.DOWNSTREAM }
            if (constants.isNotEmpty()) {
                appendLine("- Compile-time constants changed (${constants.joinToString { "`${Display.name(it.subject)}`" }}): callers keep inlined old values until recompiled")
            }
        }
        appendLine()
    }

    private fun StringBuilder.fallbacks(unknowns: List<SemanticDelta>) {
        appendLine("## Conservative Fallbacks")
        appendLine()
        if (unknowns.isEmpty()) {
            appendLine("None.")
        } else {
            unknowns.forEach { d ->
                appendLine("- `${Display.name(d.subject)}` ${d.kind}: ${d.reasons.joinToString("; ") { "`${it.code}` ${it.message}" }}")
            }
        }
        appendLine()
    }

    private fun StringBuilder.confidenceSummary(deltas: List<SemanticDelta>) {
        appendLine("## Confidence Summary")
        appendLine()
        appendLine("| confidence | deltas |")
        appendLine("|---|---|")
        val counts = deltas.groupingBy { it.confidence }.eachCount()
        Confidence.entries.forEach { appendLine("| $it | ${counts[it] ?: 0} |") }
        appendLine()
    }

    private fun StringBuilder.nextActions(input: ReportInput) {
        appendLine("## Next Actions")
        appendLine()
        val deltas = input.deltas
        val actions = mutableListOf<String>()
        if (deltas.any { it.compileImpact == ImpactLevel.DOWNSTREAM || it.binaryImpact == ImpactLevel.DOWNSTREAM }) {
            actions += "Recompile and test dependent modules."
        }
        if (deltas.any { it.frameworkImpact != ImpactLevel.NONE }) {
            actions += "Run framework-level tests (serialization, persistence, web, context) for classes with changed runtime metadata."
        }
        if (deltas.any { it.confidence == Confidence.CONSERVATIVE_UNKNOWN }) {
            actions += "Review the conservative fallbacks above; jdelta could not bound their impact."
        }
        if (input.impact == null && deltas.any { it.testImpact != ImpactLevel.NONE }) {
            actions += "Run the full test suite (no trace available for impacted-first ordering)."
        }
        if (actions.isEmpty()) actions += "Nothing to do."
        actions.forEach { appendLine("- $it") }
    }

    private class Combined(list: List<SemanticDelta>) {
        val compile = ImpactLevel.maxOf(list.map { it.compileImpact })
        val binary = ImpactLevel.maxOf(list.map { it.binaryImpact })
        val reflection = ImpactLevel.maxOf(list.map { it.reflectionImpact })
        val framework = ImpactLevel.maxOf(list.map { it.frameworkImpact })
        val test = ImpactLevel.maxOf(list.map { it.testImpact })
        val confidence = Confidence.weakestOf(list.map { it.confidence })
    }

    private fun level(level: ImpactLevel) = when (level) {
        ImpactLevel.NONE -> "unchanged"
        ImpactLevel.LOCAL -> "local (same module)"
        ImpactLevel.DOWNSTREAM -> "**downstream**"
        ImpactLevel.RUNTIME -> "runtime only"
        ImpactLevel.UNKNOWN -> "**unknown**"
    }

    private fun count(deltas: List<SemanticDelta>, predicate: (SemanticDelta) -> Boolean) = deltas.count(predicate)

    private fun revision(r: RevisionInfo) = buildString {
        append(r.label)
        val details = listOfNotNull(r.sha?.take(7), r.resolution, "dirty".takeIf { r.dirty })
        if (details.isNotEmpty()) append(" (").append(details.joinToString(", ")).append(")")
    }
}
