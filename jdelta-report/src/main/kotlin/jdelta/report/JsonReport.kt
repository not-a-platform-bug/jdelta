package jdelta.report

import jdelta.core.Confidence
import jdelta.core.Reason
import jdelta.core.WorkItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 기계용 report (ARCHITECTURE.md §11.2). 1.0 전까지 schema는 unstable이다 (D6). */
internal object JsonReport {
    const val SCHEMA_VERSION = "0.1"

    private val json = Json { prettyPrint = true }

    fun render(input: ReportInput): String = json.encodeToString(JsonObject.serializer(), toJson(input)) + "\n"

    fun toJson(input: ReportInput): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("stability", "unstable")
        putJsonObject("base") { revision(input.base) }
        putJsonObject("head") { revision(input.head) }
        putJsonArray("deltas") {
            input.deltas.forEach { d ->
                addJsonObject {
                    put("subject", d.subject.canonical)
                    put("display", Display.name(d.subject))
                    put("kind", d.kind.name)
                    putJsonObject("impact") {
                        put("compile", d.compileImpact.name)
                        put("binary", d.binaryImpact.name)
                        put("reflection", d.reflectionImpact.name)
                        put("framework", d.frameworkImpact.name)
                        put("test", d.testImpact.name)
                    }
                    put("confidence", d.confidence.name)
                    putJsonArray("reasons") { d.reasons.forEach { addJsonObject { reason(it) } } }
                }
            }
        }
        val impact = input.impact
        if (impact != null) {
            putJsonObject("plan") {
                putJsonArray("items") {
                    impact.plan.items.forEach { item ->
                        addJsonObject {
                            put("type", item::class.simpleName)
                            put("confidence", item.confidence.name)
                            when (item) {
                                is WorkItem.RecompileModule -> {
                                    put("module", item.module.path)
                                    put("scope", item.scope.name)
                                }
                                is WorkItem.RunTestsFirst -> put("tests", item.tests.size)
                                is WorkItem.RunFullSuite -> putJsonArray("modules") { item.modules?.map { it.path }?.sorted()?.forEach { add(it) } }
                                is WorkItem.ReviewManually -> put("subject", item.subject.canonical)
                            }
                            putJsonArray("reasons") { item.reasons.forEach { addJsonObject { reason(it) } } }
                        }
                    }
                }
                putJsonArray("fallbacks") {
                    impact.plan.fallbacks.forEach { f ->
                        addJsonObject {
                            put("trigger", f.trigger.canonical)
                            put("code", f.code.name)
                        }
                    }
                }
            }
            putJsonArray("impactedTests") {
                impact.plan.items.filterIsInstance<WorkItem.RunTestsFirst>().flatMap { it.tests }.forEach { t ->
                    addJsonObject {
                        put("test", t.test.canonical)
                        put("rank", t.rank)
                        put("confidence", t.confidence.name)
                        putJsonArray("path") { t.path.forEach { add(it.canonical) } }
                        putJsonArray("reasons") { t.reasons.forEach { addJsonObject { reason(it) } } }
                    }
                }
            }
        }
        putJsonObject("confidenceSummary") {
            val counts = input.deltas.groupingBy { it.confidence }.eachCount()
            Confidence.entries.forEach { put(it.name, counts[it] ?: 0) }
        }
        putJsonArray("notes") { input.notes.forEach { add(it) } }
    }

    private fun JsonObjectBuilder.revision(r: RevisionInfo) {
        put("label", r.label)
        put("sha", r.sha)
        put("resolution", r.resolution)
        put("dirty", r.dirty)
    }

    private fun JsonObjectBuilder.reason(reason: Reason) {
        put("code", reason.code.name)
        put("message", reason.message)
        putJsonArray("evidence") { reason.evidence.forEach { add(it.canonical) } }
    }
}
