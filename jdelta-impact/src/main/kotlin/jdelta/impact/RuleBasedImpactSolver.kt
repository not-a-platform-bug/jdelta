package jdelta.impact

import jdelta.core.ClassId
import jdelta.core.Confidence
import jdelta.core.DeltaKind
import jdelta.core.Edge
import jdelta.core.EdgeKind
import jdelta.core.Fallback
import jdelta.core.FieldId
import jdelta.core.FileId
import jdelta.core.ImpactInput
import jdelta.core.ImpactLevel
import jdelta.core.ImpactPolicy
import jdelta.core.ImpactResult
import jdelta.core.ImpactSolver
import jdelta.core.ImpactedNode
import jdelta.core.Language
import jdelta.core.MethodId
import jdelta.core.ModuleDependency
import jdelta.core.ModuleId
import jdelta.core.NodeId
import jdelta.core.NodeKind
import jdelta.core.ProjectModel
import jdelta.core.RankedTest
import jdelta.core.Reason
import jdelta.core.ReasonCode
import jdelta.core.ResourceId
import jdelta.core.SemanticDelta
import jdelta.core.SemanticGraph
import jdelta.core.SourceSetId
import jdelta.core.TaskId
import jdelta.core.TestId
import jdelta.core.WorkItem
import jdelta.core.WorkPlan

/**
 * delta kind별 규칙 표(ARCHITECTURE.md §6.2)를 따라 graph를 걷고 impacted test와 work plan을 만든다.
 *
 * static edge(CALLS 등)는 깊이 1로만 쓴다. 실행 영향은 trace(`EXECUTED_BY_TEST`)가 이미 전이적으로 담고 있고,
 * static edge는 trace가 없는 새 symbol을 기존 trace에 잇는 다리일 뿐이다 (§6.5).
 *
 * test node의 attribute(engine이 채운다):
 * - `module`: test가 속한 module path
 * - `classId`: test class의 ClassId canonical
 * - `traced`: trace 관측이 있으면 `true`
 * - `durationMs`, `lastStatus`: 정렬용 과거 관측
 */
public class RuleBasedImpactSolver : ImpactSolver {
    override fun solve(input: ImpactInput): ImpactResult = Solve(input).run()

    private class Solve(private val input: ImpactInput) {
        private val graph: SemanticGraph = input.graph
        private val project: ProjectModel = input.project
        private val tests: List<TestInfo> = (graph.nodes(NodeKind.TEST_METHOD) + graph.nodes(NodeKind.TEST_CLASS)).map { node ->
            TestInfo(
                id = node.id as TestId,
                module = node.attributes["module"]?.let { ModuleId(it) },
                classId = node.attributes["classId"],
                traced = node.attributes["traced"] == "true",
                durationMs = node.attributes["durationMs"]?.toLongOrNull(),
                lastFailed = node.attributes["lastStatus"] == "FAILED",
            )
        }.toList()
        private val testsByClass: Map<String, List<TestInfo>> = tests.filter { it.classId != null }.groupBy { it.classId!! }

        private val hits = LinkedHashMap<TestId, Hit>()
        private val fallbacks = ArrayList<Fallback>()

        fun run(): ImpactResult {
            val relevant = input.deltas.filter { it.kind != DeltaKind.SOURCE_ONLY_CHANGED }
            for (delta in relevant) {
                changedTests(delta)
                rule(delta)
                framework(delta)
            }
            if (relevant.any { it.testImpact != ImpactLevel.NONE }) untracedTests()
            val ranked = rank()
            val items = ArrayList<WorkItem>()
            items += recompile(relevant)
            if (ranked.isNotEmpty()) {
                val conservative = ranked.count { it.confidence == Confidence.CONSERVATIVE_UNKNOWN }
                items += WorkItem.RunTestsFirst(
                    ranked,
                    Confidence.weakestOf(ranked.map { it.confidence }),
                    listOf(Reason(ReasonCode.IMPACT_PATH, "${ranked.size} test(s) ranked by changed-test, confidence and path length; $conservative included conservatively")),
                )
            }
            if (input.policy.mode == ImpactPolicy.Mode.IMPACTED_FIRST && relevant.isNotEmpty()) {
                items += WorkItem.RunFullSuite(null, Confidence.EXACT, listOf(Reason(ReasonCode.FULL_SUITE_ALWAYS, "v0.1 always runs the full suite after the impacted tests")))
            }
            val impacted = ranked.map { ImpactedNode(it.test, it.confidence, it.path, it.reasons) }
            return ImpactResult(impacted, WorkPlan(items, fallbacks))
        }

