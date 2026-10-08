package jdelta.core

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test

class NodeIdTest {
    private val app = ModuleId(":app")
    private val calculator = ClassId(app, "com/acme/PriceCalculator")

    @Test
    fun `canonical forms match the documented format`() {
        assertThat(app.canonical).isEqualTo(":app")
        assertThat(SourceSetId(app, "main").canonical).isEqualTo(":app@main")
        assertThat(calculator.canonical).isEqualTo(":app|com/acme/PriceCalculator")
        assertThat(calculator.method("calculate", "(Lcom/acme/Order;)J").canonical)
            .isEqualTo(":app|com/acme/PriceCalculator#calculate(Lcom/acme/Order;)J")
        assertThat(ClassId(app, "com/acme/Timeouts").field("DEFAULT_MS", "J").canonical)
            .isEqualTo(":app|com/acme/Timeouts.DEFAULT_MS:J")
        assertThat(ResourceId(SourceSetId(app, "main"), "application.yml").canonical)
            .isEqualTo(":app@main/application.yml")
        assertThat(TestId("junit-jupiter", "com.acme.PriceCalculatorTest", "calculatesDiscount").canonical)
            .isEqualTo("junit-jupiter:com.acme.PriceCalculatorTest#calculatesDiscount()")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            ":",
            ":app",
            ":services:billing",
            ":app@main",
            ":app@test",
            ":app@main/application.yml",
            ":app@main/META-INF/spring/x.imports",
            ":app|com/acme/Foo",
            ":app|com/acme/Foo\$Bar",
            ":|Foo",
            ":app|com/acme/Foo#calculate(Lcom/acme/Order;)J",
            ":app|com/acme/Foo#<init>()V",
            ":app|com/acme/Foo#<clinit>()V",
            ":app|com/acme/Foo.DEFAULT_MS:J",
            ":app|com/acme/Foo.items:Ljava/util/List;",
            ":app!compileJava",
            "//build.gradle.kts",
            "//app/src/main/java/com/acme/Foo.java",
            "junit-jupiter:com.acme.FooTest",
            "junit-jupiter:com.acme.FooTest#works()",
            "junit-jupiter:com.acme.FooTest\$Nested#works(java.lang.String, int)",
        ],
    )
    fun `canonical round-trips through parse`(canonical: String) {
        val parsed = NodeId.parse(canonical)
        assertThat(parsed.canonical).isEqualTo(canonical)
    }

    @Test
    fun `parse yields the expected types`() {
        assertThat(NodeId.parse(":app")).isEqualTo(app)
        assertThat(NodeId.parse(":app|com/acme/Foo.X:I")).isEqualTo(FieldId(ClassId(app, "com/acme/Foo"), "X", "I"))
        assertThat(NodeId.parse(":app|com/acme/Foo#<init>()V"))
            .isEqualTo(MethodId(ClassId(app, "com/acme/Foo"), "<init>", "()V"))
        assertThat(NodeId.parse("junit-jupiter:com.acme.FooTest#works(int)"))
            .isEqualTo(TestId("junit-jupiter", "com.acme.FooTest", "works", "int"))
    }

    @Test
    fun `invalid ids are rejected`() {
        assertThatThrownBy { ModuleId("app") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ModuleId(":a|b") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NodeId.parse(":app|com/acme/Foo#broken") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NodeId.parse("nonsense") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
