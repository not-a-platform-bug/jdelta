package jdelta.core

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ConfidenceTest {
    @Test
    fun `weakest and strongest form a lattice`() {
        for (a in Confidence.entries) {
            for (b in Confidence.entries) {
                assertThat(a weakest b).isEqualTo(b weakest a)
                assertThat(a strongest b).isEqualTo(b strongest a)
                assertThat((a weakest b).ordinal).isEqualTo(maxOf(a.ordinal, b.ordinal))
                assertThat((a strongest b).ordinal).isEqualTo(minOf(a.ordinal, b.ordinal))
            }
        }
    }

    @Test
    fun `path confidence is the weakest link and conclusion is the strongest path`() {
        val observedPath = Confidence.weakestOf(listOf(Confidence.EXACT, Confidence.OBSERVED))
        val conservativePath = Confidence.weakestOf(listOf(Confidence.EXACT, Confidence.CONSERVATIVE_UNKNOWN))
        assertThat(observedPath).isEqualTo(Confidence.OBSERVED)
        assertThat(Confidence.strongestOf(listOf(observedPath, conservativePath))).isEqualTo(Confidence.OBSERVED)
    }

    @Test
    fun `impact level max follows severity not declaration order`() {
        assertThat(ImpactLevel.DOWNSTREAM max ImpactLevel.RUNTIME).isEqualTo(ImpactLevel.DOWNSTREAM)
        assertThat(ImpactLevel.LOCAL max ImpactLevel.RUNTIME).isEqualTo(ImpactLevel.RUNTIME)
        assertThat(ImpactLevel.maxOf(listOf(ImpactLevel.NONE, ImpactLevel.UNKNOWN, ImpactLevel.LOCAL)))
            .isEqualTo(ImpactLevel.UNKNOWN)
        assertThat(ImpactLevel.maxOf(emptyList())).isEqualTo(ImpactLevel.NONE)
    }
}
