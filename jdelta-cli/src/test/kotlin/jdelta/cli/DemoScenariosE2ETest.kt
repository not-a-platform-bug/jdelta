package jdelta.cli

import jdelta.core.Confidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * 데모 scenario 1~5 (JDELTA_DESIGN.md §21, ARCHITECTURE.md §14 14단계).
 * `samples/acme-shop`을 임시 git repository로 복사해 snapshot -> record -> 변경 -> impacted-tests를 실제 Gradle로 실행한다.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DemoScenariosE2ETest {
    private val repo: Path = Path(System.getProperty("jdelta.repoDir"))
    private val demo: Path = Files.createTempDirectory("jdelta-demo").toRealPath()

    private val checksOut = "junit-jupiter:com.acme.app.CheckoutServiceTest#checksOut()"
    private val timeout = "junit-jupiter:com.acme.app.CheckoutServiceTest#timeout()"
    private val health = "junit-jupiter:com.acme.app.HealthTest#up()"
    private val calculates = "junit-jupiter:com.acme.core.PriceCalculatorTest#calculatesRoundedTotal()"
    private val tax = "junit-jupiter:com.acme.core.PriceCalculatorTest#computesTax()"
    private val columns = "junit-jupiter:com.acme.core.OrderRowTest#readsColumnNames()"
    private val discounts = "junit-jupiter:com.acme.pricing.DiscountsTest#appliesPercent()"
    private val tiers = "junit-jupiter:com.acme.pricing.DiscountsTest#tiers()"
    private val mapper = "junit-jupiter:com.acme.core.OrderRowMapperTest#mapsOrderToRow()"
    private val all = setOf(checksOut, timeout, health, calculates, tax, columns, discounts, tiers, mapper)

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @BeforeAll
    fun setUp() {
        repo.resolve("samples/acme-shop").copyToRecursively(demo, followLinks = false, overwrite = true)
        repo.resolve("gradlew").copyToRecursively(demo.resolve("gradlew"), followLinks = false, overwrite = true)
        Files.createDirectories(demo.resolve("gradle"))
        repo.resolve("gradle/wrapper").copyToRecursively(demo.resolve("gradle/wrapper"), followLinks = false, overwrite = true)
        git("init", "-q", "-b", "main")
        git("config", "core.autocrlf", "false")
        git("add", ".")
        git("commit", "-q", "-m", "demo")
        assertThat(jdelta("snapshot", "--build").first).isEqualTo(ExitCode.OK)
        val (code, _, err) = jdelta("record", "--", "./gradlew", "test", "-q")
        assertThat(code).describedAs(err).isEqualTo(ExitCode.OK)
        assertThat(err).contains("recorded 9 test(s)")
    }

    private fun git(vararg args: String): String {
        val r = Processes.run(listOf("git", "-c", "user.name=demo", "-c", "user.email=demo@example.com", "-c", "commit.gpgsign=false") + args, demo)
        check(r.exitCode == 0) { "git ${args.toList()}: ${r.stderr}" }
        return r.stdout
    }

    private fun jdelta(vararg args: String): Triple<Int, String, String> {
        val out = StringWriter()
        val err = StringWriter()
        val code = run(arrayOf(*args, "--project-dir", demo.toString()).let { a -> if ("--" in a) moveProjectDir(a) else a }, out, err)
        return Triple(code, out.toString(), err.toString())
    }

    /** `record -- cmd`에서는 option이 `--` 앞에 와야 한다. */
    private fun moveProjectDir(args: Array<String>): Array<String> {
        val list = args.toMutableList()
        list.removeAt(list.size - 1)
        list.removeAt(list.size - 1)
        list.addAll(1, listOf("--project-dir", demo.toString()))
        return list.toTypedArray()
    }

    private fun edit(path: String, from: String, to: String) {
        val file = demo.resolve(path)
        val text = file.readText()
        check(from in text) { "$from not in $path" }
        file.writeText(text.replace(from, to))
    }

    private data class Impacted(val test: String, val confidence: Confidence, val reason: String, val allReasons: List<String> = listOf(reason))

    /** branch에서 변경을 만들고 impacted-tests와 diff를 실행한 뒤 main으로 돌아간다. */
    private fun scenario(name: String, change: () -> Unit): Pair<List<Impacted>, JsonObject> {
        git("checkout", "-q", "-B", name, "main")
        change()
        try {
            val (code, out, err) = jdelta("impacted-tests", "main", "--build", "--format", "json")
            assertThat(code).describedAs(err).isEqualTo(ExitCode.OK)
            val tests = Json.parseToJsonElement(out).jsonObject.getValue("impactedTests").jsonArray.map {
                val o = it.jsonObject
                val reasons = o.getValue("reasons").jsonArray.map { r -> r.jsonPrimitive.content }
                Impacted(o.getValue("test").jsonPrimitive.content, Confidence.valueOf(o.getValue("confidence").jsonPrimitive.content), reasons.first(), reasons)
            }
            val (diffCode, diff, diffErr) = jdelta("diff", "main", "--format", "json")
            assertThat(diffCode).describedAs(diffErr).isEqualTo(ExitCode.OK)
            return tests to Json.parseToJsonElement(diff).jsonObject
        } finally {
            git("checkout", "-q", "--", ".")
            git("checkout", "-q", "main")
        }
    }

    private fun JsonObject.kinds(): List<String> = getValue("deltas").jsonArray.map { it.jsonObject.getValue("kind").jsonPrimitive.content }

    @Test
    @Order(1)
    fun `1 private method body change selects only tests that executed it`() {
        val (tests, diff) = scenario("s1") { edit("core/src/main/java/com/acme/core/PriceCalculator.java", "(amount + 5) / 10 * 10", "(amount + 4) / 10 * 10") }
        assertThat(diff.kinds()).containsExactly("METHOD_BODY_CHANGED")
        assertThat(tests.map { it.test }).containsExactlyInAnyOrder(calculates, checksOut, discounts)
        assertThat(tests.map { it.confidence }).containsOnly(Confidence.OBSERVED)
    }

    @Test
    @Order(2)
    fun `2 public signature change reaches downstream callers`() {
        val (tests, diff) = scenario("s2") {
            edit("core/src/main/java/com/acme/core/PriceCalculator.java", "public long calculate(Order order) {\n        return round(", "public long calculate(Order order, long shipping) {\n        return shipping + round(")
            edit("pricing/src/main/kotlin/com/acme/pricing/Discounts.kt", "calculator.calculate(order)", "calculator.calculate(order, 0)")
            edit("core/src/test/java/com/acme/core/PriceCalculatorTest.java", "new Order(\"A\", 3, 9))", "new Order(\"A\", 3, 9), 0)")
        }
        assertThat(diff.kinds()).contains("METHOD_DESCRIPTOR_CHANGED")
        assertThat(tests.first().test to tests.first().confidence).isEqualTo(calculates to Confidence.EXACT) // 바뀐 test 자신
        assertThat(tests.map { it.test }).containsExactlyInAnyOrder(calculates, checksOut, discounts)
        val recompile = diff.getValue("plan").jsonObject.getValue("items").jsonArray.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "RecompileModule" }
        assertThat(recompile.map { it.getValue("module").jsonPrimitive.content to it.getValue("scope").jsonPrimitive.content }).contains(":core" to "DOWNSTREAM")
    }

    @Test
    @Order(3)
    fun `3 Kotlin public inline function body change reaches Kotlin callers`() {
        val (tests, diff) = scenario("s3") { edit("pricing/src/main/kotlin/com/acme/pricing/Measure.kt", "Metrics.record(label)", "Metrics.record(label.uppercase())") }
        assertThat(diff.kinds()).contains("KOTLIN_INLINE_BODY_CHANGED", "METHOD_BODY_CHANGED")
        assertThat(tests.map { it.test }).containsExactly(checksOut)
    }

    @Test
    @Order(4)
    fun `4 compile-time constant change recommends downstream recompilation and is conservative`() {
        val (tests, diff) = scenario("s4") { edit("core/src/main/java/com/acme/core/Timeouts.java", "1000L", "3000L") }
        assertThat(diff.kinds()).contains("CONSTANT_VALUE_CHANGED")
        assertThat(tests.map { it.test }).containsExactlyInAnyOrderElementsOf(all)
        // 값을 inline한 caller가 다시 compile되어 body가 바뀐 test가 가장 앞이다
        assertThat(tests.first().test).isEqualTo(timeout)
        assertThat(tests.drop(1).map { it.confidence }).containsOnly(Confidence.CONSERVATIVE_UNKNOWN)
    }

    @Test
    @Order(5)
    fun `5 annotation metadata change is a framework change with inferred tests`() {
        val (tests, diff) = scenario("s5") { edit("core/src/main/java/com/acme/core/OrderRow.java", "@Column(name = \"price\")", "@Column(name = \"amount\")") }
        val delta = diff.getValue("deltas").jsonArray.single().jsonObject
        assertThat(delta.getValue("kind").jsonPrimitive.content).isEqualTo("FIELD_ANNOTATION_CHANGED")
        assertThat(delta.getValue("impact").jsonObject.getValue("framework").jsonPrimitive.content).isEqualTo("RUNTIME")
        assertThat(tests.map { it.test }).containsExactlyInAnyOrder(columns, mapper)
        assertThat(tests.map { it.confidence }).containsOnly(Confidence.INFERRED)
    }

    @Test
    @Order(6)
    fun `6 annotation processor input change is conservative until ap-trace exists`() {
        val (tests, diff) = scenario("s6") { edit("core/src/main/java/com/acme/core/OrderRow.java", "public long price;", "public long price;\n\n    public String currency;") }
        val delta = diff.getValue("deltas").jsonArray.map { it.jsonObject }.first { it.getValue("kind").jsonPrimitive.content == "FIELD_ADDED" }
        val codes = delta.getValue("reasons").jsonArray.map { it.jsonObject.getValue("code").jsonPrimitive.content }
        assertThat(codes).contains("PROCESSOR_SENSITIVE_ANNOTATION")
        // 생성된 OrderRowMapperImpl을 실행한 test가 보수적으로 포함된다
        assertThat(tests.single { it.test == mapper }.allReasons).anyMatch { it.startsWith("PROCESSOR_SENSITIVE_ANNOTATION") }
        assertThat(tests.map { it.test }).contains(columns, mapper)
    }
}
