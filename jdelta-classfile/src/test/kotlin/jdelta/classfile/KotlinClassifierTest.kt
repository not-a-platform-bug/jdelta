package jdelta.classfile

import jdelta.classfile.KotlinFixture.diff
import jdelta.core.Confidence
import jdelta.core.DeltaKind
import jdelta.core.ImpactLevel
import jdelta.core.ReasonCode
import jdelta.core.SemanticDelta
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

/** Kotlin v0.1 규칙 (ARCHITECTURE.md §3.6). */
class KotlinClassifierTest {
    private val path = "com/acme/Price.kt"

    private fun List<SemanticDelta>.kinds() = map { it.subject.canonical.substringAfter('|') to it.kind }

    private fun List<SemanticDelta>.single(kind: DeltaKind) = single { it.kind == kind }

    @Test
    fun `identical Kotlin sources with lambdas, when and data classes produce no delta`() {
        val src = """
            package com.acme
            enum class Color { RED, GREEN }
            data class Price(val amount: Long, val color: Color = Color.RED) {
                fun label(): String = when (color) { Color.RED -> "r"; Color.GREEN -> "g" }
                fun doubled(items: List<Int>): List<Int> = items.map { it * 2 }.filter { it > amount }
                val lazyName by lazy { "price" }
            }
        """
        assertThat(diff(path, src, src)).isEmpty()
    }

    @Test
    fun `moving lines in a file that uses inline functions produces no delta`() {
        val before = """
            package com.acme
            class Price(val items: List<Int>) {
                fun total(): Int = items.map { it * 2 }.sum()
            }
        """
        val after = """
            package com.acme

            // comment lines shift the SMAP line table of inlined stdlib code


            class Price(val items: List<Int>) {
                fun total(): Int =
                    items.map { it * 2 }.sum()
            }
        """
        assertThat(diff(path, before, after)).isEmpty()
    }

    @Test
    fun `body change of a regular function is body-only without metadata change`() {
        val deltas = diff(
            path,
            "package com.acme\nclass Price { fun calc(q: Int): Long = q * 100L }",
            "package com.acme\nclass Price { fun calc(q: Int): Long = q * 110L }",
        )
        assertThat(deltas.kinds()).containsExactly("com/acme/Price#calc(I)J" to DeltaKind.METHOD_BODY_CHANGED)
    }

