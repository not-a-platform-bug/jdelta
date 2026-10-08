package jdelta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import jdelta.core.RankedTest
import jdelta.core.WorkItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `jdelta impacted-tests [<base>..<head>]`: 영향받는 test를 순서대로 출력한다 (ARCHITECTURE.md §10).
 * `--format gradle-filter`는 Gradle plugin 없이 `./gradlew test $(jdelta impacted-tests --format gradle-filter)`로 쓸 수 있게 한다.
 */
internal class ImpactedTestsCommand : RevisionCommand(name = "impacted-tests") {
    override fun help(context: Context): String = """
        List the tests impacted by the changes since the base revision, most relevant first.
        Tests without a recorded trace are always included (conservatively).
    """.trimIndent()

    private val format by option("--format", help = "output format")
        .choice("text", "json", "gradle-filter")
        .default("text")

    override fun run() {
        val analysis = analyze(requireImpact = true)
        val tests = analysis.impact!!.plan.items.filterIsInstance<WorkItem.RunTestsFirst>().flatMap { it.tests }
        when (format) {
            "json" -> echo(json(tests))
            "gradle-filter" -> echo(gradleFilter(tests, testModules(analysis)).joinToString(" "))
            else -> {
                analysis.notes.forEach { echo("# $it", err = true) }
                if (tests.isEmpty()) echo("# no impacted tests", err = true)
                tests.forEach { t -> echo("${t.rank}\t${t.confidence}\t${t.test.canonical}\t${t.reasons.firstOrNull()?.message ?: ""}") }
            }
        }
    }

    private fun json(tests: List<RankedTest>): String {
        val o = buildJsonObject {
            putJsonArray("impactedTests") {
                tests.forEach { t ->
                    addJsonObject {
                        put("test", t.test.canonical)
                        put("rank", t.rank)
                        put("confidence", t.confidence.name)
                        put("path", JsonArray(t.path.map { JsonPrimitive(it.canonical) }))
                        put("reasons", JsonArray(t.reasons.map { JsonPrimitive("${it.code}: ${it.message}") }))
                    }
                }
            }
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o)
    }

    /**
     * module별 Test task 인자: `:core:test --tests 'com.acme.FooTest.works' :app:test --tests ...`.
     * `--tests`는 바로 앞 task에만 걸리므로 impacted test가 없는 module이 "No tests found"로 실패하지 않는다.
     * test 이름에 공백이 있을 수 있으므로 작은따옴표로 감싼다. `| xargs ./gradlew`로 넘긴다(xargs는 따옴표를 해석한다).
     * parameter type은 filter로 표현할 수 없어 method 이름까지만 쓴다.
     */
    private fun gradleFilter(tests: List<RankedTest>, modules: Map<String, String>): List<String> = tests
        .groupBy { modules[it.test.className] ?: ":" }
        .toSortedMap()
        .flatMap { (module, list) ->
            val task = if (module == ":") ":test" else "$module:test"
            listOf(task) + list
                .map { t -> if (t.test.method == null) t.test.className else "${t.test.className}.${t.test.method}" }
                .distinct()
                .flatMap { listOf("--tests", "'" + it.replace("'", "?") + "'") }
        }

    /** test class 이름 -> module path. head snapshot의 test source set에서 찾는다. */
    private fun testModules(analysis: Analysis): Map<String, String> {
        val testSets = analysis.resolved.headModel.modules.flatMap { it.sourceSets }.filter { it.isTest }.map { it.id }.toSet()
        return analysis.resolved.head.sourceSets.filter { it.id in testSets }
            .flatMap { ss -> ss.classes.values.map { it.id.binaryName to ss.id.module.path } }
            .toMap()
    }
}
