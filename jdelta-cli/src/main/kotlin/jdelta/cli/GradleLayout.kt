package jdelta.cli

import jdelta.core.Language
import jdelta.core.ModuleId
import jdelta.core.ModuleModel
import jdelta.core.ProjectModel
import jdelta.core.SourceSetId
import jdelta.core.SourceSetModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Gradle 관례 directory 구조에서 project model을 추정한다.
 *
 * Gradle model export(ARCHITECTURE.md §4.2, 12단계)가 없을 때 쓰는 대체 수단이다.
 * build script가 있는 directory를 module로 보고, `build/classes/<language>/<sourceSet>`과
 * `src/<sourceSet>/<language>`에서 source set을 찾는다. module 사이 dependency는 알 수 없다.
 */
internal object GradleLayout {
    private val BUILD_SCRIPTS = listOf("build.gradle.kts", "build.gradle")
    private val SETTINGS = listOf("settings.gradle.kts", "settings.gradle")
    private val LANGUAGES = mapOf("java" to Language.JAVA, "kotlin" to Language.KOTLIN, "groovy" to Language.JAVA, "scala" to Language.JAVA)
    private val SKIPPED = setOf("build", "build.nosync", "buildSrc", "out", "node_modules", "src", "gradle")
    private const val MAX_DEPTH = 6

    fun discover(rootDir: Path): ProjectModel {
        val root = rootDir.toAbsolutePath().normalize()
        val modules = moduleDirs(root).map { dir -> module(root, dir) }
        return ProjectModel(root, modules)
    }

    private fun moduleDirs(root: Path): List<Path> {
        val result = ArrayList<Path>()
        fun visit(dir: Path, depth: Int) {
            if (dir != root && SETTINGS.any { dir.resolve(it).exists() }) return // included build (build-logic 등)
            if (BUILD_SCRIPTS.any { dir.resolve(it).exists() } || dir == root) result.add(dir) // Path는 Iterable<Path>이므로 += 를 쓰지 않는다
            if (depth >= MAX_DEPTH) return
            dir.listDirectoryEntries()
                .filter { it.isDirectory() && !it.name.startsWith(".") && it.name !in SKIPPED }
                .sorted()
                .forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return result
    }

    private fun module(root: Path, dir: Path): ModuleModel {
        val relative = root.relativize(dir).invariantSeparatorsPathString
        val id = ModuleId(if (relative.isEmpty()) ":" else ":" + relative.replace('/', ':'))
        val classesRoot = dir.resolve("build/classes")
        val outputs = LinkedHashMap<String, MutableList<Path>>()
        val languages = HashSet<Language>()
        for ((languageDir, language) in LANGUAGES) {
            val base = classesRoot.resolve(languageDir)
            if (!base.isDirectory()) continue
            for (sourceSetDir in base.listDirectoryEntries().filter { it.isDirectory() }.sorted()) {
                outputs.getOrPut(sourceSetDir.name) { ArrayList() }.add(sourceSetDir)
                languages += language
            }
        }
        val sourceRoot = dir.resolve("src")
        val sourceSetNames = (outputs.keys + (if (sourceRoot.isDirectory()) sourceRoot.listDirectoryEntries().filter { it.isDirectory() }.map { it.name } else emptyList()))
            .filter { valid(it) }
            .distinct()
            .sorted()
        val sourceSets = sourceSetNames.map { name ->
            val sourceDirs = LANGUAGES.keys.map { sourceRoot.resolve(name).resolve(it) }.filter { it.isDirectory() }
            if (sourceDirs.any { it.name == "kotlin" }) languages += Language.KOTLIN
            if (sourceDirs.any { it.name == "java" }) languages += Language.JAVA
            SourceSetModel(
                id = SourceSetId(id, name),
                sourceDirs = sourceDirs,
                classesDirs = outputs[name].orEmpty(),
                resourcesDirs = listOf(dir.resolve("build/resources").resolve(name)).filter { Files.isDirectory(it) },
            )
        }.filter { it.classesDirs.isNotEmpty() || it.sourceDirs.isNotEmpty() }
        return ModuleModel(id, dir, languages.ifEmpty { setOf(Language.JAVA) }, sourceSets)
    }

    private fun valid(name: String) = name.isNotEmpty() && name.none { it in "/@|" }
}