        // ---- 규칙 ----

        private fun rule(delta: SemanticDelta) {
            val subject = delta.subject
            val evidence = delta.reasons.flatMap { it.evidence }.filter { it is MethodId || it is FieldId }
            val members = listOf(subject) + evidence
            when (delta.kind) {
                DeltaKind.METHOD_BODY_CHANGED -> walk(delta, members, TRACE)
                DeltaKind.METHOD_ANNOTATION_CHANGED, DeltaKind.METHOD_PARAMETERS_CHANGED -> walk(delta, members, TRACE, Confidence.INFERRED)
                DeltaKind.KOTLIN_INLINE_BODY_CHANGED -> inlineBody(delta)
                DeltaKind.METHOD_DESCRIPTOR_CHANGED, DeltaKind.METHOD_REMOVED, DeltaKind.METHOD_ACCESS_CHANGED -> {
                    walk(delta, members, TRACE)
                    walk(delta, members, CALLERS_TRACE)
                }
                DeltaKind.METHOD_ADDED -> {
                    walk(delta, members, OVERRIDDEN_TRACE, Confidence.INFERRED)
                    walk(delta, owners(members), CLASS_TRACE, Confidence.INFERRED)
                }
                DeltaKind.METHOD_EXCEPTIONS_CHANGED -> Unit // compile에만 영향
                DeltaKind.FIELD_DESCRIPTOR_CHANGED, DeltaKind.FIELD_REMOVED, DeltaKind.FIELD_ACCESS_CHANGED -> walk(delta, members, FIELD_ACCESS_TRACE)
                DeltaKind.FIELD_ADDED, DeltaKind.FIELD_ANNOTATION_CHANGED, DeltaKind.GENERIC_SIGNATURE_CHANGED,
                DeltaKind.CLASS_ANNOTATION_CHANGED, DeltaKind.KOTLIN_METADATA_CHANGED,
                -> walk(delta, owners(members), CLASS_TRACE, Confidence.INFERRED)
                DeltaKind.CONSTANT_VALUE_CHANGED -> constant(delta, members)
                DeltaKind.CLASS_HIERARCHY_CHANGED, DeltaKind.CLASS_REMOVED, DeltaKind.CLASS_ACCESS_CHANGED,
                DeltaKind.PERMITTED_SUBCLASSES_CHANGED, DeltaKind.RECORD_COMPONENTS_CHANGED,
                -> walk(delta, owners(members), HIERARCHY_TRACE)
                DeltaKind.ENUM_CONSTANT_ORDER_CHANGED -> {
                    walk(delta, owners(members), REFERENCES_TRACE, Confidence.INFERRED)
                    walk(delta, owners(members), CLASS_TRACE, Confidence.INFERRED)
                }
                DeltaKind.CLASS_ADDED, DeltaKind.SOURCE_ONLY_CHANGED -> Unit
                DeltaKind.RESOURCE_CHANGED -> moduleFallback(delta, runtimeDependents(modulesOf(subject)), ReasonCode.RESOURCE_CONSUMERS_UNKNOWN, record = false)
                DeltaKind.UNKNOWN, DeltaKind.DEPENDENCY_CHANGED, DeltaKind.BUILD_CONFIGURATION_CHANGED -> {
                    val modules = if (subject is FileId) null else runtimeDependents(modulesOf(subject))
                    moduleFallback(delta, modules, delta.reasons.firstOrNull()?.code ?: ReasonCode.UNEXPLAINED_FINGERPRINT_CHANGE, record = true)
                }
            }
        }

        /** inline body는 caller에 복사된다. INLINED_INTO로 caller를 찾고, 찾지 못하면 Kotlin downstream module 전체로 물러선다 (§3.6). */
        private fun inlineBody(delta: SemanticDelta) {
            walk(delta, listOf(delta.subject), TRACE)
            val callers = graph.outgoing(delta.subject, setOf(EdgeKind.INLINED_INTO)).toList()
            if (callers.isNotEmpty()) {
                walk(delta, listOf(delta.subject), INLINE_TRACE, Confidence.INFERRED)
                return
            }
            if (delta.compileImpact != ImpactLevel.DOWNSTREAM) return
            val owner = modulesOf(delta.subject)
            val kotlinDependents = compileDependents(owner) - owner
            val kotlin = kotlinDependents.filter { project.module(it)?.languages?.contains(Language.KOTLIN) != false }.toSet()
            if (kotlin.isNotEmpty()) moduleFallback(delta, kotlin, ReasonCode.KOTLIN_INLINE_CALLERS_UNKNOWN, record = true)
        }

