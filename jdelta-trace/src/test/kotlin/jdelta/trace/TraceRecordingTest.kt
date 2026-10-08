package jdelta.trace

import jdelta.core.ClassId
import jdelta.core.ModuleId
import jdelta.core.TestId
import org.assertj.core.api.Assertions.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * 실제 agent와 JUnit listener를 붙인 별도 JVM에서 작은 test suite를 실행하고 raw trace를 검증한다 (ARCHITECTURE.md §13.2).
 */
class TraceRecordingTest {
    private val root: Path = Files.createTempDirectory("jdelta-trace-e2e").toRealPath()
    private val mainOut = root.resolve("app/build/classes/java/main")
    private val testOut = root.resolve("app/build/classes/java/test")
    private val rawDir = root.resolve(".jdelta/traces/raw")
    private val app = ModuleId(":app")
    private val price = ClassId(app, "com/acme/Price")
    private val engine = "junit-jupiter"
    private val priceTest = "com.acme.PriceTest"

    private val mainSources = mapOf(
        "com/acme/Price.java" to """
            package com.acme;
            public class Price {
                static final long BASE = Registry.load();
                public long total(int qty) { return qty * BASE; }
                public long discount(long amount) { return amount / 10; }
                public Runnable task() { return () -> discount(5); }
            }
        """,
        "com/acme/Registry.java" to """
            package com.acme;
            public class Registry {
                static { System.setProperty("jdelta.fixture.registry", "loaded"); }
                static long load() { return 100L; }
            }
        """,
    )

    private val testSources = mapOf(
        "com/acme/PriceTest.java" to """
            package com.acme;
            import org.junit.jupiter.api.*;
            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.ValueSource;
            import static org.junit.jupiter.api.Assertions.*;

            class PriceTest {
                static Price shared;
                @BeforeAll static void setUp() { shared = new Price(); }
                @Test void totalWorks() { assertEquals(200L, shared.total(2)); }
                @Test void discountWorks() { assertEquals(1L, shared.discount(10)); shared.task().run(); }
                @ParameterizedTest @ValueSource(ints = {1, 2}) void scales(int qty) { assertTrue(shared.total(qty) > 0); }
                @Test void fails() { fail("expected"); }
            }
        """,
        "com/acme/RunAll.java" to """
            package com.acme;
            import org.junit.platform.launcher.core.*;
            import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
            public class RunAll {
                public static void main(String[] args) {
                    var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(PriceTest.class)).build();
                    LauncherFactory.create().execute(request);
                }
            }
        """,
    )

