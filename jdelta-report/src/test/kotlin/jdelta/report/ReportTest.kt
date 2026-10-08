package jdelta.report

import jdelta.core.ClassId
import jdelta.core.Confidence
import jdelta.core.DeltaKind
import jdelta.core.ImpactLevel
import jdelta.core.ModuleId
import jdelta.core.Reason
import jdelta.core.ReasonCode
import jdelta.core.SemanticDelta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ReportTest {
    private val app = ModuleId(":app")
    private val calc = ClassId(app, "com/acme/PriceCalculator")
    private val timeouts = ClassId(app, "com/acme/Timeouts")

    private val deltas = listOf(
        SemanticDelta(
            calc.method("calculate", "(Lcom/acme/Order;[I)J"), DeltaKind.METHOD_BODY_CHANGED,
            testImpact = ImpactLevel.DOWNSTREAM, confidence = Confidence.EXACT,
            reasons = listOf(Reason(ReasonCode.BODY_ONLY, "body changed")),
        ),
        SemanticDelta(
            timeouts.field("DEFAULT_MS", "J"), DeltaKind.CONSTANT_VALUE_CHANGED,
            compileImpact = ImpactLevel.DOWNSTREAM, binaryImpact = ImpactLevel.RUNTIME, testImpact = ImpactLevel.DOWNSTREAM,
            confidence = Confidence.EXACT,
            reasons = listOf(Reason(ReasonCode.CONSTANT_INLINED_AT_CALLERS, "constant 1000 -> 3000")),
        ),
        SemanticDelta(
            ClassId(app, "com/acme/Broken"), DeltaKind.UNKNOWN,
            ImpactLevel.UNKNOWN, ImpactLevel.UNKNOWN, ImpactLevel.UNKNOWN, ImpactLevel.UNKNOWN, ImpactLevel.UNKNOWN,
            Confidence.CONSERVATIVE_UNKNOWN, listOf(Reason(ReasonCode.UNPARSEABLE_CLASSFILE, "classfile could not be parsed")),
        ),
    )
    private val input = ReportInput(RevisionInfo("main", "a1b2c3d4e5", "merge-base"), RevisionInfo("HEAD", dirty = true), deltas)

    @Test
    fun `markdown has every documented section and readable names`() {
        val md = Reports.render(input, ReportFormat.MARKDOWN)
        assertThat(md).startsWith("# jdelta report: main (a1b2c3d, merge-base) .. HEAD (dirty)")
        listOf("## Summary", "## Semantic Deltas", "## ABI Impact", "## Test Impact", "## Build Impact", "## Conservative Fallbacks", "## Confidence Summary", "## Next Actions")
            .forEach { assertThat(md).contains(it) }
        assertThat(md).contains("`com.acme.PriceCalculator#calculate(Order, int[])` — METHOD_BODY_CHANGED")
        assertThat(md).contains("`com.acme.Timeouts#DEFAULT_MS`")
        assertThat(md).contains("callers keep inlined old values until recompiled")
        assertThat(md).contains("No test trace available")
        assertThat(md).contains("`UNPARSEABLE_CLASSFILE` classfile could not be parsed")
        assertThat(md).contains("| CONSERVATIVE_UNKNOWN | 1 |")
    }

    @Test
    fun `empty diff says so`() {
        val md = Reports.render(input.copy(deltas = emptyList()), ReportFormat.MARKDOWN)
        assertThat(md).contains("No semantic changes.")
        assertThat(md).contains("Nothing to do.")
    }

    @Test
    fun `json keeps reasons, confidence and canonical ids`() {
        val json = Json.parseToJsonElement(Reports.render(input, ReportFormat.JSON)).jsonObject
        assertThat(json["schemaVersion"]!!.jsonPrimitive.content).isEqualTo("0.1")
        assertThat(json["stability"]!!.jsonPrimitive.content).isEqualTo("unstable")
        val first = json["deltas"]!!.jsonArray[1].jsonObject
        assertThat(first["subject"]!!.jsonPrimitive.content).isEqualTo(":app|com/acme/Timeouts.DEFAULT_MS:J")
        assertThat(first["impact"]!!.jsonObject["binary"]!!.jsonPrimitive.content).isEqualTo("RUNTIME")
        assertThat(first["reasons"]!!.jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content).isEqualTo("CONSTANT_INLINED_AT_CALLERS")
        assertThat(json["confidenceSummary"]!!.jsonObject["EXACT"]!!.jsonPrimitive.content).isEqualTo("2")
        assertThat(json).doesNotContainKey("impactedTests")
    }
}
