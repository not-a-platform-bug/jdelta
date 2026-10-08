package jdelta.engine

import jdelta.classfile.ProjectSnapshot
import jdelta.core.ClassId
import jdelta.core.Confidence
import jdelta.core.ImpactResult
import jdelta.core.MethodId
import jdelta.core.ModuleDependency
import jdelta.core.ModuleId
import jdelta.core.ModuleModel
import jdelta.core.ProjectModel
import jdelta.core.ReasonCode
import jdelta.core.SourceSetId
import jdelta.core.SourceSetModel
import jdelta.core.TestId
import jdelta.core.WorkItem
import jdelta.trace.ForkObservation
import jdelta.trace.TestObservation
import jdelta.trace.TraceStore
import org.assertj.core.api.Assertions.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test

/** graph 구성 + 규칙 표를 javac fixture와 손으로 만든 trace로 검증한다 (ARCHITECTURE.md §6). */
class ImpactTest {
    private val app = ModuleId(":app")
    private val web = ModuleId(":web")
    private val price = ClassId(app, "com/acme/Price")
    private val checkout = ClassId(web, "com/acme/web/Checkout")

    private val priceSource = """
        package com.acme;
        public class Price {
            public static final int SCALE = 100;
            static final java.util.Map<String, Integer> RATES = Rates.load();
            public long total(int qty) { return round(qty * 3L); }
            public long discount(long amount) { return amount / 10; }
            private long round(long v) { return v * SCALE; }
        }
    """
    private val ratesSource = """
        package com.acme;
        class Rates { static java.util.Map<String, Integer> load() { return java.util.Map.of("KRW", 1); } }
    """
    private val priceTest = """
        package com.acme;
        import org.junit.jupiter.api.Test;
        class PriceTest {
            @Test void totalWorks() { new Price().total(1); }
            @Test void discountWorks() { new Price().discount(10); }
        }
    """
    private val checkoutSource = """
        package com.acme.web;
        import com.acme.Price;
        public class Checkout { public long run() { return new Price().total(2) + new Price().discount(5); } }
    """
    private val checkoutTest = """
        package com.acme.web;
        import org.junit.jupiter.api.Test;
        class CheckoutTest { @Test void checkout() { new Checkout().run(); } }
    """

    private val tTotal = TestId("junit-jupiter", "com.acme.PriceTest", "totalWorks", "")
    private val tDiscount = TestId("junit-jupiter", "com.acme.PriceTest", "discountWorks", "")
    private val tCheckout = TestId("junit-jupiter", "com.acme.web.CheckoutTest", "checkout", "")

    private fun m(owner: ClassId, name: String, desc: String) = MethodId(owner, name, desc)

    /** 실제 agent가 남겼을 trace와 같은 내용. */
    private val traces = TraceStore(
        tests = listOf(
            observation(tTotal, m(price, "total", "(I)J"), m(price, "round", "(J)J"), m(price, "<init>", "()V")),
            observation(tDiscount, m(price, "discount", "(J)J"), m(price, "<init>", "()V")),
            observation(tCheckout, m(checkout, "run", "()J"), m(price, "total", "(I)J"), m(price, "round", "(J)J"), m(price, "discount", "(J)J")),
        ).associateBy { it.test },
        forks = listOf(
            ForkObservation("run", "fork-1", setOf(m(price, "<clinit>", "()V"), m(ClassId(app, "com/acme/Rates"), "load", "()Ljava/util/Map;")), setOf(TestId("junit-jupiter", "com.acme.PriceTest"))),
            ForkObservation("run", "fork-2", setOf(m(price, "<clinit>", "()V")), setOf(TestId("junit-jupiter", "com.acme.web.CheckoutTest"))),
        ),
    )

    private fun observation(test: TestId, vararg methods: MethodId) =
        TestObservation(test, methods.toSet(), "abc", "2026-10-08T00:00:00Z", "run", "fork", 5, "SUCCESSFUL", false)

    private data class Sources(
        val appMain: Map<String, String>,
        val appTest: Map<String, String>,
        val webMain: Map<String, String>,
        val webTest: Map<String, String>,
    )

