package jdelta.core

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class GraphTest {
    private val app = ModuleId(":app")
    private val caller = ClassId(app, "a/Caller").method("run", "()V")
    private val callee = ClassId(app, "a/Callee").method("work", "()V")
    private val test = TestId("junit-jupiter", "a.CallerTest", "runs")

    @Test
    fun `edges are indexed in both directions by kind`() {
        val graph = GraphBuilder()
            .add(Node(caller, NodeKind.METHOD))
            .add(Node(callee, NodeKind.METHOD))
            .add(Node(test, NodeKind.TEST_METHOD))
            .add(Edge(caller, callee, EdgeKind.CALLS, Confidence.EXACT))
            .add(Edge(callee, test, EdgeKind.EXECUTED_BY_TEST, Confidence.OBSERVED))
            .build()

        assertThat(graph.incoming(callee, setOf(EdgeKind.CALLS)).map { it.from }.toList()).containsExactly(caller)
        assertThat(graph.outgoing(callee, setOf(EdgeKind.EXECUTED_BY_TEST)).map { it.to }.toList()).containsExactly(test)
        assertThat(graph.outgoing(callee, setOf(EdgeKind.CALLS)).toList()).isEmpty()
        assertThat(graph.nodes(NodeKind.METHOD).count()).isEqualTo(2)
    }

    @Test
    fun `duplicate edges keep the strongest confidence`() {
        val graph = GraphBuilder()
            .add(Edge(callee, test, EdgeKind.EXECUTED_BY_TEST, Confidence.INFERRED))
            .add(Edge(callee, test, EdgeKind.EXECUTED_BY_TEST, Confidence.OBSERVED))
            .add(Edge(callee, test, EdgeKind.EXECUTED_BY_TEST, Confidence.CONSERVATIVE_UNKNOWN))
            .build()

        assertThat(graph.edgeCount).isEqualTo(1)
        assertThat(graph.outgoing(callee).single().confidence).isEqualTo(Confidence.OBSERVED)
    }
}
