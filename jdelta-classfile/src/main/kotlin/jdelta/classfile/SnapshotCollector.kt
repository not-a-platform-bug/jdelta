package jdelta.classfile

import jdelta.core.ProjectModel
import jdelta.core.SourceSetModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * project model의 output directory를 읽어 [ProjectSnapshot]을 만든다 (ARCHITECTURE.md §4.3).
 * 존재하지 않는 directory는 빈 것으로 취급한다(아직 build되지 않은 source set).
 */
public class SnapshotCollector(
    private val reader: ClassSnapshotReader = ClassSnapshotReader(),
) {
    public fun collect(model: ProjectModel): ProjectSnapshot =
        ProjectSnapshot(model.modules.flatMap { it.sourceSets }.parallelStream().map(::collect).toList())

    public fun collect(sourceSet: SourceSetModel): SourceSetSnapshot {
        val module = sourceSet.id.module
        val classes = LinkedHashMap<String, ClassSnapshot>()
        val unparseable = LinkedHashMap<String, String>()
        var moduleDescriptor: String? = null

        for (dir in sourceSet.classesDirs) {
            for (file in walk(dir)) {
                if (file.extension != "class") continue
                val bytes = file.readBytes()
                if (file.name == "module-info.class") {
                    moduleDescriptor = Hasher.bytes(bytes)
                    continue
                }
                try {
                    val snapshot = reader.read(module, bytes)
                    classes[snapshot.internalName] = snapshot
                } catch (e: RuntimeException) {
                    // ASM이 모르는 classfile version 등. 분석을 멈추지 않고 UNKNOWN으로 넘긴다 (§3.5).
                    unparseable[dir.relativize(file).invariantSeparatorsPathString.removeSuffix(".class")] = Hasher.bytes(bytes)
                }
            }
        }

        return SourceSetSnapshot(sourceSet.id, classes, unparseable, resourceHashes(sourceSet), moduleDescriptor)
    }

    /** resource 상대 경로 -> content hash. 저장된 snapshot은 resource 내용 대신 이 hash만 보관한다. */
    public fun resourceHashes(sourceSet: SourceSetModel): Map<String, String> {
        val resources = LinkedHashMap<String, String>()
        for (dir in sourceSet.resourcesDirs) {
            for (file in walk(dir)) {
                resources[dir.relativize(file).invariantSeparatorsPathString] = Hasher.bytes(file.readBytes())
            }
        }
        return resources
    }

    private fun walk(dir: Path): List<Path> {
        if (!dir.isDirectory()) return emptyList()
        return Files.walk(dir).use { stream -> stream.filter { it.isRegularFile() }.sorted().toList() }
    }
}
