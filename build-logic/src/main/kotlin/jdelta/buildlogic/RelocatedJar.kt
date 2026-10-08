package jdelta.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * 자기 class와 dependency jar를 하나의 jar로 합치면서 package를 relocate한다 (shadow plugin의 최소 대체).
 * test JVM 안에서 도는 agent가 사용자의 ASM과 충돌하지 않게 한다 (ARCHITECTURE.md §8.1, D8).
 */
@CacheableTask
abstract class RelocatedJar : DefaultTask() {
    /** 자기 class directory와 합칠 dependency jar. */
    @get:Classpath
    abstract val sources: ConfigurableFileCollection

    /** 펼치지 않고 jar root에 그대로 넣는 파일(중첩 jar 등). */
    @get:org.gradle.api.tasks.InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NAME_ONLY)
    abstract val embedded: ConfigurableFileCollection

    /** [sources]에서 뺄 경로 prefix. */
    @get:Input
    abstract val excludes: org.gradle.api.provider.ListProperty<String>

    /** internal name prefix -> 새 prefix. 예: `org/objectweb/asm/` -> `jdelta/shaded/asm/`. */
    @get:Input
    abstract val relocations: MapProperty<String, String>

    @get:Input
    abstract val manifestAttributes: MapProperty<String, String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun build() {
        val relocations = relocations.get()
        val remapper = object : Remapper() {
            override fun map(internalName: String): String =
                relocations.entries.firstOrNull { internalName.startsWith(it.key) }?.let { it.value + internalName.removePrefix(it.key) } ?: internalName
        }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            manifestAttributes.get().forEach { (k, v) -> mainAttributes.putValue(k, v) }
        }
        val written = HashSet<String>()
        val target = outputJar.get().asFile
        target.parentFile.mkdirs()
        JarOutputStream(target.outputStream().buffered(), manifest).use { out ->
            val excluded = excludes.getOrElse(emptyList())
            fun add(path: String, bytes: ByteArray) {
                if (skip(path) || excluded.any { path.startsWith(it) }) return
                val renamed = if (path.endsWith(".class")) remapper.map(path.removeSuffix(".class")) + ".class" else path
                if (!written.add(renamed)) return
                val content = if (path.endsWith(".class")) relocate(bytes, remapper) else bytes
                out.putNextEntry(JarEntry(renamed).apply { time = CONSTANT_TIME })
                out.write(content)
                out.closeEntry()
            }
            for (file in sources.files.sortedBy { it.path }) {
                when {
                    file.isDirectory -> file.walkTopDown().filter { it.isFile }.sortedBy { it.path }
                        .forEach { add(it.relativeTo(file).invariantSeparatorsPath, it.readBytes()) }
                    file.isFile && file.name.endsWith(".jar") -> JarFile(file).use { jar ->
                        jar.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }
                            .forEach { entry -> add(entry.name, jar.getInputStream(entry).readBytes()) }
                    }
                }
            }
            for (file in embedded.files.sortedBy { it.name }) {
                out.putNextEntry(JarEntry(file.name).apply { time = CONSTANT_TIME })
                out.write(file.readBytes())
                out.closeEntry()
            }
        }
    }

    private fun relocate(bytes: ByteArray, remapper: Remapper): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(ClassRemapper(writer, remapper), 0)
        return writer.toByteArray()
    }

    private fun skip(path: String): Boolean =
        path == "META-INF/MANIFEST.MF" || path.endsWith("module-info.class") ||
            (path.startsWith("META-INF/") && (path.endsWith(".SF") || path.endsWith(".RSA") || path.endsWith(".DSA"))) ||
            path.startsWith("META-INF/versions/")

    private companion object {
        /** 재현 가능한 jar를 위해 entry 시각을 고정한다. */
        val CONSTANT_TIME = 315532800000L // 1980-01-01, zip 형식의 최소값
    }
}

