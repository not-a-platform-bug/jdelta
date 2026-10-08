package jdelta.engine

import jdelta.classfile.ClassSnapshot
import jdelta.classfile.MethodRole
import jdelta.classfile.ProjectSnapshot
import jdelta.core.ClassId
import jdelta.core.Confidence
import jdelta.core.Edge
import jdelta.core.EdgeKind
import jdelta.core.FieldId
import jdelta.core.GraphBuilder
import jdelta.core.MethodId
import jdelta.core.ModuleId
import jdelta.core.Node
import jdelta.core.NodeKind
import jdelta.core.ProjectModel
import jdelta.core.ReasonCode
import jdelta.core.SemanticDelta
import jdelta.core.SemanticGraph
import jdelta.core.TestId
import jdelta.trace.TraceStore
import jdelta.trace.classLevel

/**
 * snapshot, trace store, project model로 [SemanticGraph]를 만든다 (ARCHITECTURE.md §6.1, D1).
 *
 * code edge는 base와 head snapshot을 합쳐서 만든다. 삭제되거나 descriptor가 바뀐 member는 head에 없지만
 * trace와 옛 caller는 그것을 가리키기 때문이다.
 */
internal class GraphFactory(
    private val base: ProjectSnapshot,
    private val head: ProjectSnapshot,
    private val model: ProjectModel,
    private val traces: TraceStore,
    private val deltas: List<SemanticDelta>,
    private val facts: FrameworkFacts,
) {
    private val builder = GraphBuilder()
    private val headIndex = ClassIndex(head)
    private val baseIndex = ClassIndex(base)

    fun build(): SemanticGraph {
        modules()
        code(base, baseIndex)
        code(head, headIndex)
        frameworkAttributes()
        inlining()
        tests()
        return builder.build()
    }

    private fun modules() {
        for (module in model.modules) {
            builder.add(Node(module.id, NodeKind.MODULE))
            for (dep in module.dependencies) {
                builder.add(Edge(module.id, dep.target, EdgeKind.DEPENDS_ON_MODULE, Confidence.EXACT, mapOf("kind" to dep.kind.name)))
            }
        }
    }

    private fun code(snapshot: ProjectSnapshot, index: ClassIndex) {
        for (sourceSet in snapshot.sourceSets) {
            for (c in sourceSet.classes.values) {
                builder.add(Node(c.id, NodeKind.CLASS))
                c.superName?.let { s -> index.resolve(s, c.id.module).forEach { builder.add(Edge(c.id, it, EdgeKind.EXTENDS, Confidence.EXACT)) } }
                c.interfaces.forEach { i -> index.resolve(i, c.id.module).forEach { builder.add(Edge(c.id, it, EdgeKind.IMPLEMENTS, Confidence.EXACT)) } }
                for (f in c.fields) {
                    val id = c.id.field(f.name, f.descriptor)
                    builder.add(Node(id, NodeKind.FIELD))
                    builder.add(Edge(c.id, id, EdgeKind.DECLARES, Confidence.EXACT))
                }
                for (m in c.methods) {
                    val id = c.id.method(m.name, m.descriptor)
                    builder.add(Node(id, if (m.name == "<init>") NodeKind.CONSTRUCTOR else NodeKind.METHOD))
                    builder.add(Edge(c.id, id, EdgeKind.DECLARES, Confidence.EXACT))
                    for (call in m.references.calls) {
                        val (owner, nameAndDesc) = splitMember(call, '.') ?: continue
                        val paren = nameAndDesc.indexOf('(')
                        if (paren <= 0) continue
                        index.resolveMethod(owner, nameAndDesc.substring(0, paren), nameAndDesc.substring(paren), c.id.module)
                            .forEach { builder.add(Edge(id, it, EdgeKind.CALLS, Confidence.EXACT)) }
                    }
                    fieldEdges(id, m.references.fieldReads, EdgeKind.READS_FIELD, index, c.id.module)
                    fieldEdges(id, m.references.fieldWrites, EdgeKind.WRITES_FIELD, index, c.id.module)
                    for (type in m.references.types) {
                        index.resolve(elementType(type), c.id.module).forEach { builder.add(Edge(id, it, EdgeKind.REFERENCES, Confidence.EXACT)) }
                    }
                    if (m.role == MethodRole.NORMAL && m.name != "<init>" && m.name != "<clinit>" && (m.access and (ACC_STATIC or ACC_PRIVATE)) == 0) {
                        index.overridden(c, m.name, m.descriptor).forEach { builder.add(Edge(id, it, EdgeKind.OVERRIDES, Confidence.EXACT)) }
                    }
                }
            }
        }
    }

    private fun fieldEdges(from: MethodId, refs: Set<String>, kind: EdgeKind, index: ClassIndex, module: ModuleId) {
        for (ref in refs) {
            val (owner, nameAndDesc) = splitMember(ref, '.') ?: continue
            val colon = nameAndDesc.lastIndexOf(':')
            if (colon <= 0) continue
            index.resolve(owner, module).forEach { builder.add(Edge(from, it.field(nameAndDesc.substring(0, colon), nameAndDesc.substring(colon + 1)), kind, Confidence.EXACT)) }
        }
    }

    /** framework profile이 읽는 attribute: annotation descriptor 목록과 생성된 class 표시 (§6.4). */
    private fun frameworkAttributes() {
        for ((id, descriptors) in facts.annotations) {
            val kind = when (id) {
                is ClassId -> NodeKind.CLASS
                is FieldId -> NodeKind.FIELD
                is MethodId -> if (id.name == "<init>") NodeKind.CONSTRUCTOR else NodeKind.METHOD
                else -> continue
            }
            builder.add(Node(id, kind, mapOf("annotations" to descriptors.joinToString(" "))))
        }
        for (id in facts.generatedClasses) builder.add(Node(id, NodeKind.CLASS, mapOf("generated" to "true")))
    }

    /** `INLINED_INTO`는 kotlinc의 흔적으로 추정한 것이므로 INFERRED다 (§3.6 규칙 3). */
    private fun inlining() {
        val inlineByName = HashMap<String, MutableList<MethodId>>()
        for (c in headIndex.all) {
            val kotlin = c.kotlin ?: continue
            for (m in c.methods) {
                if (kotlin.isInlineMethod(m.key)) inlineByName.getOrPut(m.name) { ArrayList() } += c.id.method(m.name, m.descriptor)
            }
        }
        if (inlineByName.isEmpty()) return
        for (c in headIndex.all) {
            for (m in c.methods) {
                val caller = c.id.method(m.name, m.descriptor)
                for (inlined in m.references.inlinedFunctions) {
                    val owner = inlined.substringBeforeLast('.')
                    val name = inlined.substringAfterLast('.')
                    inlineByName[name].orEmpty()
                        .filter { owner == "?" || it.owner.internalName == owner }
                        .forEach { builder.add(Edge(it, caller, EdgeKind.INLINED_INTO, Confidence.INFERRED)) }
                }
            }
        }
    }

    /**
     * test node와 `EXECUTED_BY_TEST` edge.
     * head의 test source set에 class가 없는 test는 삭제된 test이므로 버린다.
     * 관측 이후 test class 자신이 바뀌었으면 trace는 stale이고 edge는 INFERRED로 내려간다 (§8.6).
     */
    private fun tests() {
        val discovered = TestDiscovery.discover(head, model)
        val testClasses = HashMap<String, ClassId>()
        discovered.forEach { testClasses[it.id.className] = it.classId }
        val testSourceSets = model.modules.flatMap { it.sourceSets }.filter { it.isTest }.map { it.id }.toSet()
        head.sourceSets.filter { it.id in testSourceSets }.forEach { ss -> ss.classes.values.forEach { testClasses.putIfAbsent(it.id.binaryName, it.id) } }
        val changedClasses = deltas.mapNotNull { ownerClass(it.subject) }.toSet()

        fun addTest(id: TestId, classId: ClassId, observed: Boolean) {
            val observation = traces.tests[id]
            val attributes = buildMap {
                put("module", classId.module.path)
                put("classId", classId.canonical)
                put("traced", observed.toString())
                observation?.durationMs?.let { put("durationMs", it.toString()) }
                observation?.status?.let { put("lastStatus", it) }
            }
            builder.add(Node(id, if (id.method == null) NodeKind.TEST_CLASS else NodeKind.TEST_METHOD, attributes))
        }

        for (test in discovered) addTest(test.id, test.classId, test.id in traces.tests)
        for (observation in traces.tests.values) {
            val classId = testClasses[observation.test.className] ?: continue
            addTest(observation.test, classId, observed = true)
            val stale = classId in changedClasses
            val confidence = if (stale || observation.contaminated) Confidence.INFERRED else Confidence.OBSERVED
            val attributes = if (stale) mapOf("stale" to "true") else emptyMap()
            for (method in observation.methods) {
                builder.add(Edge(method, observation.test, EdgeKind.EXECUTED_BY_TEST, confidence, attributes))
            }
        }
        // 한 번만 실행되는 code는 같은 fork의 모든 test class가 의존할 수 있다 (§8.3)
        for (fork in traces.forks) {
            val classes = fork.testClasses.map { it.classLevel() }.filter { it.className in testClasses }
            for (testClass in classes) {
                addTest(testClass, testClasses.getValue(testClass.className), observed = true)
                for (method in fork.oneTimeInit) {
                    builder.add(Edge(method, testClass, EdgeKind.EXECUTED_BY_TEST, Confidence.INFERRED, mapOf("reason" to ReasonCode.ONE_TIME_INITIALIZATION.name)))
                }
            }
        }
    }

    private fun ownerClass(id: jdelta.core.NodeId): ClassId? = when (id) {
        is ClassId -> id
        is MethodId -> id.owner
        is FieldId -> id.owner
        else -> null
    }

    /** `[Lcom/acme/Foo;` -> `com/acme/Foo`. 배열이 아니면 그대로. */
    private fun elementType(type: String): String {
        val element = type.trimStart('[')
        return if (element.length < type.length && element.startsWith("L")) element.substring(1, element.length - 1) else element
    }

    private fun splitMember(ref: String, separator: Char): Pair<String, String>? {
        // owner internal name에는 '.'이 없으므로 첫 separator가 경계다
        val i = ref.indexOf(separator)
        return if (i <= 0) null else ref.substring(0, i) to ref.substring(i + 1)
    }

    /** internal name -> project 안의 class. 같은 이름이 여러 module에 있으면 같은 module을 우선한다. */
    private class ClassIndex(snapshot: ProjectSnapshot) {
        val all: List<ClassSnapshot> = snapshot.sourceSets.flatMap { it.classes.values }
        private val byName: Map<String, List<ClassSnapshot>> = all.groupBy { it.internalName }

        fun resolve(internalName: String, from: ModuleId): List<ClassId> = classes(internalName, from).map { it.id }

        private fun classes(internalName: String, from: ModuleId): List<ClassSnapshot> {
            val candidates = byName[internalName] ?: return emptyList()
            return candidates.filter { it.id.module == from }.ifEmpty { candidates }
        }

        /** 호출 대상 method를 선언한 class를 상위 type에서 찾는다(invokevirtual은 subclass 이름으로 상위 method를 부른다). */
        fun resolveMethod(owner: String, name: String, descriptor: String, from: ModuleId): List<MethodId> {
            val queue = ArrayDeque(classes(owner, from))
            val seen = HashSet<ClassId>()
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                if (!seen.add(c.id)) continue
                if (c.methods.any { it.name == name && it.descriptor == descriptor }) return listOf(c.id.method(name, descriptor))
                (listOfNotNull(c.superName) + c.interfaces).forEach { queue += classes(it, c.id.module) }
            }
            return emptyList()
        }

        /** [c]의 상위 type에서 같은 이름과 descriptor의 method. */
        fun overridden(c: ClassSnapshot, name: String, descriptor: String): List<MethodId> {
            val result = ArrayList<MethodId>()
            val queue = ArrayDeque((listOfNotNull(c.superName) + c.interfaces).flatMap { classes(it, c.id.module) })
            val seen = HashSet<ClassId>()
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                if (!seen.add(s.id)) continue
                val m = s.methods.firstOrNull { it.name == name && it.descriptor == descriptor && (it.access and ACC_PRIVATE) == 0 }
                if (m != null) result += s.id.method(name, descriptor)
                (listOfNotNull(s.superName) + s.interfaces).forEach { queue += classes(it, s.id.module) }
            }
            return result
        }
    }

    private companion object {
        const val ACC_PRIVATE = 0x0002
        const val ACC_STATIC = 0x0008
    }
}