        /** compile된 caller에는 field 참조가 남지 않는다. owner class trace와 downstream module 전체(§6.3). */
        private fun constant(delta: SemanticDelta, members: List<NodeId>) {
            walk(delta, members, FIELD_ACCESS_TRACE)
            walk(delta, owners(members), CLASS_TRACE, Confidence.INFERRED)
            if (delta.compileImpact == ImpactLevel.NONE) return
            val modules = if (delta.compileImpact == ImpactLevel.DOWNSTREAM) compileDependents(modulesOf(delta.subject)) else modulesOf(delta.subject)
            moduleFallback(delta, modules, ReasonCode.CONSTANT_INLINED_AT_CALLERS, record = false)
        }

        // ---- framework profile (§6.4) ----

        private fun annotationsOf(id: NodeId): Set<String> =
            graph.node(id)?.attributes?.get("annotations")?.split(' ')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

        private fun framework(delta: SemanticDelta) {
            val owner = ownerClass(delta.subject) ?: return
            val annotations = annotationsOf(delta.subject) + annotationsOf(owner)
            for (profile in FrameworkMatch.matches(delta.kind, annotations)) {
                val reason = Reason(ReasonCode.FRAMEWORK_PROFILE_MATCH, "${profile.framework} profile: ${profile.annotations.filter { it in annotations }.joinToString { FrameworkMatch.simpleName(it) }}", listOf(delta.subject))
                for (expansion in profile.expansions) expand(delta, owner, expansion, reason)
            }
            // enum ordinal + @Enumerated: entity를 실행한 test와 JPA slice test
            delta.reasons.filter { it.code == ReasonCode.FRAMEWORK_PROFILE_MATCH && it.evidence.isNotEmpty() }.forEach { reason ->
                val entities = reason.evidence.mapNotNull { ownerClass(it) }.distinct()
                walk(delta, entities, CLASS_TRACE, Confidence.INFERRED)
                entities.forEach { expand(delta, it, TestExpansion.JPA_SLICE_TESTS, reason) }
            }
            // processor-sensitive: 생성된 class를 실행한 test를 보수적으로 포함한다
            delta.reasons.filter { it.code == ReasonCode.PROCESSOR_SENSITIVE_ANNOTATION }.forEach { reason ->
                val modules = reason.evidence.flatMap { modulesOf(it) }.toSet()
                val generated = graph.nodes(NodeKind.CLASS).filter { it.attributes["generated"] == "true" && (it.id as ClassId).module in modules }.map { it.id }.toList()
                walk(delta, generated, CLASS_TRACE, Confidence.CONSERVATIVE_UNKNOWN, reason)
            }
        }

        private fun expand(delta: SemanticDelta, owner: ClassId, expansion: TestExpansion, reason: Reason) {
            val modules = runtimeDependents(setOf(owner.module))
            fun testsWith(annotations: Set<String>) = tests.filter { t ->
                t.id.method != null && (modules == null || t.module in modules) &&
                    t.classId?.let { annotationsOf(jdelta.core.NodeId.parse(it)) }.orEmpty().any { it in annotations }
            }
            when (expansion) {
                TestExpansion.CLASS_TRACE -> walk(delta, listOf(owner), CLASS_TRACE, Confidence.INFERRED, reason)
                TestExpansion.CONTEXT_TESTS -> testsWith(FrameworkProfiles.SPRING_TEST_ANNOTATIONS).forEach { record(it.id, Confidence.CONSERVATIVE_UNKNOWN, listOf(delta.subject, it.id), reason) }
                TestExpansion.WEB_SLICE_TESTS -> testsWith(FrameworkProfiles.WEB_SLICE_ANNOTATIONS).forEach { record(it.id, Confidence.INFERRED, listOf(delta.subject, it.id), reason) }
                TestExpansion.JPA_SLICE_TESTS -> testsWith(FrameworkProfiles.JPA_SLICE_ANNOTATIONS).forEach { record(it.id, Confidence.INFERRED, listOf(delta.subject, it.id), reason) }
                TestExpansion.SERIALIZATION_NAMED_TESTS -> tests.filter { t ->
                    val simple = t.id.className.substringAfterLast('.')
                    t.id.method != null && (modules == null || t.module in modules) && (simple.contains("Serializ") || simple.contains("Json"))
                }.forEach { record(it.id, Confidence.INFERRED, listOf(delta.subject, it.id), reason) }
                TestExpansion.GENERATED_CODE_TESTS -> Unit // PROCESSOR_SENSITIVE_ANNOTATION reason이 있을 때 위에서 처리한다
            }
        }

