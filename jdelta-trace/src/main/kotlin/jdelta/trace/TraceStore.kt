package jdelta.trace

import jdelta.core.MethodId
import jdelta.core.NodeId
import jdelta.core.TestId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * test 하나(또는 test class의 setup)의 최신 관측 (ARCHITECTURE.md §8.6).
 * [test]의 method가 null이면 class setup(`@BeforeAll`, 첫 static 초기화 등)이다.
 */
public data class TestObservation(
    public val test: TestId,
    public val methods: Set<MethodId>,
    public val commit: String?,
    public val recordedAt: String,
    public val runId: String,
    public val forkId: String,
    public val durationMs: Long?,
    public val status: String?,
    public val contaminated: Boolean,
)

/**
 * fork 하나에서 한 번만 실행된 code와 그 fork에서 실행된 test class (§8.3).
 * one-time code가 바뀌면 같은 fork의 test class 전체가 영향 후보다.
 */
public data class ForkObservation(
    public val runId: String,
    public val forkId: String,
    public val oneTimeInit: Set<MethodId>,
    public val testClasses: Set<TestId>,
)

public class TraceStore(
    public val tests: Map<TestId, TestObservation>,
    public val forks: List<ForkObservation>,
) {
    public val isEmpty: Boolean get() = tests.isEmpty()

    /**
     * raw trace를 병합한다. 같은 test의 새 관측은 이전 것을 **대체**한다(합집합이 아니다).
     * 삭제된 code를 계속 가리키면 recall이 아니라 노이즈가 늘어나기 때문이다.
     */
    public fun merge(traces: List<RawTrace>, recordedAt: String): TraceStore {
        val updated = LinkedHashMap(tests)
        val newForks = ArrayList<ForkObservation>()
        for (trace in traces) {
            for (scope in trace.scopes) {
                updated[scope.test] = TestObservation(
                    test = scope.test,
                    methods = trace.methodsOf(scope),
                    commit = trace.commit,
                    recordedAt = recordedAt,
                    runId = trace.runId,
                    forkId = trace.forkId,
                    durationMs = scope.durationMs,
                    status = scope.status,
                    contaminated = trace.contaminated,
                )
            }
            val classes = trace.scopes.mapTo(LinkedHashSet()) { it.test.classLevel() }
            newForks += ForkObservation(trace.runId, trace.forkId, trace.oneTimeInitMethods, classes)
        }
        // 다시 관측된 test class는 옛 fork에서 뺀다. 비게 된 fork는 버린다.
        val reobserved = newForks.flatMapTo(HashSet()) { it.testClasses }
        val kept = forks.map { it.copy(testClasses = it.testClasses - reobserved) }.filter { it.testClasses.isNotEmpty() }
        return TraceStore(updated, kept + newForks)
    }

    public fun save(file: Path) {
        file.parent?.createDirectories()
        val json = buildJsonObject {
            put("formatVersion", FORMAT_VERSION)
            putJsonArray("tests") {
                tests.values.sortedBy { it.test.canonical }.forEach { o ->
                    add(
                        buildJsonObject {
                            put("test", o.test.canonical)
                            put("commit", o.commit)
                            put("recordedAt", o.recordedAt)
                            put("runId", o.runId)
                            put("forkId", o.forkId)
                            o.durationMs?.let { put("durationMs", it) }
                            put("status", o.status)
                            put("contaminated", o.contaminated)
                            put("methods", JsonArray(o.methods.map { JsonPrimitive(it.canonical) }.sortedBy { it.content }))
                        },
                    )
                }
            }
            putJsonArray("forks") {
                forks.forEach { f ->
                    add(
                        buildJsonObject {
                            put("runId", f.runId)
                            put("forkId", f.forkId)
                            put("oneTimeInit", JsonArray(f.oneTimeInit.map { JsonPrimitive(it.canonical) }.sortedBy { it.content }))
                            put("testClasses", JsonArray(f.testClasses.map { JsonPrimitive(it.canonical) }.sortedBy { it.content }))
                        },
                    )
                }
            }
        }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        tmp.writeText(Json.encodeToString(JsonObject.serializer(), json))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    public companion object {
        public const val FORMAT_VERSION: Int = 1

        public val EMPTY: TraceStore = TraceStore(emptyMap(), emptyList())

        /** 없거나 형식이 다르면 빈 store. trace는 다시 만들 수 있는 cache다 (§12). */
        public fun load(file: Path): TraceStore {
            if (!file.isRegularFile()) return EMPTY
            val o = try {
                Json.parseToJsonElement(file.readText()).jsonObject
            } catch (e: IllegalArgumentException) {
                return EMPTY
            }
            if (o["formatVersion"]?.jsonPrimitive?.int != FORMAT_VERSION) return EMPTY
            val tests = o.getValue("tests").jsonArray.map { e ->
                val t = e.jsonObject
                TestObservation(
                    test = NodeId.parse(t.getValue("test").jsonPrimitive.content) as TestId,
                    methods = t.getValue("methods").jsonArray.mapTo(LinkedHashSet()) { NodeId.parse(it.jsonPrimitive.content) as MethodId },
                    commit = t["commit"]?.jsonPrimitive?.contentOrNull,
                    recordedAt = t.getValue("recordedAt").jsonPrimitive.content,
                    runId = t.getValue("runId").jsonPrimitive.content,
                    forkId = t.getValue("forkId").jsonPrimitive.content,
                    durationMs = t["durationMs"]?.jsonPrimitive?.long,
                    status = t["status"]?.jsonPrimitive?.contentOrNull,
                    contaminated = t.getValue("contaminated").jsonPrimitive.boolean,
                )
            }
            val forks = o.getValue("forks").jsonArray.map { e ->
                val f = e.jsonObject
                ForkObservation(
                    runId = f.getValue("runId").jsonPrimitive.content,
                    forkId = f.getValue("forkId").jsonPrimitive.content,
                    oneTimeInit = f.getValue("oneTimeInit").jsonArray.mapTo(LinkedHashSet()) { NodeId.parse(it.jsonPrimitive.content) as MethodId },
                    testClasses = f.getValue("testClasses").jsonArray.mapTo(LinkedHashSet()) { NodeId.parse(it.jsonPrimitive.content) as TestId },
                )
            }
            return TraceStore(tests.associateBy { it.test }, forks)
        }
    }
}

/** test method id -> 그 class의 id. */
public fun TestId.classLevel(): TestId = if (method == null) this else TestId(engine, className)
