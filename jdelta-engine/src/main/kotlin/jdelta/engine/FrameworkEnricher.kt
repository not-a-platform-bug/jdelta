package jdelta.engine

import jdelta.core.ClassId
import jdelta.core.FieldId
import jdelta.core.ImpactLevel
import jdelta.core.MethodId
import jdelta.core.NodeId
import jdelta.core.Reason
import jdelta.core.ReasonCode
import jdelta.core.SemanticDelta
import jdelta.core.DeltaKind
import jdelta.impact.FrameworkMatch

/**
 * 분류된 delta에 framework profile 판단을 더한다 (ARCHITECTURE.md §6.4).
 * framework impact를 RUNTIME으로 올리고 `FRAMEWORK_PROFILE_MATCH`/`PROCESSOR_SENSITIVE_ANNOTATION` reason을 남긴다.
 * test를 어디까지 넓힐지는 impact solver가 같은 profile 표로 정한다.
 */
internal object FrameworkEnricher {
    fun enrich(deltas: List<SemanticDelta>, facts: FrameworkFacts): List<SemanticDelta> = deltas.map { enrich(it, facts) }

    private fun enrich(delta: SemanticDelta, facts: FrameworkFacts): SemanticDelta {
        val owner = ownerClass(delta.subject) ?: return delta
        val annotations = facts.of(delta.subject) + facts.of(owner)
        val reasons = ArrayList<Reason>()

        for (profile in FrameworkMatch.matches(delta.kind, annotations)) {
            val matched = profile.annotations.filter { it in annotations }.joinToString { FrameworkMatch.simpleName(it) }
            reasons += Reason(ReasonCode.FRAMEWORK_PROFILE_MATCH, "${profile.framework}: $matched; the framework reads this through reflection or proxies")
        }
        if (delta.kind == DeltaKind.ENUM_CONSTANT_ORDER_CHANGED) {
            facts.ordinalEnums[owner.internalName]?.let { fields ->
                reasons += Reason(ReasonCode.FRAMEWORK_PROFILE_MATCH, "jpa: persisted by ordinal (@Enumerated) in ${fields.joinToString { it.owner.binaryName + "." + it.name }}; stored rows change meaning", fields.toList())
            }
        }
        if (FrameworkMatch.changesProcessorInput(delta.kind)) {
            val processors = facts.processorInputs[owner].orEmpty().filter { it.module in facts.processorModules }
            if (processors.isNotEmpty()) {
                val modules = processors.map { it.module.path }.distinct().sorted()
                reasons += Reason(
                    ReasonCode.PROCESSOR_SENSITIVE_ANNOTATION,
                    "input of annotation-processed ${processors.joinToString { it.binaryName }}; generated sources in ${modules.joinToString()} may change; included conservatively until ap-trace exists",
                    processors,
                )
            }
        }
        if (reasons.isEmpty()) return delta
        return delta.copy(frameworkImpact = delta.frameworkImpact max ImpactLevel.RUNTIME, reasons = delta.reasons + reasons)
    }

    private fun ownerClass(id: NodeId): ClassId? = when (id) {
        is ClassId -> id
        is MethodId -> id.owner
        is FieldId -> id.owner
        else -> null
    }
}
