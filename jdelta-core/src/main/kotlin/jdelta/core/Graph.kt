package jdelta.core

public enum class NodeKind {
    PROJECT,
    MODULE,
    SOURCE_SET,
    SOURCE_FILE,
    CLASS,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    ANNOTATION,
    RESOURCE,
    TEST_CLASS,
    TEST_METHOD,
    GRADLE_TASK,

    // 확장
    GENERATED_SOURCE,
    ANNOTATION_PROCESSOR,
    PROCESSOR_OPTION,
    SPRING_BEAN,
    SPRING_ENDPOINT,
    CONFIGURATION_PROPERTY,
    DEPENDENCY,
    RUNTIME_TRACE,
}

public data class Node(
    public val id: NodeId,
    public val kind: NodeKind,
    public val attributes: Map<String, String> = emptyMap(),
)

/** impact가 edge를 따라 흐르는 기본 방향. 실제 traversal은 delta kind별 rule이 정한다. */
public enum class Propagation {
    /** from 변경 -> to 영향 */
    FORWARD,

    /** to 변경 -> from 영향 (A CALLS B에서 B가 바뀌면 A가 영향) */
    BACKWARD,

    /** 기본 전파 없음. rule에서만 사용 */
    NONE,
}

/** edge는 이름 그대로의 방향(`A CALLS B`, `M EXECUTED_BY_TEST T`)으로 저장한다. */
public enum class EdgeKind(public val impactFlow: Propagation) {
    BELONGS_TO(Propagation.BACKWARD),
    DEFINES(Propagation.FORWARD),
    DECLARES(Propagation.FORWARD),
    REFERENCES(Propagation.BACKWARD),
    CALLS(Propagation.BACKWARD),
    READS_FIELD(Propagation.BACKWARD),
    WRITES_FIELD(Propagation.BACKWARD),
    EXTENDS(Propagation.BACKWARD),
    IMPLEMENTS(Propagation.BACKWARD),
    OVERRIDES(Propagation.BACKWARD),
    ANNOTATED_BY(Propagation.NONE),
    COMPILED_IN_MODULE(Propagation.FORWARD),
    EXECUTED_BY_TEST(Propagation.FORWARD),
    DEPENDS_ON_MODULE(Propagation.BACKWARD),
    INLINED_INTO(Propagation.FORWARD),
    TASK_INPUT(Propagation.FORWARD),
    TASK_OUTPUT(Propagation.BACKWARD),

    // 확장
    GENERATED_FROM(Propagation.BACKWARD),
    GENERATED_BY(Propagation.BACKWARD),
    CONSUMED_BY_PROCESSOR(Propagation.FORWARD),
    READS_RESOURCE(Propagation.BACKWARD),
    WRITES_RESOURCE(Propagation.FORWARD),
    CREATES_BEAN(Propagation.FORWARD),
    INJECTS_BEAN(Propagation.BACKWARD),
    HANDLES_ENDPOINT(Propagation.FORWARD),
    BINDS_CONFIGURATION(Propagation.BACKWARD),
    CONDITIONAL_ON(Propagation.BACKWARD),
    PROXIED_BY(Propagation.FORWARD),
}

public data class Edge(
    public val from: NodeId,
    public val to: NodeId,
    public val kind: EdgeKind,
    public val confidence: Confidence,
    public val attributes: Map<String, String> = emptyMap(),
) {
    public fun other(id: NodeId): NodeId = if (id == from) to else from
}

public interface SemanticGraph {
    public fun node(id: NodeId): Node?

    public fun outgoing(id: NodeId, kinds: Set<EdgeKind> = ALL_EDGE_KINDS): Sequence<Edge>

    public fun incoming(id: NodeId, kinds: Set<EdgeKind> = ALL_EDGE_KINDS): Sequence<Edge>

    public fun nodes(kind: NodeKind): Sequence<Node>

    public val nodeCount: Int

    public val edgeCount: Int

    public companion object {
        public val ALL_EDGE_KINDS: Set<EdgeKind> = EdgeKind.entries.toSet()
    }
}

/**
 * 실행마다 새로 구성하는 불변 graph (ARCHITECTURE.md D1).
 * 같은 (from, to, kind) edge가 여러 번 들어오면 가장 강한 confidence 하나만 남긴다.
 */
public class GraphBuilder {
    private val nodes = LinkedHashMap<NodeId, Node>()
    private val edges = LinkedHashMap<Triple<NodeId, NodeId, EdgeKind>, Edge>()

    public fun add(node: Node): GraphBuilder = apply {
        val existing = nodes[node.id]
        nodes[node.id] = if (existing == null) node else existing.copy(attributes = existing.attributes + node.attributes)
    }

    public fun add(edge: Edge): GraphBuilder = apply {
        val key = Triple(edge.from, edge.to, edge.kind)
        val existing = edges[key]
        if (existing == null || edge.confidence.ordinal < existing.confidence.ordinal) edges[key] = edge
    }

    public fun build(): SemanticGraph = IndexedGraph(nodes.toMap(), edges.values.toList())
}

private class IndexedGraph(
    private val nodesById: Map<NodeId, Node>,
    edges: List<Edge>,
) : SemanticGraph {
    private val outgoingByKind: Map<EdgeKind, Map<NodeId, List<Edge>>> =
        edges.groupBy { it.kind }.mapValues { (_, list) -> list.groupBy { it.from } }
    private val incomingByKind: Map<EdgeKind, Map<NodeId, List<Edge>>> =
        edges.groupBy { it.kind }.mapValues { (_, list) -> list.groupBy { it.to } }
    private val nodesByKind: Map<NodeKind, List<Node>> = nodesById.values.groupBy { it.kind }

    override val nodeCount: Int = nodesById.size
    override val edgeCount: Int = edges.size

    override fun node(id: NodeId): Node? = nodesById[id]

    override fun outgoing(id: NodeId, kinds: Set<EdgeKind>): Sequence<Edge> =
        kinds.asSequence().flatMap { outgoingByKind[it]?.get(id).orEmpty() }

    override fun incoming(id: NodeId, kinds: Set<EdgeKind>): Sequence<Edge> =
        kinds.asSequence().flatMap { incomingByKind[it]?.get(id).orEmpty() }

    override fun nodes(kind: NodeKind): Sequence<Node> = nodesByKind[kind].orEmpty().asSequence()
}
