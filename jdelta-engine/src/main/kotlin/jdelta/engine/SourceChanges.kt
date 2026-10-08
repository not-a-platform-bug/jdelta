package jdelta.engine

import jdelta.classfile.ClassSnapshot
import jdelta.classfile.ProjectSnapshot
import jdelta.core.ClassId
import jdelta.core.Confidence
import jdelta.core.DeltaKind
import jdelta.core.FieldId
import jdelta.core.FileId
import jdelta.core.ImpactLevel
import jdelta.core.MethodId
import jdelta.core.ProjectModel
import jdelta.core.Reason
import jdelta.core.ReasonCode
import jdelta.core.SemanticDelta
import jdelta.core.SourceSetModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** git이 보고한 변경 파일 하나. [path]는 repository root 기준 `/` 구분 상대 경로. */
public data class ChangedFile(public val path: String, public val status: Status) {
    public enum class Status { ADDED, MODIFIED, DELETED }
}

/** build output이 source보다 오래된 source 파일 (§4.5). */
public data class StaleSource(public val path: String, public val reason: String)

/**
 * 변경된 source 파일과 build output을 대조한다 (ARCHITECTURE.md §4.5).
 *
 * source 파일과 classfile은 `SourceFile` attribute와 package 경로로 잇는다.
 * Kotlin은 package와 directory가 달라도 되므로, 경로로 찾지 못하면 같은 source set 안에서 file 이름만으로 찾는다.
 */
