package jdelta.engine

import jdelta.core.ClassId
import jdelta.core.Confidence
import jdelta.core.DeltaKind
import jdelta.core.ImpactLevel
import jdelta.core.ImpactResult
import jdelta.core.MethodId
import jdelta.core.ModuleId
import jdelta.core.ModuleModel
import jdelta.core.ProjectModel
import jdelta.core.ReasonCode
import jdelta.core.SemanticDelta
import jdelta.core.SourceSetId
import jdelta.core.SourceSetModel
import jdelta.core.TestId
import jdelta.core.WorkItem
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

/**
 * framework profile (ARCHITECTURE.md §6.4). framework library 대신 같은 package 이름의 stub annotation을 compile한다.
 * profile은 annotation descriptor 문자열만 보므로 실제 library와 결과가 같다.
 */
class FrameworkProfileTest {
    private val app = ModuleId(":app")

    private val stubs = mapOf(
        "org/springframework/stereotype/Service.java" to annotation("org.springframework.stereotype", "Service", "TYPE"),
        "org/springframework/web/bind/annotation/RestController.java" to annotation("org.springframework.web.bind.annotation", "RestController", "TYPE"),
        "org/springframework/web/bind/annotation/GetMapping.java" to annotation("org.springframework.web.bind.annotation", "GetMapping", "METHOD", "String value() default \"\";"),
        "org/springframework/boot/test/context/SpringBootTest.java" to annotation("org.springframework.boot.test.context", "SpringBootTest", "TYPE"),
        "org/springframework/boot/test/autoconfigure/web/servlet/WebMvcTest.java" to annotation("org.springframework.boot.test.autoconfigure.web.servlet", "WebMvcTest", "TYPE"),
        "com/fasterxml/jackson/annotation/JsonProperty.java" to annotation("com.fasterxml.jackson.annotation", "JsonProperty", "FIELD", "String value() default \"\";"),
        "jakarta/persistence/Enumerated.java" to annotation("jakarta.persistence", "Enumerated", "FIELD", "String value() default \"ORDINAL\";"),
        "org/mapstruct/Mapper.java" to annotation("org.mapstruct", "Mapper", "TYPE"),
    )

    private fun annotation(pkg: String, name: String, target: String, body: String = "") = """
        package $pkg;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.$target)
        public @interface $name { $body }
    """

    private val main = mapOf(
        "com/acme/PriceService.java" to """
            package com.acme;
            @org.springframework.stereotype.Service
            public class PriceService { public long price() { return 1; } }
        """,
        "com/acme/PriceController.java" to """
            package com.acme;
            @org.springframework.web.bind.annotation.RestController
            public class PriceController {
                @org.springframework.web.bind.annotation.GetMapping("/price") public long get() { return 1; }
            }
        """,
        "com/acme/OrderJson.java" to """
            package com.acme;
            public class OrderJson { @com.fasterxml.jackson.annotation.JsonProperty("sku") public String sku; }
        """,
        "com/acme/Status.java" to "package com.acme; public enum Status { NEW, PAID }",
        "com/acme/OrderEntity.java" to """
            package com.acme;
            public class OrderEntity { @jakarta.persistence.Enumerated public Status status; }
        """,
        "com/acme/OrderView.java" to "package com.acme; public record OrderView(String sku) {}",
        "com/acme/OrderMapper.java" to """
            package com.acme;
            @org.mapstruct.Mapper
            public interface OrderMapper { OrderView toView(OrderJson json); }
        """,
    )

    /** annotation processor가 만든 것처럼 source directory 밖에서 온 class */
    private val generated = mapOf(
        "com/acme/OrderMapperImpl.java" to """
            package com.acme;
            public class OrderMapperImpl implements OrderMapper { public OrderView toView(OrderJson json) { return new OrderView(json.sku); } }
        """,
    )