        /** 변경된 test class 자신: test method body 변경은 그 test, class 수준 변경은 class 전체 (§6.6). */
        private fun changedTests(delta: SemanticDelta) {
            val owner = ownerClass(delta.subject) ?: return
            val classTests = testsByClass[owner.canonical] ?: return
            val reason = Reason(ReasonCode.TEST_CHANGED, "the test itself changed (${delta.kind})", listOf(delta.subject))
            val subject = delta.subject
            val matching = if (subject is MethodId) classTests.filter { it.id.method == subject.name } else emptyList()
            val targets = matching.ifEmpty { classTests }
            for (t in targets) record(t.id, Confidence.EXACT, listOf(delta.subject, t.id), reason, changedTest = true)
        }

        /** trace가 없는 test는 영향 여부를 알 수 없으므로 항상 포함한다 (§6.6). */
        private fun untracedTests() {
            for (t in tests) {
                if (t.traced || t.id.method == null) continue
                record(t.id, Confidence.CONSERVATIVE_UNKNOWN, listOf(t.id), Reason(ReasonCode.NO_TRACE_FOR_TEST, "no recorded trace for this test"))
            }
        }

        private fun moduleFallback(delta: SemanticDelta, modules: Set<ModuleId>?, code: ReasonCode, record: Boolean) {
            val reason = Reason(code, "${delta.kind} on ${delta.subject.canonical}: impact cannot be bounded by the graph; " +
                (modules?.let { "all tests of ${it.map { m -> m.path }.sorted().joinToString()}" } ?: "the full suite") + " are included")
            for (t in tests) {
                if (t.id.method == null || (modules != null && t.module !in modules)) continue
                record(t.id, Confidence.CONSERVATIVE_UNKNOWN, listOf(delta.subject, t.id), reason)
            }
            if (record) {
                fallbacks += Fallback(delta.subject, code, WorkItem.RunFullSuite(modules, Confidence.CONSERVATIVE_UNKNOWN, listOf(reason)))
            }
        }

        // ---- traversal (§6.5) ----

        private fun walk(delta: SemanticDelta, seeds: List<NodeId>, route: List<Hop>, cap: Confidence = Confidence.EXACT, extra: Reason? = null) {
            var frontier: Map<NodeId, State> = seeds.associateWith { State(delta.confidence weakest cap, listOf(it)) }
            for (hop in route) {
                val next = LinkedHashMap<NodeId, State>()
                if (hop.includeStart) frontier.forEach { (n, s) -> keep(next, n, s) }
                var layer = frontier
                repeat(hop.maxDepth) {
                    val reached = LinkedHashMap<NodeId, State>()
                    for ((node, state) in layer) {
                        for (edge in hop.edges(graph, node)) {
                            // one-time 초기화 edge는 바뀐 member 자신에서만 따른다. class의 다른 member를 거쳐 가면 fork 전체로 번진다.
                            if (edge.attributes["reason"] != null && state.path.size > 1) continue
                            val other = edge.other(node)
                            val confidence = state.confidence weakest edge.confidence weakest hop.cap
                            keep(reached, other, State(confidence, state.path + other, edge))
                        }
                    }
                    reached.forEach { (n, s) -> keep(next, n, s) }
                    layer = reached
                }
                frontier = next
            }
            for ((node, state) in frontier) {
                if (node !is TestId) continue
                val reasons = mutableListOf(Reason(ReasonCode.IMPACT_PATH, "${delta.kind} reaches this test via ${describe(state.path)}", state.path))
                state.lastEdge?.attributes?.get("reason")?.let { code ->
                    reasons += Reason(ReasonCode.valueOf(code), "code that runs once per fork changed; every test class in that fork may depend on it")
                }
                if (state.confidence == Confidence.INFERRED && state.lastEdge?.attributes?.get("stale") == "true") {
                    reasons += Reason(ReasonCode.TRACE_STALE, "the test changed after its trace was recorded")
                }
                extra?.let { reasons.add(0, it) }
                reasons.forEach { record(node, state.confidence, state.path, it) }
            }
        }

