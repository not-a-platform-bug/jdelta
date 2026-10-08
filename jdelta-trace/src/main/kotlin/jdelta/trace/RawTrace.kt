package jdelta.trace

import jdelta.core.MethodId
import jdelta.core.NodeId
import jdelta.core.TestId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

public enum class ScopeKind { CLASS_SETUP, TEST }

/** scope 하나에서 실행된 method. [hits]는 [RawTrace.methods]의 index다. */
public data class RawScope(
    public val kind: ScopeKind,
    public val test: TestId,
    public val hits: Set<Int>,
    public val durationMs: Long? = null,
    public val status: String? = null,
)

/**
 * test JVM 하나(fork)가 남긴 raw trace (ARCHITECTURE.md §8.5).
 * agent(`jdelta.runtime.Recorder`)가 쓰고 이 module이 읽는다. 두 쪽의 유일한 계약이다.
 */
public data class RawTrace(
    public val runId: String,
    public val forkId: String,
    public val commit: String?,
    public val classpathFingerprint: String?,
    public val contaminated: Boolean,
    public val methods: List<MethodId>,
    public val scopes: List<RawScope>,
    public val ambient: Set<Int>,
    public val oneTimeInit: Set<Int>,
) {
    public fun scope(kind: ScopeKind, test: TestId): RawScope? = scopes.firstOrNull { it.kind == kind && it.test == test }

    public fun methodsOf(scope: RawScope): Set<MethodId> = scope.hits.mapTo(LinkedHashSet()) { methods[it] }

    public val oneTimeInitMethods: Set<MethodId> get() = oneTimeInit.mapTo(LinkedHashSet()) { methods[it] }

    public companion object {
        public const val FORMAT_VERSION: Int = 1
    }
}

public object RawTraceReader {
    public fun read(file: Path): RawTrace {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        val version = o["formatVersion"]?.jsonPrimitive?.int
        require(version == RawTrace.FORMAT_VERSION) { "unsupported raw trace format $version in $file" }
        return RawTrace(
            runId = o.getValue("runId").jsonPrimitive.content,
            forkId = o.getValue("forkId").jsonPrimitive.content,
            commit = o["commit"]?.jsonPrimitive?.contentOrNull,
            classpathFingerprint = o["classpathFingerprint"]?.jsonPrimitive?.contentOrNull,
            contaminated = o.getValue("contaminated").jsonPrimitive.boolean,
            methods = o.getValue("methods").jsonArray.map { NodeId.parse(it.jsonPrimitive.content) as MethodId },
            scopes = o.getValue("scopes").jsonArray.map { scope(it.jsonObject) },
            ambient = o.ints("ambient"),
            oneTimeInit = o.ints("oneTimeInit"),
        )
    }

    /** `<raw>/<runId>/` 아래의 fork별 파일 전부. */
    public fun readRun(runDir: Path): List<RawTrace> {
        if (!runDir.isDirectory()) return emptyList()
        return Files.list(runDir).use { files -> files.filter { it.extension == "json" }.sorted().toList() }.map(::read)
    }

    private fun scope(o: JsonObject) = RawScope(
        kind = ScopeKind.valueOf(o.getValue("kind").jsonPrimitive.content),
        test = NodeId.parse(o.getValue("test").jsonPrimitive.content) as TestId,
        hits = o.ints("hits"),
        durationMs = o["durationMs"]?.jsonPrimitive?.long,
        status = o["status"]?.jsonPrimitive?.contentOrNull,
    )

    private fun JsonObject.ints(key: String): Set<Int> = getValue(key).jsonArray.mapTo(LinkedHashSet()) { it.jsonPrimitive.int }
}
