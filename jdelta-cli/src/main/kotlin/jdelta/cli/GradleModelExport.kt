package jdelta.cli

import jdelta.core.ProjectModel
import jdelta.engine.ProjectModelFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * init script로 `jdelta-gradle-plugin`을 주입해 project model을 export한다 (ARCHITECTURE.md §4.2, D10).
 * 사용자는 build script를 고치지 않아도 된다.
 */
internal object GradleModelExport {
    const val EXPORT_TASK = "jdeltaExportModel"
    private const val MODEL_FILE = ".jdelta/model/project.json"
    private const val PLUGIN_JAR_PROPERTY = "jdelta.gradlePluginJar"

    /** CLI 배포본의 `plugin/jdelta-gradle-plugin.jar`. system property로 바꿀 수 있다(test). */
    fun pluginJar(): Path? = Distribution.file(PLUGIN_JAR_PROPERTY, "plugin/jdelta-gradle-plugin.jar")

    /** Gradle export를 쓸 수 있는가: wrapper와 plugin jar가 있어야 한다. */
    fun available(rootDir: Path): Boolean = wrapper(rootDir).exists() && pluginJar() != null

    fun modelFile(rootDir: Path): Path = rootDir.resolve(MODEL_FILE)

    /** 저장된 model이 모든 build 설정 파일보다 새로우면 다시 export하지 않는다. */
    fun isFresh(rootDir: Path): Boolean {
        val model = modelFile(rootDir)
        if (!model.isRegularFile()) return false
        val exported = model.getLastModifiedTime()
        return buildFiles(rootDir).none { it.getLastModifiedTime() > exported }
    }

    fun read(rootDir: Path): ProjectModel = ProjectModelFile.read(modelFile(rootDir))

    /**
     * `gradlew -q --init-script <script> [tasks] jdeltaExportModel`.
     * [tasks]로 build를 같은 Gradle 호출에 묶으면 daemon/configuration을 한 번만 쓴다.
     */
    fun export(rootDir: Path, tasks: List<String>, log: (String) -> Unit): ProjectModel {
        val jar = pluginJar() ?: throw JDeltaFailure("jdelta Gradle plugin jar not found; reinstall the jdelta distribution")
        val script = initScript(rootDir, jar)
        val command = listOf(wrapper(rootDir).toString(), "-q", "--init-script", script.toString()) + tasks + EXPORT_TASK
        log("running: ${command.joinToString(" ")}")
        val result = Processes.run(command, rootDir, onOutput = log)
        if (result.exitCode != 0) throw JDeltaFailure("Gradle model export failed with exit code ${result.exitCode}")
        return read(rootDir)
    }

    fun initScript(rootDir: Path, jar: Path): Path {
        val dir = rootDir.resolve(".jdelta/init").createDirectories()
        val path = jar.toAbsolutePath().invariantSeparatorsPathString.replace("\\", "\\\\").replace("'", "\\'")
        return dir.resolve("jdelta.init.gradle").also {
            it.writeText(
                """
                // jdelta CLI가 생성한 파일. project model export plugin을 모든 project에 적용한다.
                initscript { dependencies { classpath files('$path') } }
                allprojects { apply plugin: jdelta.gradle.JDeltaPlugin }
                """.trimIndent() + "\n",
            )
        }
    }

    private fun wrapper(rootDir: Path): Path = rootDir.resolve(if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")

    private val BUILD_FILE_NAMES = setOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "gradle.properties", "libs.versions.toml")
    private val SKIPPED_DIRS = setOf("build", "build.nosync", ".gradle", ".gradle.nosync", ".git", ".jdelta", ".idea", "node_modules", "src", "out")

    private fun buildFiles(rootDir: Path): List<Path> {
        val result = ArrayList<Path>()
        Files.walk(rootDir, 6).use { stream ->
            stream.filter { path ->
                val relative = rootDir.relativize(path)
                relative.none { it.name in SKIPPED_DIRS } && path.isRegularFile() && path.name in BUILD_FILE_NAMES
            }.forEach { result.add(it) }
        }
        return result
    }
}