    private val tests = mapOf(
        "com/acme/ContextTest.java" to """
            package com.acme;
            import org.junit.jupiter.api.Test;
            @org.springframework.boot.test.context.SpringBootTest
            class ContextTest { @Test void contextLoads() {} }
        """,
        "com/acme/PriceControllerWebTest.java" to """
            package com.acme;
            import org.junit.jupiter.api.Test;
            @org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
            class PriceControllerWebTest { @Test void get() {} }
        """,
        "com/acme/OrderJsonTest.java" to """
            package com.acme;
            import org.junit.jupiter.api.Test;
            class OrderJsonTest { @Test void serializes() {} }
        """,
        "com/acme/MapperTest.java" to """
            package com.acme;
            import org.junit.jupiter.api.Test;
            class MapperTest { @Test void maps() { new OrderMapperImpl().toView(new OrderJson()); } }
        """,
        "com/acme/PlainTest.java" to """
            package com.acme;
            import org.junit.jupiter.api.Test;
            class PlainTest { @Test void plain() {} }
        """,
    )

    private fun test(cls: String, method: String) = TestId("junit-jupiter", "com.acme.$cls", method, "")

    private val traces = TraceStore(
        listOf(
            test("ContextTest", "contextLoads") to listOf<MethodId>(),
            test("PriceControllerWebTest", "get") to listOf(ClassId(app, "com/acme/PriceController").method("get", "()J")),
            test("OrderJsonTest", "serializes") to listOf(),
            test("MapperTest", "maps") to listOf(ClassId(app, "com/acme/OrderMapperImpl").method("toView", "(Lcom/acme/OrderJson;)Lcom/acme/OrderView;")),
            test("PlainTest", "plain") to listOf(),
        ).associate { (t, methods) -> t to TestObservation(t, methods.toSet(), "c", "t", "run", "fork", 1, "SUCCESSFUL", false) },
        emptyList(),
    )

    private fun compile(root: Path, mainSources: Map<String, String>): ProjectModel {
        val cp = System.getProperty("java.class.path")
        fun javac(dir: String, files: Map<String, String>, out: Path, classpath: String): Path {
            val src = root.resolve(dir)
            val paths = files.map { (p, code) -> src.resolve(p).also { it.parent.createDirectories(); it.writeText(code.trimIndent()) } }
            out.createDirectories()
            val code = ToolProvider.getSystemJavaCompiler().run(null, null, null, *(listOf("--release", "17", "-d", "$out", "-cp", classpath) + paths.map { "$it" }).toTypedArray())
            check(code == 0) { "compile failed: $dir" }
            return src
        }
        val mainOut = root.resolve("classes/main")
        val testOut = root.resolve("classes/test")
        javac("stubs", stubs, mainOut, cp)
        val mainSrc = javac("src/main/java", mainSources, mainOut, "$mainOut${File.pathSeparator}$cp")
        javac("build/generated/sources/annotationProcessor/java/main", generated, mainOut, "$mainOut${File.pathSeparator}$cp")
        val testSrc = javac("src/test/java", tests, testOut, "$mainOut${File.pathSeparator}$cp")
        return ProjectModel(
            root,
            listOf(
                ModuleModel(
                    app, root,
                    sourceSets = listOf(
                        SourceSetModel(SourceSetId(app, "main"), sourceDirs = listOf(mainSrc, root.resolve("stubs")), classesDirs = listOf(mainOut)),
                        SourceSetModel(SourceSetId(app, "test"), sourceDirs = listOf(testSrc), classesDirs = listOf(testOut)),
                    ),
                    annotationProcessors = listOf("org.mapstruct:mapstruct-processor:1.6.3"),
                ),
            ),
        )
    }

    private fun analyze(change: (Map<String, String>) -> Map<String, String>): Pair<List<SemanticDelta>, ImpactResult> {
        val engine = JDeltaEngine()
        val baseModel = compile(Files.createTempDirectory("fw-base"), main)
        val headModel = compile(Files.createTempDirectory("fw-head"), change(main))
        val base = engine.snapshot(baseModel)
        val head = engine.snapshot(headModel)
        val diff = engine.diff(base, head, headModel)
        return diff.deltas to engine.impact(diff, base, head, headModel, traces)
    }

