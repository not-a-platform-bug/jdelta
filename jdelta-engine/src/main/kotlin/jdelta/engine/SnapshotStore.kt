package jdelta.engine

import jdelta.classfile.SnapshotCollector
import jdelta.core.ModuleModel
import jdelta.core.NodeId
import jdelta.core.ProjectModel
import jdelta.core.SourceSetId
import jdelta.core.SourceSetModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** snapshot을 만든 revision. */
public data class SnapshotRevision(
    public val sha: String?,
    public val label: String,
    public val dirty: Boolean,
)

/**
 * 저장된 snapshot 하나. [model]의 classes directory는 저장소 안을 가리킨다.
 * resource는 내용 대신 hash만 보관한다.
 */
public data class StoredSnapshot(
    public val dir: Path,
    public val revision: SnapshotRevision,
    public val createdAt: String,
    public val model: ProjectModel,
    public val resources: Map<SourceSetId, Map<String, String>>,
)

/**
 * `.jdelta/` 작업 디렉터리 (ARCHITECTURE.md §12). 모든 내용은 source와 build로 다시 만들 수 있는 cache다.
 */
public class Workspace(public val rootDir: Path) {
    public val dir: Path = rootDir.resolve(".jdelta")
    public val snapshots: SnapshotStore = SnapshotStore(dir.resolve("snapshots"))
    public val rawTraces: Path = dir.resolve("traces/raw")
    public val traceStore: Path = dir.resolve("traces/store.json")

    /** 병렬 CI job이 같은 디렉터리를 쓰는 경우를 위해 쓰기 구간을 file lock으로 감싼다. */
    public fun <T> locked(block: () -> T): T {
        dir.createDirectories()
        FileChannel.open(dir.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { return block() }
        }
    }
}

/**
 * commit SHA 단위 snapshot 저장소.
 *
 * 해석한 snapshot(JSON) 대신 **classfile 원본**을 보관한다. 읽을 때 현재 reader로 다시 해석하므로
 * reader가 개선되어도 저장된 snapshot이 낡지 않고, 저장 형식의 schema migration도 필요 없다.
 *
 * ```text
 * <dir>/<sha>/manifest.json
 * <dir>/<sha>/<n>/...class       n = manifest의 source set 순번
 * ```
 */
public class SnapshotStore(private val dir: Path) {
    public fun contains(sha: String): Boolean = load(sha) != null

    /** 없거나 형식 version이 다르면 null. 형식이 다르면 migration하지 않고 다시 만든다 (§12). */
    public fun load(sha: String): StoredSnapshot? {
        val snapshotDir = dir.resolve(sha)
        return if (snapshotDir.resolve(MANIFEST).isRegularFile()) readOrNull(snapshotDir) else null
    }

    public fun save(sha: String, model: ProjectModel, revision: SnapshotRevision): StoredSnapshot {
        dir.createDirectories()
        val target = dir.resolve(sha)
        load(sha)?.let { return it }
        val tmp = dir.resolve(".tmp-${UUID.randomUUID()}")
        write(tmp, model, revision)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            deleteRecursively(tmp) // 다른 process가 먼저 저장했다
        } catch (e: java.nio.file.DirectoryNotEmptyException) {
            deleteRecursively(tmp)
        }
        return read(target)
    }

    public companion object {
        public const val FORMAT_VERSION: Int = 1
        private const val MANIFEST = "manifest.json"
        private val json = Json { prettyPrint = true }

        /** [model]의 현재 build output을 [target]에 snapshot으로 쓴다. CI artifact(`--baseline-dir`)용으로도 쓴다. */
        public fun write(target: Path, model: ProjectModel, revision: SnapshotRevision, collector: SnapshotCollector = SnapshotCollector()) {
            require(!target.exists() || Files.list(target).use { it.findFirst().isEmpty }) { "snapshot target is not empty: $target" }
            target.createDirectories()
            val sourceSets = model.modules.flatMap { it.sourceSets }
            val manifest = buildJsonObject {
                put("formatVersion", FORMAT_VERSION)
                putJsonObject("revision") {
                    revision.sha?.let { put("sha", it) }
                    put("label", revision.label)
                    put("dirty", revision.dirty)
                }
                put("createdAt", Instant.now().toString())
                putJsonArray("sourceSets") {
                    sourceSets.forEachIndexed { index, sourceSet ->
                        copyClasses(sourceSet, target.resolve(index.toString()))
                        addJsonObject {
                            put("id", sourceSet.id.canonical)
                            put("test", sourceSet.isTest)
                            put("dir", index.toString())
                            putJsonObject("resources") {
                                collector.resourceHashes(sourceSet).forEach { (path, hash) -> put(path, hash) }
                            }
                        }
                    }
                }
            }
            target.resolve(MANIFEST).writeText(json.encodeToString(JsonObject.serializer(), manifest) + "\n")
        }

        /** `--baseline-dir`로 받은 snapshot directory를 읽는다. */
        public fun read(snapshotDir: Path): StoredSnapshot =
            readOrNull(snapshotDir) ?: throw IllegalArgumentException("not a jdelta snapshot (format $FORMAT_VERSION): $snapshotDir")

        private fun readOrNull(snapshotDir: Path): StoredSnapshot? {
            val manifestFile = snapshotDir.resolve(MANIFEST)
            if (!manifestFile.isRegularFile()) return null
            val manifest = try {
                Json.parseToJsonElement(manifestFile.readText()).jsonObject
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (manifest["formatVersion"]?.jsonPrimitive?.int != FORMAT_VERSION) return null
            val revision = manifest.getValue("revision").jsonObject.let {
                SnapshotRevision(it["sha"]?.jsonPrimitive?.content, it.getValue("label").jsonPrimitive.content, it.getValue("dirty").jsonPrimitive.boolean)
            }
            val resources = LinkedHashMap<SourceSetId, Map<String, String>>()
            val sourceSets = manifest.getValue("sourceSets").jsonArray.map { element ->
                val o = element.jsonObject
                val id = NodeId.parse(o.getValue("id").jsonPrimitive.content) as SourceSetId
                resources[id] = o.getValue("resources").jsonObject.mapValues { it.value.jsonPrimitive.content }
                SourceSetModel(
                    id = id,
                    classesDirs = listOf(snapshotDir.resolve(o.getValue("dir").jsonPrimitive.content)),
                    isTest = o.getValue("test").jsonPrimitive.boolean,
                )
            }
            val modules = sourceSets.groupBy { it.id.module }.map { (module, sets) -> ModuleModel(module, snapshotDir, sourceSets = sets) }
            return StoredSnapshot(
                dir = snapshotDir,
                revision = revision,
                createdAt = manifest["createdAt"]?.jsonPrimitive?.content ?: "",
                model = ProjectModel(snapshotDir, modules),
                resources = resources,
            )
        }

        /** java/kotlin 등 여러 classes directory를 하나로 합친다. 같은 package tree이므로 경로가 겹치지 않는다. */
        private fun copyClasses(sourceSet: SourceSetModel, target: Path) {
            target.createDirectories()
            for (classesDir in sourceSet.classesDirs) {
                if (!classesDir.isDirectory()) continue
                Files.walk(classesDir).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension == "class" }.forEach { file ->
                        val dest = target.resolve(classesDir.relativize(file).invariantSeparatorsPathString)
                        dest.parent.createDirectories()
                        Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        private fun deleteRecursively(path: Path) {
            if (!path.exists()) return
            Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
