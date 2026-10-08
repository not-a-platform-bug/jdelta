package jdelta.classfile

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class FingerprintTest {
    private val sources = mapOf(
        "com/acme/PriceCalculator.java" to """
            package com.acme;
            import java.util.*;
            import java.util.function.*;
            public class PriceCalculator {
                public static final int SCALE = 2;
                private final Map<String, Long> cache = new HashMap<>();
                public long calculate(List<Integer> items) {
                    long sum = 0;
                    for (int i : items) { if (i > 10) sum += i; else sum -= 1; }
                    try { cache.put("x", sum); } catch (RuntimeException e) { throw new IllegalStateException(e); }
                    Supplier<Long> s = () -> cache.getOrDefault("x", 0L);
                    switch ((int) (sum % 3)) { case 0: return s.get(); case 1: return 1; default: return sum; }
                }
                public Runnable task() { return new Runnable() { public void run() { calculate(List.of()); } }; }
            }
        """,
    )

    @Test
    fun `compiling the same source twice yields identical snapshots`() {
        val a = JavaFixture.snapshot(sources)
        val b = JavaFixture.snapshot(sources)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `debug information does not affect any fingerprint or body hash`() {
        val withDebug = JavaFixture.snapshot(sources, "-g")
        val withoutDebug = JavaFixture.snapshot(sources, "-g:none")
        val fingerprints = { s: ProjectSnapshot -> s.sourceSets.single().classes.mapValues { it.value.fingerprints } }
        val bodies = { s: ProjectSnapshot -> s.sourceSets.single().classes.mapValues { c -> c.value.methods.map { it.key to it.bodyHash } } }
        assertThat(fingerprints(withoutDebug)).isEqualTo(fingerprints(withDebug))
        assertThat(bodies(withoutDebug)).isEqualTo(bodies(withDebug))
        assertThat(DeltaClassifier().diff(withDebug, withoutDebug)).isEmpty()
    }

    @Test
    fun `parameter names from -parameters are reflection-only`() {
        val plain = JavaFixture.snapshot(sources, "-g")
        val withNames = JavaFixture.snapshot(sources, "-g", "-parameters")
        val deltas = DeltaClassifier().diff(plain, withNames)
        assertThat(deltas.map { it.kind }.toSet()).containsOnly(jdelta.core.DeltaKind.METHOD_PARAMETERS_CHANGED)
        assertThat(deltas.map { it.compileImpact }.toSet()).containsOnly(jdelta.core.ImpactLevel.NONE)
    }

    @Test
    fun `lambda bodies are folded and anonymous classes are marked local`() {
        val snapshot = JavaFixture.snapshot(sources).sourceSets.single()
        val calc = snapshot.classes.getValue("com/acme/PriceCalculator")
        val lambda = calc.methods.single { it.role == MethodRole.LAMBDA_BODY }
        assertThat(lambda.foldedInto).isEqualTo("calculate(Ljava/util/List;)J")
        assertThat(calc.localClassNames).containsExactly("com/acme/PriceCalculator$1")
        val anonymous = snapshot.classes.getValue("com/acme/PriceCalculator$1")
        assertThat(anonymous.isLocalOrAnonymous).isTrue()
        assertThat(anonymous.enclosingClassName).isEqualTo("com/acme/PriceCalculator")
        assertThat(anonymous.enclosingMethod?.methodKey).isEqualTo("task()Ljava/lang/Runnable;")
    }

    @Test
    fun `references are collected for the graph`() {
        val calc = JavaFixture.snapshot(sources).sourceSets.single().classes.getValue("com/acme/PriceCalculator")
        val refs = calc.methods.single { it.name == "calculate" }.references
        assertThat(refs.calls).contains("java/util/Map.put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
        assertThat(refs.fieldReads).contains("com/acme/PriceCalculator.cache:Ljava/util/Map;")
        assertThat(refs.types).contains("java/lang/IllegalStateException")
    }

    @Test
    fun `unparseable classfiles are recorded instead of failing`() {
        val dir = JavaFixture.compile(sources)
        java.nio.file.Files.write(dir.resolve("com/acme/Broken.class"), byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0, 0, 0, 99))
        val snapshot = JavaFixture.snapshotOf(dir).sourceSets.single()
        assertThat(snapshot.unparseable).containsKey("com/acme/Broken")
        assertThat(snapshot.classes).containsKey("com/acme/PriceCalculator")
    }
}