    private fun Map<String, String>.edit(path: String, from: String, to: String) = this + (path to getValue(path).replace(from, to).also { check(it != getValue(path)) })

    private fun ImpactResult.ranked() = plan.items.filterIsInstance<WorkItem.RunTestsFirst>().single().tests

    @Test
    fun `spring bean API change includes context tests conservatively`() {
        val (deltas, impact) = analyze { it.edit("com/acme/PriceService.java", "public long price() { return 1; }", "public long price() { return 1; } public long discount() { return 0; }") }
        val delta = deltas.single { it.kind == DeltaKind.METHOD_ADDED }
        assertThat(delta.frameworkImpact).isEqualTo(ImpactLevel.RUNTIME)
        assertThat(delta.reasons.map { it.code }).contains(ReasonCode.FRAMEWORK_PROFILE_MATCH)
        assertThat(delta.reasons.first { it.code == ReasonCode.FRAMEWORK_PROFILE_MATCH }.message).startsWith("spring-core: @Service")
        val context = impact.ranked().single { it.test == test("ContextTest", "contextLoads") }
        assertThat(context.confidence).isEqualTo(Confidence.CONSERVATIVE_UNKNOWN)
        assertThat(context.reasons.first().code).isEqualTo(ReasonCode.FRAMEWORK_PROFILE_MATCH)
        assertThat(impact.ranked().map { it.test }).doesNotContain(test("PlainTest", "plain"))
    }

    @Test
    fun `endpoint change includes web slice tests`() {
        val (_, impact) = analyze { it.edit("com/acme/PriceController.java", "@org.springframework.web.bind.annotation.GetMapping(\"/price\")", "@org.springframework.web.bind.annotation.GetMapping(\"/prices\")") }
        assertThat(impact.ranked().map { it.test }).contains(test("PriceControllerWebTest", "get")).doesNotContain(test("ContextTest", "contextLoads"))
    }

    @Test
    fun `jackson property rename prefers serialization tests`() {
        val (deltas, impact) = analyze { it.edit("com/acme/OrderJson.java", "JsonProperty(\"sku\")", "JsonProperty(\"code\")") }
        assertThat(deltas.single().frameworkImpact).isEqualTo(ImpactLevel.RUNTIME)
        val json = impact.ranked().single { it.test == test("OrderJsonTest", "serializes") }
        assertThat(json.confidence).isEqualTo(Confidence.INFERRED)
        assertThat(json.reasons.first().message).contains("jackson")
    }

    @Test
    fun `enum reorder persisted by ordinal is flagged`() {
        val (deltas, _) = analyze { it.edit("com/acme/Status.java", "NEW, PAID", "PAID, NEW") }
        val delta = deltas.single { it.kind == DeltaKind.ENUM_CONSTANT_ORDER_CHANGED }
        assertThat(delta.reasons.single { it.code == ReasonCode.FRAMEWORK_PROFILE_MATCH }.message).contains("persisted by ordinal").contains("com.acme.OrderEntity.status")
    }

    @Test
    fun `processor input change includes tests of generated code conservatively (scenario 6)`() {
        val (deltas, impact) = analyze { it.edit("com/acme/OrderJson.java", "public String sku;", "public String sku; public int quantity;") }
        val delta = deltas.single { it.kind == DeltaKind.FIELD_ADDED }
        val reason = delta.reasons.single { it.code == ReasonCode.PROCESSOR_SENSITIVE_ANNOTATION }
        assertThat(reason.message).contains("com.acme.OrderMapper").contains("until ap-trace exists")
        val mapper = impact.ranked().single { it.test == test("MapperTest", "maps") }
        assertThat(mapper.confidence).isEqualTo(Confidence.CONSERVATIVE_UNKNOWN)
        assertThat(mapper.reasons.map { it.code }).contains(ReasonCode.PROCESSOR_SENSITIVE_ANNOTATION)
    }
}