        private fun keep(map: MutableMap<NodeId, State>, node: NodeId, state: State) {
            val existing = map[node]
            if (existing == null || state.better(existing)) map[node] = state
        }

        private fun record(test: TestId, confidence: Confidence, path: List<NodeId>, reason: Reason, changedTest: Boolean = false) {
            val existing = hits[test]
            val candidate = Hit(test, confidence, path, listOf(reason), changedTest)
            hits[test] = when {
                existing == null -> candidate
                candidate.better(existing) -> candidate.copy(reasons = (candidate.reasons + existing.reasons).distinct(), changedTest = changedTest || existing.changedTest)
                else -> existing.copy(reasons = (existing.reasons + reason).distinct(), changedTest = changedTest || existing.changedTest)
            }
        }

        // ---- 정렬 (§6.6) ----

        private fun rank(): List<RankedTest> {
            val info = tests.associateBy { it.id }
            return hits.values.sortedWith(
                compareByDescending<Hit> { it.changedTest }
                    .thenBy { it.confidence.ordinal }
                    .thenBy { it.path.size }
                    .thenByDescending { info[it.test]?.lastFailed == true }
                    .thenBy { info[it.test]?.durationMs ?: Long.MAX_VALUE }
                    .thenBy { it.test.canonical },
            ).mapIndexed { index, hit -> RankedTest(hit.test, index + 1, hit.confidence, hit.path, hit.reasons.take(MAX_REASONS)) }
        }

        // ---- build impact (§6.7) ----

        private fun recompile(deltas: List<SemanticDelta>): List<WorkItem> {
            val scopes = LinkedHashMap<ModuleId, Pair<WorkItem.Scope, MutableList<SemanticDelta>>>()
            for (delta in deltas) {
                val scope = when {
                    delta.compileImpact == ImpactLevel.DOWNSTREAM -> WorkItem.Scope.DOWNSTREAM
                    delta.compileImpact == ImpactLevel.LOCAL -> WorkItem.Scope.LOCAL
                    else -> continue
                }
                for (module in modulesOf(delta.subject)) {
                    val current = scopes[module]
                    val widest = if (current?.first == WorkItem.Scope.DOWNSTREAM) WorkItem.Scope.DOWNSTREAM else scope
                    scopes[module] = widest to (current?.second ?: ArrayList()).also { it += delta }
                }
            }
            return scopes.map { (module, value) ->
                val (scope, list) = value
                val dependents = if (scope == WorkItem.Scope.DOWNSTREAM) (compileDependents(setOf(module)) - module).sortedBy { it.path } else emptyList()
                val message = "${list.size} compile-relevant change(s), e.g. ${list.first().kind} on ${list.first().subject.canonical}" +
                    if (dependents.isNotEmpty()) " — compile classpath reaches ${dependents.joinToString { it.path }}" else ""
                WorkItem.RecompileModule(module, scope, Confidence.weakestOf(list.map { it.confidence }), listOf(Reason(ReasonCode.COMPILE_ABI_CHANGED, message, list.take(MAX_REASONS).map { it.subject })))
            }
        }

        // ---- module graph ----

        /** compile classpath로 닿는 module: 직접 의존하는 module + API로 다시 노출하는 경로 (§4.1). */
        private fun compileDependents(modules: Set<ModuleId>): Set<ModuleId> {
            val result = LinkedHashSet(modules)
            val queue = ArrayDeque(modules.map { it to true })
            while (queue.isNotEmpty()) {
                val (module, exposed) = queue.removeFirst()
                if (!exposed) continue
                for (m in project.modules) {
                    val dep = m.dependencies.firstOrNull { it.target == module && it.kind != ModuleDependency.Kind.RUNTIME_ONLY } ?: continue
                    if (result.add(m.id)) queue += m.id to (dep.kind == ModuleDependency.Kind.API)
                }
            }
            return result
        }

        /** runtime classpath로 닿는 module 전체(transitive). module 사이 dependency를 모르면 전체 module. */
        private fun runtimeDependents(modules: Set<ModuleId>): Set<ModuleId>? {
            if (project.modules.size > 1 && project.modules.all { it.dependencies.isEmpty() }) return null
            val result = LinkedHashSet(modules)
            val queue = ArrayDeque(modules)
            while (queue.isNotEmpty()) {
                val module = queue.removeFirst()
                for (m in project.modules) {
                    if (m.dependencies.any { it.target == module && it.kind != ModuleDependency.Kind.COMPILE_ONLY } && result.add(m.id)) queue += m.id
                }
            }
            return result
        }