    private fun defaultSources() = Sources(
        mapOf("com/acme/Price.java" to priceSource, "com/acme/Rates.java" to ratesSource),
        mapOf("com/acme/PriceTest.java" to priceTest),
        mapOf("com/acme/web/Checkout.java" to checkoutSource),
        mapOf("com/acme/web/CheckoutTest.java" to checkoutTest),
    )

    private fun compile(root: Path, sources: Sources): ProjectModel {
        val cp = System.getProperty("java.class.path")
        fun javac(name: String, files: Map<String, String>, classpath: String): Path {
            val out = root.resolve("$name/classes").createDirectories()
            val paths = files.map { (p, code) -> root.resolve("$name/src/$p").also { it.parent.createDirectories(); it.writeText(code.trimIndent()) } }
            val code = ToolProvider.getSystemJavaCompiler().run(null, null, null, *(listOf("--release", "17", "-d", "$out", "-cp", classpath) + paths.map { "$it" }).toTypedArray())
            check(code == 0) { "compile failed: $name" }
            return out
        }
        val appMain = javac("app-main", sources.appMain, cp)
        val appTest = javac("app-test", sources.appTest, listOf(appMain, cp).joinToString(File.pathSeparator))
        val webMain = javac("web-main", sources.webMain, listOf(appMain, cp).joinToString(File.pathSeparator))
        val webTest = javac("web-test", sources.webTest, listOf(webMain, appMain, cp).joinToString(File.pathSeparator))
        return ProjectModel(
            root,
            listOf(
                ModuleModel(app, root, sourceSets = listOf(ss(app, "main", appMain), ss(app, "test", appTest))),
                ModuleModel(web, root, sourceSets = listOf(ss(web, "main", webMain), ss(web, "test", webTest)), dependencies = listOf(ModuleDependency(app, ModuleDependency.Kind.IMPLEMENTATION))),
            ),
        )
    }

    private fun ss(module: ModuleId, name: String, dir: Path) = SourceSetModel(SourceSetId(module, name), classesDirs = listOf(dir))

    private fun impact(after: Sources, traces: TraceStore = this.traces): Pair<List<jdelta.core.SemanticDelta>, ImpactResult> {
        val engine = JDeltaEngine()
        val baseModel = compile(Files.createTempDirectory("base"), defaultSources())
        val headModel = compile(Files.createTempDirectory("head"), after)
        val base: ProjectSnapshot = engine.snapshot(baseModel)
        val head: ProjectSnapshot = engine.snapshot(headModel)
        val diff = engine.diff(base, head)
        return diff.deltas to engine.impact(diff, base, head, headModel, traces)
    }

    private fun ImpactResult.ranked() = plan.items.filterIsInstance<WorkItem.RunTestsFirst>().singleOrNull()?.tests.orEmpty()

    private fun ImpactResult.confidenceOf(test: TestId) = ranked().firstOrNull { it.test == test }?.confidence

    @Test
    fun `private method body change selects only the tests that executed it`() {
        val after = defaultSources().copy(appMain = defaultSources().appMain + ("com/acme/Price.java" to priceSource.replace("v * SCALE", "v * SCALE + 1")))
        val (deltas, result) = impact(after)
        assertThat(deltas.map { it.kind.name }).containsExactly("METHOD_BODY_CHANGED")
        assertThat(result.ranked().map { it.test }).containsExactlyInAnyOrder(tTotal, tCheckout)
        assertThat(result.confidenceOf(tTotal)).isEqualTo(Confidence.OBSERVED)
        assertThat(result.plan.items.filterIsInstance<WorkItem.RecompileModule>()).isEmpty()
        assertThat(result.plan.items.last()).isInstanceOf(WorkItem.RunFullSuite::class.java)
        val path = result.ranked().first { it.test == tTotal }.path
        assertThat(path).containsExactly(m(price, "round", "(J)J"), tTotal)
    }

