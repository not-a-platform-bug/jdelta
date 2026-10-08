package jdelta.engine

import jdelta.core.Language
import jdelta.core.ModuleDependency
import jdelta.core.ModuleId
import jdelta.core.ModuleModel
import jdelta.core.ProjectModel
import jdelta.core.SourceSetId
import jdelta.core.SourceSetModel
import jdelta.core.ToolchainInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * `jdeltaExportModel`이 쓴 `.jdelta/model/project.json`을 읽는다 (ARCHITECTURE.md §4.2).
 * 형식은 `jdelta-gradle-plugin`의 `ModuleModel`/`ExportModelTask`가 정한다.
 */
public object ProjectModelFile {
    public const val FORMAT_VERSION: Int = 1

    public fun read(file: Path): ProjectModel {
        val root = try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invalid project model $file: ${e.message}", e)
        }
        val version = root["formatVersion"]?.jsonPrimitive?.int
        require(version == FORMAT_VERSION) { "unsupported project model format $version in $file (expected $FORMAT_VERSION)" }
        val modules = root.getValue("modules").jsonArray.map { module(it.jsonObject) }
        return ProjectModel(
            rootDir = Path(root.getValue("rootDir").jsonPrimitive.content),
            modules = modules,
            toolchain = ToolchainInfo(),
        )
    }

    private fun module(o: JsonObject): ModuleModel {
        val id = ModuleId(o.getValue("path").jsonPrimitive.content)
        return ModuleModel(
            id = id,
            projectDir = Path(o.getValue("projectDir").jsonPrimitive.content),
            languages = o.strings("languages").map { Language.valueOf(it) }.toSet(),
            sourceSets = o.getValue("sourceSets").jsonArray.map { element ->
                val s = element.jsonObject
                SourceSetModel(
                    id = SourceSetId(id, s.getValue("name").jsonPrimitive.content),
                    sourceDirs = s.strings("sourceDirs").map { Path(it) },
                    classesDirs = s.strings("classesDirs").map { Path(it) },
                    resourcesDirs = s.strings("resourcesDirs").map { Path(it) },
                    isTest = s.getValue("test").jsonPrimitive.boolean,
                )
            },
            dependencies = o.getValue("dependencies").jsonArray.map { element ->
                val d = element.jsonObject
                ModuleDependency(ModuleId(d.getValue("target").jsonPrimitive.content), ModuleDependency.Kind.valueOf(d.getValue("kind").jsonPrimitive.content))
            }.distinct(),
            annotationProcessors = o.strings("annotationProcessors"),
        )
    }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key]?.jsonArray ?: emptyList<JsonElement>()).map { it.jsonPrimitive.content }
}