    private fun compile(sources: Map<String, String>, out: Path, classpath: String) {
        val src = root.resolve("src-" + out.fileName)
        val files = sources.map { (path, code) -> src.resolve(path).also { it.parent.createDirectories(); it.writeText(code.trimIndent()) } }
        out.createDirectories()
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null, *(listOf("--release", "17", "-g", "-d", out.toString(), "-cp", classpath) + files.map { it.toString() }).toTypedArray())
        check(result == 0) { "fixture compile failed" }
    }

    private fun runTests(withAgent: Boolean): Int {
        val testClasspath = System.getProperty("java.class.path")
        compile(mainSources, mainOut, testClasspath)
        compile(testSources, testOut, "$mainOut${File.pathSeparator}$testClasspath")
        val config = root.resolve("agent.properties").also {
            it.writeText(
                """
                runId=run-1
                output=${rawDir.toString().replace("\\", "/")}
                commit=abc123
                dir.0=${mainOut.toString().replace("\\", "/")}
                module.0=:app
                dir.1=${testOut.toString().replace("\\", "/")}
                module.1=:app
                """.trimIndent(),
            )
        }
        val classpath = listOf(mainOut.toString(), testOut.toString(), System.getProperty("jdelta.junitJar"), testClasspath).joinToString(File.pathSeparator)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOfNotNull(
            java,
            if (withAgent) "-javaagent:${System.getProperty("jdelta.agentJar")}=config=$config" else null,
            "-cp", classpath,
            "com.acme.RunAll",
        )
        val process = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "test JVM failed ($code):\n$output" }
        check(!output.contains("jdelta: cannot instrument")) { output }
        return code
    }

    private fun method(name: String, descriptor: String, owner: ClassId = price) = owner.method(name, descriptor)

    private fun test(method: String, params: String = "") = TestId(engine, priceTest, method, params)

    @Test
    fun `records per-test scopes, class setup and one-time initialization`() {
        runTests(withAgent = true)
        val trace = RawTraceReader.readRun(rawDir.resolve("run-1")).single()

        assertThat(trace.runId).isEqualTo("run-1")
        assertThat(trace.commit).isEqualTo("abc123")
        assertThat(trace.forkId).startsWith("pid-")
        assertThat(trace.contaminated).isFalse()
        assertThat(trace.methods).allMatch { it.owner.module == app }

        val total = trace.methodsOf(trace.scope(ScopeKind.TEST, test("totalWorks"))!!)
        assertThat(total).contains(method("total", "(I)J")).doesNotContain(method("discount", "(J)J"))

        val discount = trace.scope(ScopeKind.TEST, test("discountWorks"))!!
        assertThat(trace.methodsOf(discount))
            .contains(method("discount", "(J)J"), method("task", "()Ljava/lang/Runnable;"), method("lambda\$task\$0", "()V"))
            .doesNotContain(method("total", "(I)J"))
        assertThat(discount.status).isEqualTo("SUCCESSFUL")

        // parameterized invocation 두 번은 template method 하나로 모인다
        val scales = trace.scopes.filter { it.test.method == "scales" }
        assertThat(scales).hasSize(1)
        assertThat(scales.single().test).isEqualTo(test("scales", "int"))

        assertThat(trace.scope(ScopeKind.TEST, test("fails"))!!.status).isEqualTo("FAILED")

        // @BeforeAll과 처음 사용 시의 static 초기화는 class setup에 귀속되고 one-time 집합에 들어간다
        val setup = trace.methodsOf(trace.scope(ScopeKind.CLASS_SETUP, TestId(engine, priceTest))!!)
        assertThat(setup).contains(
            method("setUp", "()V", ClassId(app, "com/acme/PriceTest")),
            method("<init>", "()V"),
            method("<clinit>", "()V"),
            method("<clinit>", "()V", ClassId(app, "com/acme/Registry")),
        )
        assertThat(trace.oneTimeInitMethods).contains(
            method("<clinit>", "()V"),
            method("<clinit>", "()V", ClassId(app, "com/acme/Registry")),
            method("load", "()J", ClassId(app, "com/acme/Registry")),
        ).doesNotContain(method("total", "(I)J"))
    }

    @Test
    fun `listener on the classpath without the agent is inert`() {
        runTests(withAgent = false)
        assertThat(rawDir.exists()).isFalse()
    }
}

class TraceStoreTest {
    private val app = ModuleId(":app")
    private val a = ClassId(app, "com/acme/A").method("a", "()V")
    private val b = ClassId(app, "com/acme/A").method("b", "()V")
    private val test = TestId("junit-jupiter", "com.acme.ATest", "works", "")

    private fun trace(vararg methods: jdelta.core.MethodId, runId: String = "run") = RawTrace(
        runId = runId, forkId = "fork", commit = "c", classpathFingerprint = null, contaminated = false,
        methods = methods.toList(),
        scopes = listOf(RawScope(ScopeKind.TEST, test, methods.indices.toSet(), 3, "SUCCESSFUL")),
        ambient = emptySet(), oneTimeInit = setOf(0),
    )

    @Test
    fun `newer observation replaces the older one and survives a save-load round trip`() {
        val first = TraceStore.EMPTY.merge(listOf(trace(a, b)), "t1")
        val second = first.merge(listOf(trace(b, runId = "run-2")), "t2")
        assertThat(second.tests.getValue(test).methods).containsExactly(b)
        assertThat(second.forks).hasSize(1) // 같은 test class를 다시 관측한 옛 fork는 버린다

        val file = Files.createTempDirectory("store").resolve("store.json")
        second.save(file)
        val loaded = TraceStore.load(file)
        assertThat(loaded.tests).isEqualTo(second.tests)
        assertThat(loaded.forks).isEqualTo(second.forks)
    }

    @Test
    fun `unknown format is treated as empty`() {
        val file = Files.createTempFile("store", ".json").also { it.writeText("{\"formatVersion\": 99}") }
        assertThat(TraceStore.load(file).isEmpty).isTrue()
    }
}