    @Test
    fun `public signature change reaches the old method's tests and its downstream callers`() {
        val after = defaultSources().copy(
            appMain = defaultSources().appMain + ("com/acme/Price.java" to priceSource.replace("discount(long amount) { return amount / 10; }", "discount(long amount, int pct) { return amount * pct / 100; }")),
            appTest = mapOf("com/acme/PriceTest.java" to priceTest.replace("discount(10)", "discount(10, 10)")),
            webMain = mapOf("com/acme/web/Checkout.java" to checkoutSource.replace("discount(5)", "discount(5, 10)")),
        )
        val (deltas, result) = impact(after)
        assertThat(deltas.map { it.kind.name }).contains("METHOD_DESCRIPTOR_CHANGED")
        assertThat(result.ranked().map { it.test }).contains(tDiscount, tCheckout).doesNotContain(tTotal)
        // 바뀐 test가 가장 앞이다
        assertThat(result.ranked().first().test).isEqualTo(tDiscount)
        assertThat(result.ranked().first().confidence).isEqualTo(Confidence.EXACT)
        val recompile = result.plan.items.filterIsInstance<WorkItem.RecompileModule>().single { it.module == app }
        assertThat(recompile.scope).isEqualTo(WorkItem.Scope.DOWNSTREAM)
        assertThat(recompile.reasons.single().message).contains(":web")
    }

    @Test
    fun `constant change includes downstream module tests conservatively`() {
        val after = defaultSources().copy(appMain = defaultSources().appMain + ("com/acme/Price.java" to priceSource.replace("SCALE = 100", "SCALE = 1000")))
        val (deltas, result) = impact(after)
        assertThat(deltas.map { it.kind.name }).contains("CONSTANT_VALUE_CHANGED")
        assertThat(result.ranked().map { it.test }).containsExactlyInAnyOrder(tTotal, tDiscount, tCheckout)
        assertThat(result.ranked().flatMap { it.reasons }.map { it.code }).contains(ReasonCode.CONSTANT_INLINED_AT_CALLERS)
        assertThat(result.plan.items.filterIsInstance<WorkItem.RecompileModule>().map { it.module }).contains(app)
    }

    @Test
    fun `static initializer change includes every test class of the fork`() {
        val after = defaultSources().copy(appMain = defaultSources().appMain + ("com/acme/Rates.java" to ratesSource.replace("\"KRW\", 1", "\"KRW\", 2")))
        val (_, result) = impact(after)
        val classes = result.ranked().map { it.test }
        assertThat(classes).contains(TestId("junit-jupiter", "com.acme.PriceTest")).doesNotContain(TestId("junit-jupiter", "com.acme.web.CheckoutTest"))
        assertThat(result.ranked().flatMap { it.reasons }.map { it.code }).contains(ReasonCode.ONE_TIME_INITIALIZATION)
        assertThat(result.confidenceOf(TestId("junit-jupiter", "com.acme.PriceTest"))).isEqualTo(Confidence.INFERRED)
    }

    @Test
    fun `new and untraced tests are always included`() {
        val after = defaultSources().copy(
            appMain = defaultSources().appMain + ("com/acme/Price.java" to priceSource.replace("amount / 10", "amount / 20")),
            appTest = mapOf("com/acme/PriceTest.java" to priceTest.replace("@Test void discountWorks()", "@Test void brandNew() {}\n    @Test void discountWorks()")),
        )
        val (_, result) = impact(after)
        val brandNew = TestId("junit-jupiter", "com.acme.PriceTest", "brandNew", "")
        assertThat(result.confidenceOf(brandNew)).isEqualTo(Confidence.EXACT) // 바뀐 test 자신
        assertThat(result.ranked().map { it.test }).contains(tDiscount, tCheckout)

        // trace가 전혀 없으면 모든 test가 NO_TRACE_FOR_TEST로 들어간다
        val (_, untraced) = impact(after, TraceStore.EMPTY)
        assertThat(untraced.ranked().filter { it.reasons.any { r -> r.code == ReasonCode.NO_TRACE_FOR_TEST } }.map { it.test })
            .contains(tTotal, tCheckout)
    }

    @Test
    fun `parameter types follow JUnit MethodSource`() {
        assertThat(TestDiscovery.parameterTypes("(I[Ljava/lang/String;Lcom/acme/Outer\$Inner;[[J)V"))
            .isEqualTo("int, [Ljava.lang.String;, com.acme.Outer\$Inner, [[J")
        assertThat(TestDiscovery.parameterTypes("()V")).isEmpty()
    }
}