        private fun modulesOf(id: NodeId): Set<ModuleId> = when (id) {
            is ClassId -> setOf(id.module)
            is MethodId -> setOf(id.owner.module)
            is FieldId -> setOf(id.owner.module)
            is ResourceId -> setOf(id.sourceSet.module)
            is SourceSetId -> setOf(id.module)
            is ModuleId -> setOf(id)
            is TaskId -> setOf(id.module)
            is FileId, is TestId -> project.modules.map { it.id }.toSet()
        }

        private fun owners(members: List<NodeId>): List<NodeId> = members.mapNotNull { ownerClass(it) }.distinct()

        private fun ownerClass(id: NodeId): ClassId? = when (id) {
            is ClassId -> id
            is MethodId -> id.owner
            is FieldId -> id.owner
            else -> null
        }

        private fun describe(path: List<NodeId>): String = path.joinToString(" -> ") { it.canonical }
    }

    private data class TestInfo(
        val id: TestId,
        val module: ModuleId?,
        val classId: String?,
        val traced: Boolean,
        val durationMs: Long?,
        val lastFailed: Boolean,
    )

    private data class State(val confidence: Confidence, val path: List<NodeId>, val lastEdge: Edge? = null) {
        fun better(other: State) = confidence.ordinal < other.confidence.ordinal ||
            (confidence == other.confidence && path.size < other.path.size)
    }

    private data class Hit(
        val test: TestId,
        val confidence: Confidence,
        val path: List<NodeId>,
        val reasons: List<Reason>,
        val changedTest: Boolean,
    ) {
        fun better(other: Hit) = confidence.ordinal < other.confidence.ordinal ||
            (confidence == other.confidence && path.size < other.path.size)
    }

    /** edge kind를 한 방향으로 [maxDepth]번까지 따라간다. [includeStart]면 출발 node도 다음 hop의 출발점이다. */
    private data class Hop(
        val kinds: Set<EdgeKind>,
        val incoming: Boolean,
        val maxDepth: Int = 1,
        val includeStart: Boolean = false,
        val cap: Confidence = Confidence.EXACT,
    ) {
        fun edges(graph: SemanticGraph, node: NodeId): Sequence<Edge> = if (incoming) graph.incoming(node, kinds) else graph.outgoing(node, kinds)
    }

    private companion object {
        const val MAX_REASONS = 3
        val EXECUTED = Hop(setOf(EdgeKind.EXECUTED_BY_TEST), incoming = false)

        /** member -> 그것을 실행한 test */
        val TRACE = listOf(EXECUTED)

        /** member <- CALLS (1단계) caller -> test */
        val CALLERS_TRACE = listOf(Hop(setOf(EdgeKind.CALLS), incoming = true), EXECUTED)

        /** 새 method가 override하는 상위 method -> test */
        val OVERRIDDEN_TRACE = listOf(Hop(setOf(EdgeKind.OVERRIDES), incoming = false), EXECUTED)

        /** field <- READS/WRITES method -> test */
        val FIELD_ACCESS_TRACE = listOf(Hop(setOf(EdgeKind.READS_FIELD, EdgeKind.WRITES_FIELD), incoming = true), EXECUTED)

        /** class -> 선언한 member -> test */
        val CLASS_TRACE = listOf(Hop(setOf(EdgeKind.DECLARES), incoming = false), EXECUTED)

        /** class와 subclass(최대 5단계) -> member -> test */
        val HIERARCHY_TRACE = listOf(
            Hop(setOf(EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS), incoming = true, maxDepth = 5, includeStart = true),
            Hop(setOf(EdgeKind.DECLARES), incoming = false),
            EXECUTED,
        )

        /** class를 참조하는 method -> test (enum ordinal 등) */
        val REFERENCES_TRACE = listOf(Hop(setOf(EdgeKind.REFERENCES), incoming = true), EXECUTED)

        /** inline function -> INLINED_INTO caller -> test */
        val INLINE_TRACE = listOf(Hop(setOf(EdgeKind.INLINED_INTO), incoming = false, cap = Confidence.INFERRED), EXECUTED)
    }
}