public class SourceChanges(
    private val rootDir: Path,
    private val model: ProjectModel,
    private val head: ProjectSnapshot,
) {
    /** 낡은 build output으로 "변경 없음"이라고 말하는 것이 가장 나쁜 실패다. 의심되면 stale로 본다. */
    public fun stale(changes: List<ChangedFile>): List<StaleSource> = changes
        .filter { it.status != ChangedFile.Status.DELETED && isCompiledSource(it.path) }
        .mapNotNull { change ->
            val source = rootDir.resolve(change.path)
            if (!source.isRegularFile()) return@mapNotNull null
            val (sourceSet, classes) = classesOf(change.path) ?: return@mapNotNull null
            if (classes.isEmpty()) return@mapNotNull StaleSource(change.path, "no compiled class found in ${sourceSet.id}")
            val sourceTime = Files.getLastModifiedTime(source)
            val older = classes.mapNotNull { classfile(sourceSet, it) }.filter { Files.getLastModifiedTime(it) < sourceTime }
            if (older.isEmpty()) null else StaleSource(change.path, "${older.size} classfile(s) are older than the source")
        }

    /**
     * class delta로 드러나지 않는 변경을 delta로 만든다.
     * - source는 바뀌었는데 compile 결과가 같다: `SOURCE_ONLY_CHANGED`
     * - build script, dependency 선언이 바뀌었다: `BUILD_CONFIGURATION_CHANGED` / `DEPENDENCY_CHANGED` (분석 범위 밖이므로 UNKNOWN)
     */
    public fun deltas(changes: List<ChangedFile>, classDeltas: List<SemanticDelta>): List<SemanticDelta> {
        val changedClasses = classDeltas.mapNotNull { ownerClass(it.subject) }.map { it.internalName to it.module }.toSet()
        val result = ArrayList<SemanticDelta>()
        for (change in changes.sortedBy { it.path }) {
            val buildKind = buildFileKind(change.path)
            if (buildKind != null) {
                result += buildDelta(change, buildKind)
                continue
            }
            if (change.status == ChangedFile.Status.DELETED || !isCompiledSource(change.path)) continue
            val (sourceSet, classes) = classesOf(change.path) ?: continue
            if (classes.isEmpty()) continue
            // local/anonymous class 변경은 소유 class의 delta로 보고되므로 바깥 class까지 본다
            val touched = classes.any { c -> generateSequence(c.internalName) { it.substringBeforeLast('$', "").ifEmpty { null } }.any { (it to sourceSet.id.module) in changedClasses } }
            if (touched) continue
            result += SemanticDelta(
                subject = FileId(change.path),
                kind = DeltaKind.SOURCE_ONLY_CHANGED,
                confidence = Confidence.EXACT,
                reasons = listOf(
                    Reason(ReasonCode.SOURCE_ONLY, "source changed but its ${classes.size} compiled class(es) are semantically identical (comments, formatting, line moves)", classes.map { it.id }),
                ),
            )
        }
        return result
    }

    private fun buildDelta(change: ChangedFile, kind: DeltaKind) = SemanticDelta(
        subject = FileId(change.path),
        kind = kind,
        compileImpact = ImpactLevel.UNKNOWN,
        binaryImpact = ImpactLevel.UNKNOWN,
        reflectionImpact = ImpactLevel.UNKNOWN,
        frameworkImpact = ImpactLevel.UNKNOWN,
        testImpact = ImpactLevel.UNKNOWN,
        confidence = Confidence.CONSERVATIVE_UNKNOWN,
        reasons = listOf(
            if (kind == DeltaKind.DEPENDENCY_CHANGED) {
                Reason(ReasonCode.DEPENDENCY_DECLARATION_CHANGED, "dependency declarations ${change.status.name.lowercase()}; library ABI changes are not analyzed in v0.1")
            } else {
                Reason(ReasonCode.BUILD_SCRIPT_CHANGED, "build configuration ${change.status.name.lowercase()}; compiler flags, plugins and tasks may change every output")
            },
        ),
    )

    private fun ownerClass(id: jdelta.core.NodeId): ClassId? = when (id) {
        is ClassId -> id
        is MethodId -> id.owner
        is FieldId -> id.owner
        else -> null
    }

    /** source 파일이 속한 source set과 그 파일에서 나온 class. source set을 모르면 null. */
    private fun classesOf(path: String): Pair<SourceSetModel, List<ClassSnapshot>>? {
        val absolute = real(rootDir.resolve(path))
        for (module in model.modules) {
            for (sourceSet in module.sourceSets) {
                val sourceDir = sourceSet.sourceDirs.map { real(it) }.firstOrNull { absolute.startsWith(it) } ?: continue
                val relative = sourceDir.relativize(absolute).invariantSeparatorsPathString
                val fileName = absolute.name
                val packagePath = relative.substringBeforeLast('/', "")
                val candidates = head.sourceSet(sourceSet.id)?.classes?.values.orEmpty().filter { it.sourceFile == fileName }
                val exact = candidates.filter { it.packageName == packagePath }
                return sourceSet to exact.ifEmpty { if (path.endsWith(".kt")) candidates else emptyList() }
            }
        }
        return null
    }

    /** `/var`와 `/private/var`처럼 symlink로 갈라진 경로를 맞춘다. 없는 파일은 정규화만 한다. */
    private fun real(p: Path): Path = try {
        p.toRealPath()
    } catch (e: java.io.IOException) {
        p.toAbsolutePath().normalize()
    }

    private fun classfile(sourceSet: SourceSetModel, c: ClassSnapshot): Path? =
        sourceSet.classesDirs.map { it.resolve(c.internalName + ".class") }.firstOrNull { it.isRegularFile() }

    private companion object {
        val SOURCE_EXTENSIONS = setOf("java", "kt")

        /** package-info, module-info는 class를 만들지 않을 수 있다. */
        fun isCompiledSource(path: String): Boolean {
            val name = path.substringAfterLast('/')
            return name.substringAfterLast('.') in SOURCE_EXTENSIONS && name != "package-info.java" && name != "module-info.java"
        }

        fun buildFileKind(path: String): DeltaKind? {
            val name = path.substringAfterLast('/')
            return when {
                name == "libs.versions.toml" || name == "gradle.lockfile" || name.endsWith(".lockfile") -> DeltaKind.DEPENDENCY_CHANGED
                name.endsWith(".gradle.kts") || name.endsWith(".gradle") || name == "gradle.properties" -> DeltaKind.BUILD_CONFIGURATION_CHANGED
                path.startsWith("gradle/wrapper/") -> DeltaKind.BUILD_CONFIGURATION_CHANGED
                path.startsWith("buildSrc/") || path.startsWith("build-logic/") -> DeltaKind.BUILD_CONFIGURATION_CHANGED
                else -> null
            }
        }
    }
}