    @Test
    fun `nullability change is a Kotlin-only compile change`() {
        val deltas = diff(
            path,
            "package com.acme\nclass Price { fun note(): String = \"x\" }",
            "package com.acme\nclass Price { fun note(): String? = \"x\" }",
        )
        val metadata = deltas.single(DeltaKind.KOTLIN_METADATA_CHANGED)
        assertThat(metadata.subject.canonical).endsWith("#note()Ljava/lang/String;")
        assertThat(metadata.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(metadata.binaryImpact).isEqualTo(ImpactLevel.NONE)
        assertThat(metadata.confidence).isEqualTo(Confidence.INFERRED)
        assertThat(metadata.reasons.map { it.code }).contains(ReasonCode.KOTLIN_CALLERS_ONLY)
        assertThat(metadata.reasons.first().message).isEqualTo("public final fun note(): kotlin/String -> public final fun note(): kotlin/String?")
        // kotlinc가 붙이는 CLASS retention @NotNull/@Nullable도 바뀐다
        assertThat(deltas.map { it.kind }).contains(DeltaKind.METHOD_ANNOTATION_CHANGED).doesNotContain(DeltaKind.METHOD_BODY_CHANGED)
    }

    @Test
    fun `parameter rename breaks Kotlin named arguments`() {
        val deltas = diff(
            path,
            "package com.acme\nclass Price { fun calc(qty: Int): Long = qty * 100L }",
            "package com.acme\nclass Price { fun calc(count: Int): Long = count * 100L }",
        )
        val metadata = deltas.single(DeltaKind.KOTLIN_METADATA_CHANGED)
        assertThat(metadata.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(metadata.reasons.first().message).contains("qty: kotlin/Int").contains("count: kotlin/Int")
    }

    @Test
    fun `adding a default value adds a synthetic default method that only compiled callers see`() {
        val deltas = diff(
            path,
            "package com.acme\nclass Price { fun calc(qty: Int): Long = qty * 100L }",
            "package com.acme\nclass Price { fun calc(qty: Int = 1): Long = qty * 100L }",
        )
        val added = deltas.single(DeltaKind.METHOD_ADDED)
        assertThat(added.subject.canonical).contains("calc\$default")
        assertThat(added.compileImpact).isEqualTo(ImpactLevel.NONE)
        assertThat(added.reasons.map { it.code }).contains(ReasonCode.SYNTHETIC_MEMBER)
        assertThat(deltas.single(DeltaKind.KOTLIN_METADATA_CHANGED).reasons.first().message).contains("= default")
    }

    @Test
    fun `changing a default value changes the synthetic default method body`() {
        val deltas = diff(
            path,
            "package com.acme\nclass Price { fun calc(qty: Int = 1): Long = qty * 100L }",
            "package com.acme\nclass Price { fun calc(qty: Int = 2): Long = qty * 100L }",
        )
        assertThat(deltas.kinds()).containsExactly(
            "com/acme/Price#calc\$default(Lcom/acme/Price;IILjava/lang/Object;)J" to DeltaKind.METHOD_BODY_CHANGED,
        )
    }

    @Test
    fun `internal members are module-local even though they are public on the JVM`() {
        val deltas = diff(
            path,
            "package com.acme\nclass Price { internal fun calc(qty: Int): Long = qty * 100L }",
            "package com.acme\nclass Price { internal fun calc(qty: Long): Long = qty * 100L }",
        )
        val changed = deltas.single(DeltaKind.METHOD_DESCRIPTOR_CHANGED)
        assertThat(changed.compileImpact).isEqualTo(ImpactLevel.LOCAL)
        assertThat(changed.binaryImpact).isEqualTo(ImpactLevel.LOCAL)
    }

    @Test
    fun `internal class members are module-local`() {
        val deltas = diff(
            path,
            "package com.acme\ninternal class Price { fun calc(qty: Int): Long = qty * 100L }",
            "package com.acme\ninternal class Price { fun calc(qty: Int): Long = qty * 100L\n fun extra() = 1 }",
        )
        assertThat(deltas.single(DeltaKind.METHOD_ADDED).compileImpact).isEqualTo(ImpactLevel.LOCAL)
    }

    @Test
    fun `public inline function body change is promoted and reaches compiled callers`() {
        val util = "com/acme/Util.kt"
        val caller = "package com.acme\nclass Price { fun doubled(): Int = twice { 21 } }"
        val deltas = diff(
            mapOf(util to "package com.acme\ninline fun twice(block: () -> Int): Int = block() + block()", path to caller),
            mapOf(util to "package com.acme\ninline fun twice(block: () -> Int): Int = block() * 2", path to caller),
        )
        val inline = deltas.single(DeltaKind.KOTLIN_INLINE_BODY_CHANGED)
        assertThat(inline.subject.canonical).endsWith("com/acme/UtilKt#twice(Lkotlin/jvm/functions/Function0;)I")
        assertThat(inline.compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(inline.binaryImpact).isEqualTo(ImpactLevel.RUNTIME)
        assertThat(inline.confidence).isEqualTo(Confidence.EXACT)
        // 같은 module의 caller는 다시 inline되어 body가 바뀐다
        assertThat(deltas.kinds()).contains("com/acme/Price#doubled()I" to DeltaKind.METHOD_BODY_CHANGED)
    }

    @Test
    fun `internal inline function body change is local`() {
        val deltas = diff(
            path,
            "package com.acme\nclass Price { internal inline fun calc(q: Int): Long = q * 100L }",
            "package com.acme\nclass Price { internal inline fun calc(q: Int): Long = q * 110L }",
        )
        assertThat(deltas.single(DeltaKind.KOTLIN_INLINE_BODY_CHANGED).compileImpact).isEqualTo(ImpactLevel.LOCAL)
    }

    @Test
    fun `inline call sites are recorded from SMAP and inline markers`() {
        val snapshot = KotlinFixture.snapshot(
            mapOf(
                "com/acme/Util.kt" to "package com.acme\ninline fun twice(block: () -> Int): Int = block() + block()",
                path to "package com.acme\nclass Price {\n fun doubled(): Int = twice { 21 }\n fun plain(): Int = 1\n}",
            ),
        )
        val classes = snapshot.sourceSets.single().classes
        val price = classes.getValue("com/acme/Price")
        assertThat(price.methods.single { it.name == "doubled" }.references.inlinedFunctions).containsExactly("com/acme/UtilKt.twice")
        assertThat(price.methods.single { it.name == "plain" }.references.inlinedFunctions).isEmpty()
        // inline function 자신의 marker는 call site가 아니다
        assertThat(classes.getValue("com/acme/UtilKt").methods.single { it.name == "twice" }.references.inlinedFunctions).isEmpty()
    }

    @Test
    fun `const val change follows the Java constant rule and companion internal const is local`() {
        val deltas = diff(
            path,
            "package com.acme\nclass Price { companion object { const val MAX = 10\n internal const val MIN = 1 } }",
            "package com.acme\nclass Price { companion object { const val MAX = 20\n internal const val MIN = 2 } }",
        )
        val constants = deltas.filter { it.kind == DeltaKind.CONSTANT_VALUE_CHANGED }.associateBy { it.subject.canonical.substringAfter('.') }
        assertThat(constants.getValue("MAX:I").compileImpact).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(constants.getValue("MAX:I").binaryImpact).isEqualTo(ImpactLevel.RUNTIME)
        assertThat(constants.getValue("MIN:I").compileImpact).isEqualTo(ImpactLevel.LOCAL)
    }

    @Test
    fun `adding a when over an enum does not report the synthetic WhenMappings class`() {
        val enum = "com/acme/Color.kt" to "package com.acme\nenum class Color { RED, GREEN }"
        val deltas = diff(
            mapOf(enum, path to "package com.acme\nclass Price { fun label(c: Color): String = c.name }"),
            mapOf(enum, path to "package com.acme\nclass Price { fun label(c: Color): String = when (c) { Color.RED -> \"r\"; Color.GREEN -> \"g\" } }"),
        )
        assertThat(deltas.map { it.subject.canonical }).noneMatch { it.contains("WhenMappings") }
        assertThat(deltas.kinds()).contains("com/acme/Price#label(Lcom/acme/Color;)Ljava/lang/String;" to DeltaKind.METHOD_BODY_CHANGED)
    }

    @Test
    fun `adding an enum entry breaks exhaustive when in Kotlin callers`() {
        val deltas = diff(
            "com/acme/Color.kt",
            "package com.acme\nenum class Color { RED, GREEN }",
            "package com.acme\nenum class Color { RED, GREEN, BLUE }",
        )
        assertThat(deltas.kinds()).contains(
            "com/acme/Color.BLUE:Lcom/acme/Color;" to DeltaKind.FIELD_ADDED,
            "com/acme/Color" to DeltaKind.KOTLIN_METADATA_CHANGED,
        )
    }

    @Test
    fun `type alias change is reported on the file facade`() {
        val deltas = diff(
            "com/acme/Ids.kt",
            "package com.acme\ntypealias Id = Long\nfun parse(s: String): Id = s.toLong()",
            "package com.acme\ntypealias Id = Long\ntypealias Name = String\nfun parse(s: String): Id = s.toLong()",
        )
        val added = deltas.single(DeltaKind.KOTLIN_METADATA_CHANGED)
        assertThat(added.subject.canonical).endsWith("com/acme/IdsKt")
        assertThat(added.reasons.first().message).contains("typealias Name = kotlin/String")
    }

    @Test
    fun `data class property addition is explained without unknown deltas`() {
        val deltas = diff(
            path,
            "package com.acme\ndata class Price(val amount: Long)",
            "package com.acme\ndata class Price(val amount: Long, val currency: String = \"KRW\")",
        )
        assertThat(deltas.map { it.kind }).contains(DeltaKind.METHOD_DESCRIPTOR_CHANGED, DeltaKind.METHOD_ADDED, DeltaKind.FIELD_ADDED)
    }

    @Test
    fun `private Kotlin changes have no compile impact`() {
        val deltas = diff(
            path,
            "package com.acme\nclass Price { private fun calc(q: Int): Long = q * 100L\n fun total() = calc(1) }",
            "package com.acme\nclass Price { private fun calc(q: Int): Long? = q * 100L\n fun total() = calc(1) }",
        )
        assertThat(deltas.filter { it.subject.canonical.contains("calc") }.map { it.compileImpact }).containsOnly(ImpactLevel.NONE)
    }
}
