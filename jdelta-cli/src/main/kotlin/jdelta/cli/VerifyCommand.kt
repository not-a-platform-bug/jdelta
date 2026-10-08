package jdelta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import jdelta.engine.Workspace
import jdelta.trace.TraceStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * `jdelta verify`: impacted-first 실행의 phase 1/2 결과를 모아 miss를 기록한다 (ARCHITECTURE.md §8.7, §9.2).
 * phase 1(impacted test)이 실패했으면 exit code 1로 build를 실패시킨다. phase 2는 이미 실행된 뒤다.
 */
internal class VerifyCommand : CoreCliktCommand(name = "verify") {
    override fun help(context: Context): String = """
        Summarize an impacted-first run: compare phase 1 (impacted tests) with phase 2 (full suite),
        record misses in .jdelta/metrics, and fail when phase 1 failed.
    """.trimIndent()

    private val projectDir by option("--project-dir", metavar = "DIR").default(".")
    private val impactedFile by option("--impacted", metavar = "FILE", help = "output of 'impacted-tests --format json'").required()
    private val phase1 by option("--phase1", metavar = "DIR", help = "JUnit XML results of the impacted tests (repeatable)").multiple()
    private val phase2 by option("--phase2", metavar = "DIR", help = "JUnit XML results of the full suite (repeatable)").multiple()

    override fun run() {
        val dir = Path(projectDir).absolute().normalize()
        val root = try {
            Git(dir).topLevel()
        } catch (e: JDeltaFailure) {
            dir
        }
        val impacted = impacted(Path(impactedFile))
        val first = phase1.flatMap { results(Path(it)) }
        val second = phase2.flatMap { results(Path(it)) }
        val workspace = Workspace(root)
        val traces = TraceStore.load(workspace.traceStore)

        val phase1Failures = first.filter { it.failed }
        val phase2Failures = second.filter { it.failed }
        // miss 후보: phase 2에서 실패했지만 impacted set에 없던 test. 직전 관측도 실패였던 test는 원래 깨져 있던 것이다.
        val misses = phase2Failures.filter { r -> impacted.none { it.matches(r) } }.filter { r -> !previouslyFailed(r, traces) }

        val now = Instant.now().toString()
        workspace.locked {
            val metrics = workspace.dir.resolve("metrics").createDirectories()
            append(
                metrics.resolve("runs.jsonl"),
                buildJsonObject {
                    put("time", now)
                    put("impacted", impacted.size)
                    put("phase1Tests", first.size)
                    put("phase1Failures", phase1Failures.size)
                    put("phase2Tests", second.size)
                    put("phase2Failures", phase2Failures.size)
                    put("misses", misses.size)
                },
            )
            misses.forEach { m ->
                append(
                    metrics.resolve("misses.jsonl"),
                    buildJsonObject {
                        put("time", now)
                        put("test", "${m.className}#${m.name}")
                        put("message", m.message)
                        putJsonArray("impacted") { impacted.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.canonical)) } }
                    },
                )
            }
        }

        echo("jdelta: phase 1 ran ${first.size} impacted test(s), ${phase1Failures.size} failed; phase 2 ran ${second.size} test(s), ${phase2Failures.size} failed")
        if (misses.isNotEmpty()) {
            echo("jdelta: ${misses.size} failing test(s) were not in the impacted set (recorded in .jdelta/metrics/misses.jsonl):")
            misses.forEach { echo("  ${it.className}#${it.name}") }
        }
        if (phase1Failures.isNotEmpty()) {
            throw JDeltaFailure("impacted tests failed: ${phase1Failures.joinToString { "${it.className}#${it.name}" }}")
        }
    }

    private data class ImpactedTest(val canonical: String, val className: String, val method: String?) {
        /** JUnit XML의 testcase 이름은 display name이다. method 이름으로 시작하면 같은 test로 본다. parameterized invocation은 class로 맞춘다. */
        fun matches(r: TestResult): Boolean = r.className == className &&
            (method == null || r.name == method || r.name.startsWith("$method(") || r.name.startsWith("["))
    }

    private data class TestResult(val className: String, val name: String, val failed: Boolean, val message: String)

    private fun impacted(file: Path): List<ImpactedTest> {
        if (!file.isRegularFile()) return emptyList()
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        return o["impactedTests"]?.jsonArray.orEmpty().map { e ->
            val canonical = e.jsonObject.getValue("test").jsonPrimitive.content
            val rest = canonical.substringAfter(':')
            ImpactedTest(canonical, rest.substringBefore('#'), rest.substringAfter('#', "").substringBefore('(').ifEmpty { null })
        }
    }

    /** Gradle의 JUnit XML(`TEST-*.xml`). */
    private fun results(dir: Path): List<TestResult> {
        if (!dir.isDirectory()) return emptyList()
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
        return Files.list(dir).use { files -> files.filter { it.name.startsWith("TEST-") && it.name.endsWith(".xml") }.sorted().toList() }.flatMap { file ->
            val cases = builder.parse(file.toFile()).getElementsByTagName("testcase")
            (0 until cases.length).map { i ->
                val e = cases.item(i) as Element
                val failure = (e.getElementsByTagName("failure").item(0) ?: e.getElementsByTagName("error").item(0)) as Element?
                TestResult(e.getAttribute("classname"), e.getAttribute("name"), failure != null, failure?.getAttribute("message").orEmpty().take(500))
            }
        }
    }

    private fun previouslyFailed(r: TestResult, traces: TraceStore): Boolean =
        traces.tests.values.any { o -> o.test.className == r.className && o.test.method != null && r.name.startsWith(o.test.method!!) && o.status == "FAILED" }

    private fun append(file: Path, line: JsonObject) {
        Files.writeString(file, Json.encodeToString(JsonObject.serializer(), line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
