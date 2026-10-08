package jdelta.engine

import jdelta.core.Language
import jdelta.core.ModuleDependency
import jdelta.core.ModuleId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test

class ProjectModelFileTest {
    /** `jdelta-gradle-plugin`의 ExportModelTask가 쓰는 형식 (ModelExportTest가 같은 형식을 검증한다). */
    private val sample = """
        {
          "formatVersion": 1,
          "gradleVersion": "9.5.1",
          "rootDir": "/repo",
          "modules": [
            {"path": ":", "projectDir": "/repo", "languages": [], "sourceSets": [], "dependencies": [], "annotationProcessors": []},
            {"path": ":web", "projectDir": "/repo/web", "languages": ["JAVA", "KOTLIN"], "sourceSets": [{"name": "main", "test": false, "sourceDirs": ["/repo/web/src/main/java", "/repo/web/src/main/kotlin"], "classesDirs": ["/repo/web/build/classes/java/main", "/repo/web/build/classes/kotlin/main"], "resourcesDirs": ["/repo/web/build/resources/main"]}, {"name": "test", "test": true, "sourceDirs": [], "classesDirs": ["/repo/web/build/classes/java/test"], "resourcesDirs": []}], "dependencies": [{"target": ":service", "kind": "IMPLEMENTATION"}, {"target": ":core", "kind": "TEST"}], "annotationProcessors": ["org.mapstruct:mapstruct-processor:1.6.3"]}
          ]
        }
    """.trimIndent()

    @Test
    fun `reads the exported model`() {
        val file = Files.createTempFile("project", ".json").also { it.writeText(sample) }
        val model = ProjectModelFile.read(file)
        val web = model.module(ModuleId(":web"))!!
        assertThat(web.languages).containsExactlyInAnyOrder(Language.JAVA, Language.KOTLIN)
        assertThat(web.sourceSets.map { it.id.name to it.isTest }).containsExactly("main" to false, "test" to true)
        assertThat(web.sourceSets.first().classesDirs.map { it.toString() }).hasSize(2)
        assertThat(web.dependencies).containsExactly(
            ModuleDependency(ModuleId(":service"), ModuleDependency.Kind.IMPLEMENTATION),
            ModuleDependency(ModuleId(":core"), ModuleDependency.Kind.TEST),
        )
        assertThat(web.annotationProcessors).containsExactly("org.mapstruct:mapstruct-processor:1.6.3")
    }

    @Test
    fun `rejects another format version`() {
        val file = Files.createTempFile("project", ".json").also { it.writeText(sample.replace("\"formatVersion\": 1", "\"formatVersion\": 99")) }
        assertThatThrownBy { ProjectModelFile.read(file) }.hasMessageContaining("unsupported project model format 99")
    }
}
