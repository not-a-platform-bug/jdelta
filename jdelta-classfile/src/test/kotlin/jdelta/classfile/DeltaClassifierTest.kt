package jdelta.classfile

import jdelta.classfile.JavaFixture.MODULE
import jdelta.classfile.JavaFixture.diff
import jdelta.core.ClassId
import jdelta.core.Confidence
import jdelta.core.DeltaKind
import jdelta.core.ImpactLevel
import jdelta.core.ReasonCode
import jdelta.core.SemanticDelta
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class DeltaClassifierTest {
    private val calc = ClassId(MODULE, "com/acme/PriceCalculator")
    private val path = "com/acme/PriceCalculator.java"

    private fun List<SemanticDelta>.kinds() = map { it.subject.canonical.substringAfter('|') to it.kind }

    @Test
    fun `identical sources produce no delta`() {
        val src = """
            package com.acme;
            public class PriceCalculator { public long calculate(int qty) { return qty * 100L; } }
        """
        assertThat(diff(path, src, src)).isEmpty()
    }

    @Test
    fun `comments, blank lines and moved lines do not change anything`() {
        val before = """
            package com.acme;
            public class PriceCalculator {
                public long calculate(int qty) { return qty * 100L; }
                public long discount(long price) { return price / 10; }
            }
        """
        val after = """
            package com.acme;

            /** Calculates prices. */
            public class PriceCalculator {
                // comment pushes every line down


                public long calculate(int qty) {
                    return qty * 100L;
                }

                public long discount(long price) { return price / 10; }
            }
        """
        assertThat(diff(path, before, after)).isEmpty()
    }

    @Test
    fun `public method body change is body-only with no compile or binary impact`() {
        val deltas = diff(
            path,
            """
            package com.acme;
            public class PriceCalculator { public long calculate(int qty) { return qty * 100L; } }
            """,
            """
            package com.acme;
            public class PriceCalculator { public long calculate(int qty) { return qty * 120L; } }
            """,
        )
        val delta = deltas.single()
        assertThat(delta.subject).isEqualTo(calc.method("calculate", "(I)J"))
        assertThat(delta.kind).isEqualTo(DeltaKind.METHOD_BODY_CHANGED)
        assertThat(delta.compileImpact).isEqualTo(ImpactLevel.NONE)
        assertThat(delta.binaryImpact).isEqualTo(ImpactLevel.NONE)
        assertThat(delta.testImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(delta.confidence).isEqualTo(Confidence.EXACT)
        assertThat(delta.reasons.map { it.code }).containsExactly(ReasonCode.BODY_ONLY)
    }

    @Test
    fun `private method body change touches only that method`() {
        val deltas = diff(
            path,
            """
            package com.acme;
            public class PriceCalculator {
                public long calculate(int qty) { return base(qty); }
                private long base(int qty) { return qty * 100L; }
            }
            """,
            """
            package com.acme;
            public class PriceCalculator {
                public long calculate(int qty) { return base(qty); }
                private long base(int qty) { return qty * 99L; }
            }
            """,
        )
        assertThat(deltas.kinds()).containsExactly("com/acme/PriceCalculator#base(I)J" to DeltaKind.METHOD_BODY_CHANGED)
    }

    @Test
    fun `public descriptor change is downstream compile and binary`() {
        val deltas = diff(
            path,
            """
            package com.acme;
            public class PriceCalculator { public long calculate(int qty) { return qty; } }
            """,
            """
            package com.acme;
            public class PriceCalculator { public long calculate(long qty) { return qty; } }
            """,
        )
        val delta = deltas.single()
        assertThat(delta.kind).isEqualTo(DeltaKind.METHOD_DESCRIPTOR_CHANGED)
        assertThat(delta.subject).isEqualTo(calc.method("calculate", "(J)J"))
        assertThat(delta.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(delta.binaryImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(delta.reasons.first().evidence).containsExactly(calc.method("calculate", "(I)J"))
    }

    @Test
    fun `package-private descriptor change stays local`() {
        val deltas = diff(
            path,
            """
            package com.acme;
            public class PriceCalculator { long calculate(int qty) { return qty; } }
            """,
            """
            package com.acme;
            public class PriceCalculator { long calculate(long qty) { return qty; } }
            """,
        )
        val delta = deltas.single()
        assertThat(delta.compileImpact).isEqualTo(ImpactLevel.LOCAL)
        assertThat(delta.reasons.map { it.code }).contains(ReasonCode.PACKAGE_PRIVATE_SCOPE)
    }

    @Test
    fun `adding an overload is an inferred downstream compile risk but not binary`() {
        val deltas = diff(
            path,
            """
            package com.acme;
            public class PriceCalculator { public long calculate(int qty) { return qty; } }
            """,
            """
            package com.acme;
            public class PriceCalculator {
                public long calculate(int qty) { return qty; }
                public long calculate(long qty) { return qty; }
            }
            """,
        )
        val delta = deltas.single()
        assertThat(delta.kind).isEqualTo(DeltaKind.METHOD_ADDED)
        assertThat(delta.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(delta.binaryImpact).isEqualTo(ImpactLevel.NONE)
        assertThat(delta.confidence).isEqualTo(Confidence.INFERRED)
    }

    @Test
    fun `abstract method added to an interface breaks implementations`() {
        val deltas = diff(
            "com/acme/Pricing.java",
            "package com.acme; public interface Pricing { long price(); }",
            "package com.acme; public interface Pricing { long price(); long tax(); }",
        )
        val delta = deltas.single()
        assertThat(delta.kind).isEqualTo(DeltaKind.METHOD_ADDED)
        assertThat(delta.binaryImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(delta.reasons.map { it.code }).contains(ReasonCode.ABSTRACT_METHOD_ADDED)
    }

    @Test
    fun `default method added to an interface is not binary breaking`() {
        val deltas = diff(
            "com/acme/Pricing.java",
            "package com.acme; public interface Pricing { long price(); }",
            "package com.acme; public interface Pricing { long price(); default long tax() { return 0; } }",
        )
        assertThat(deltas.single().binaryImpact).isEqualTo(ImpactLevel.NONE)
    }

    @Test
    fun `compile-time constant change is detected even though the descriptor is unchanged`() {
        val timeouts = "com/acme/Timeouts.java"
        val deltas = diff(
            mapOf(
                timeouts to "package com.acme; public class Timeouts { public static final long DEFAULT_MS = 1000L; }",
                "com/acme/Client.java" to "package com.acme; public class Client { long timeout() { return Timeouts.DEFAULT_MS; } }",
            ),
            mapOf(
                timeouts to "package com.acme; public class Timeouts { public static final long DEFAULT_MS = 3000L; }",
                "com/acme/Client.java" to "package com.acme; public class Client { long timeout() { return Timeouts.DEFAULT_MS; } }",
            ),
        )
        // Client는 source가 그대로지만 inline된 값 때문에 body가 바뀐다. 이것이 constant inlining의 실체다.
        assertThat(deltas.kinds()).containsExactly(
            "com/acme/Client#timeout()J" to DeltaKind.METHOD_BODY_CHANGED,
            "com/acme/Timeouts.DEFAULT_MS:J" to DeltaKind.CONSTANT_VALUE_CHANGED,
        )
        val constant = deltas.single { it.kind == DeltaKind.CONSTANT_VALUE_CHANGED }
        assertThat(constant.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(constant.binaryImpact).isEqualTo(ImpactLevel.RUNTIME)
        assertThat(constant.reasons.first().code).isEqualTo(ReasonCode.CONSTANT_INLINED_AT_CALLERS)
        assertThat(constant.reasons.first().message).contains("1000 -> 3000")
    }

    @Test
    fun `annotation value change has reflection and framework impact`() {
        val ann = "com/acme/Transactional.java" to """
            package com.acme;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Transactional { boolean readOnly() default false; }
        """
        val deltas = diff(
            mapOf(ann, "com/acme/OrderService.java" to "package com.acme; public class OrderService { @Transactional(readOnly = true) public void place() {} }"),
            mapOf(ann, "com/acme/OrderService.java" to "package com.acme; public class OrderService { @Transactional public void place() {} }"),
        )
        val delta = deltas.single()
        assertThat(delta.kind).isEqualTo(DeltaKind.METHOD_ANNOTATION_CHANGED)
        assertThat(delta.compileImpact).isEqualTo(ImpactLevel.LOCAL)
        assertThat(delta.reflectionImpact).isEqualTo(ImpactLevel.LOCAL)
        assertThat(delta.frameworkImpact).isEqualTo(ImpactLevel.RUNTIME)
        assertThat(delta.confidence).isEqualTo(Confidence.INFERRED)
        assertThat(delta.reasons.map { it.code }).contains(ReasonCode.RUNTIME_VISIBLE_ANNOTATION)
    }

    @Test
    fun `adding a lambda does not create false deltas for lambdas in other methods`() {
        val before = """
            package com.acme;
            import java.util.function.*;
            public class PriceCalculator {
                public Supplier<String> a() { return () -> "a"; }
                public Supplier<String> b() { return () -> "b"; }
            }
        """
        val after = """
            package com.acme;
            import java.util.function.*;
            public class PriceCalculator {
                public Supplier<String> a() { Runnable r = () -> {}; r.run(); return () -> "a"; }
                public Supplier<String> b() { return () -> "b"; }
            }
        """
        // b의 lambda는 lambda$b$1 -> lambda$b$2로 이름이 밀리지만 b는 바뀌지 않았다
        assertThat(diff(path, before, after).kinds())
            .containsExactly("com/acme/PriceCalculator#a()Ljava/util/function/Supplier;" to DeltaKind.METHOD_BODY_CHANGED)
    }

    @Test
    fun `lambda body change is reported on the enclosing method`() {
        val deltas = diff(
            path,
            """
            package com.acme;
            import java.util.function.*;
            public class PriceCalculator { public Supplier<String> a() { return () -> "a"; } }
            """,
            """
            package com.acme;
            import java.util.function.*;
            public class PriceCalculator { public Supplier<String> a() { return () -> "A"; } }
            """,
        )
        assertThat(deltas.kinds())
            .containsExactly("com/acme/PriceCalculator#a()Ljava/util/function/Supplier;" to DeltaKind.METHOD_BODY_CHANGED)
    }

    @Test
    fun `adding an anonymous class does not create false deltas for anonymous classes in other methods`() {
        val before = """
            package com.acme;
            public class PriceCalculator {
                public Runnable a() { return new Runnable() { public void run() { System.out.println("a"); } }; }
                public Runnable b() { return new Runnable() { public void run() { System.out.println("b"); } }; }
            }
        """
        val after = """
            package com.acme;
            public class PriceCalculator {
                public Runnable a() {
                    new Object() { public String toString() { return "x"; } }.toString();
                    return new Runnable() { public void run() { System.out.println("a"); } };
                }
                public Runnable b() { return new Runnable() { public void run() { System.out.println("b"); } }; }
            }
        """
        val deltas = diff(path, before, after)
        assertThat(deltas.kinds())
            .containsExactly("com/acme/PriceCalculator#a()Ljava/lang/Runnable;" to DeltaKind.METHOD_BODY_CHANGED)
        assertThat(deltas.single().reasons.map { it.code }).contains(ReasonCode.LOCAL_CLASS_CHANGED)
    }

    @Test
    fun `anonymous class body change is reported on the enclosing method`() {
        val deltas = diff(
            path,
            """
            package com.acme;
            public class PriceCalculator { public Runnable a() { return new Runnable() { public void run() { System.out.println("a"); } }; } }
            """,
            """
            package com.acme;
            public class PriceCalculator { public Runnable a() { return new Runnable() { public void run() { System.out.println("A"); } }; } }
            """,
        )
        assertThat(deltas.kinds())
            .containsExactly("com/acme/PriceCalculator#a()Ljava/lang/Runnable;" to DeltaKind.METHOD_BODY_CHANGED)
    }

    @Test
    fun `enum constant reorder changes ordinals`() {
        val deltas = diff(
            "com/acme/Status.java",
            "package com.acme; public enum Status { NEW, PAID, SHIPPED }",
            "package com.acme; public enum Status { NEW, SHIPPED, PAID }",
        )
        val order = deltas.single { it.kind == DeltaKind.ENUM_CONSTANT_ORDER_CHANGED }
        assertThat(order.reflectionImpact).isEqualTo(ImpactLevel.RUNTIME)
        assertThat(order.reasons.single().message).contains("PAID", "SHIPPED")
    }

    @Test
    fun `enum constant appended at the end keeps existing ordinals`() {
        val deltas = diff(
            "com/acme/Status.java",
            "package com.acme; public enum Status { NEW, PAID }",
            "package com.acme; public enum Status { NEW, PAID, SHIPPED }",
        )
        assertThat(deltas.map { it.kind }).doesNotContain(DeltaKind.ENUM_CONSTANT_ORDER_CHANGED)
        assertThat(deltas.map { it.kind }).contains(DeltaKind.FIELD_ADDED)
    }

    @Test
    fun `sealed permits change is detected`() {
        val deltas = diff(
            mapOf(
                "com/acme/Shape.java" to "package com.acme; public sealed interface Shape permits Circle {}",
                "com/acme/Circle.java" to "package com.acme; public final class Circle implements Shape {}",
            ),
            mapOf(
                "com/acme/Shape.java" to "package com.acme; public sealed interface Shape permits Circle, Square {}",
                "com/acme/Circle.java" to "package com.acme; public final class Circle implements Shape {}",
                "com/acme/Square.java" to "package com.acme; public final class Square implements Shape {}",
            ),
        )
        assertThat(deltas.kinds()).contains(
            "com/acme/Shape" to DeltaKind.PERMITTED_SUBCLASSES_CHANGED,
            "com/acme/Square" to DeltaKind.CLASS_ADDED,
        )
        assertThat(deltas.single { it.kind == DeltaKind.PERMITTED_SUBCLASSES_CHANGED }.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
    }

    @Test
    fun `record component change is detected`() {
        val deltas = diff(
            "com/acme/Money.java",
            "package com.acme; public record Money(long amount) {}",
            "package com.acme; public record Money(long amount, String currency) {}",
        )
        assertThat(deltas.map { it.kind }).contains(DeltaKind.RECORD_COMPONENTS_CHANGED)
    }

    @Test
    fun `public method of package-private base is exposed through a public subclass`() {
        fun base(body: String) = "package com.acme; abstract class BaseService { public long fee() { return $body; } public long rate(int x) { return x; } }"
        val sub = "com/acme/OrderService.java" to "package com.acme; public class OrderService extends BaseService {}"
        val deltas = diff(
            mapOf("com/acme/BaseService.java" to base("1"), sub),
            mapOf("com/acme/BaseService.java" to base("1").replace("rate(int x)", "rate(long x)"), sub),
        )
        val delta = deltas.single { it.subject.canonical.contains("BaseService") }
        assertThat(delta.kind).isEqualTo(DeltaKind.METHOD_DESCRIPTOR_CHANGED)
        assertThat(delta.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(delta.reasons.map { it.code }).contains(ReasonCode.EXPOSED_VIA_PUBLIC_SUBCLASS)
        // javac가 public subclass에 만든 visibility bridge도 바뀐다. 기존 caller는 이 bridge에 link되어 있다.
        val bridgeRemoved = deltas.single { it.subject.canonical.endsWith("OrderService#rate(I)J") }
        assertThat(bridgeRemoved.kind).isEqualTo(DeltaKind.METHOD_REMOVED)
        assertThat(bridgeRemoved.binaryImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(bridgeRemoved.reasons.map { it.code }).containsExactly(ReasonCode.BRIDGE_CHANGED)
    }

    @Test
    fun `access narrowing is linkage breaking`() {
        val deltas = diff(
            path,
            "package com.acme; public class PriceCalculator { public void reset() {} }",
            "package com.acme; public class PriceCalculator { protected void reset() {} }",
        )
        val delta = deltas.single()
        assertThat(delta.kind).isEqualTo(DeltaKind.METHOD_ACCESS_CHANGED)
        assertThat(delta.binaryImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(delta.reasons.map { it.code }).contains(ReasonCode.LINKAGE_BREAKING_ACCESS_CHANGE)
    }

    @Test
    fun `throws clause change affects compile only`() {
        val deltas = diff(
            path,
            "package com.acme; public class PriceCalculator { public void load() {} }",
            "package com.acme; public class PriceCalculator { public void load() throws java.io.IOException {} }",
        )
        val delta = deltas.single()
        assertThat(delta.kind).isEqualTo(DeltaKind.METHOD_EXCEPTIONS_CHANGED)
        assertThat(delta.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(delta.binaryImpact).isEqualTo(ImpactLevel.NONE)
        assertThat(delta.testImpact).isEqualTo(ImpactLevel.NONE)
    }

    @Test
    fun `generic signature change without erasure change`() {
        val deltas = diff(
            path,
            "package com.acme; import java.util.*; public class PriceCalculator { public List<String> names() { return null; } }",
            "package com.acme; import java.util.*; public class PriceCalculator { public List<Integer> names() { return null; } }",
        )
        val delta = deltas.single()
        assertThat(delta.kind).isEqualTo(DeltaKind.GENERIC_SIGNATURE_CHANGED)
        assertThat(delta.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(delta.binaryImpact).isEqualTo(ImpactLevel.NONE)
    }

    @Test
    fun `class moved between modules is marked`() {
        val app = ModuleIdFixtures.snapshot(":app", mapOf("com/acme/Util.java" to "package com.acme; public class Util {}"))
        val core = ModuleIdFixtures.snapshot(":core", mapOf("com/acme/Util.java" to "package com.acme; public class Util {}"))
        val empty = ProjectSnapshot(emptyList())
        val deltas = DeltaClassifier().diff(
            ProjectSnapshot(app.sourceSets + ModuleIdFixtures.emptySourceSet(":core")),
            ProjectSnapshot(core.sourceSets + ModuleIdFixtures.emptySourceSet(":app")),
        )
        assertThat(deltas.map { it.kind }).containsExactlyInAnyOrder(DeltaKind.CLASS_REMOVED, DeltaKind.CLASS_ADDED)
        assertThat(deltas.flatMap { d -> d.reasons.map { it.code } }).contains(ReasonCode.CLASS_MOVED)
        assertThat(empty.classCount).isZero()
    }
}

object ModuleIdFixtures {
    fun snapshot(module: String, sources: Map<String, String>): ProjectSnapshot {
        val dir = JavaFixture.compile(sources)
        val id = jdelta.core.SourceSetId(jdelta.core.ModuleId(module), "main")
        return ProjectSnapshot(listOf(SnapshotCollector().collect(jdelta.core.SourceSetModel(id, classesDirs = listOf(dir)))))
    }

    fun emptySourceSet(module: String) = SourceSetSnapshot(jdelta.core.SourceSetId(jdelta.core.ModuleId(module), "main"), emptyMap())
}
