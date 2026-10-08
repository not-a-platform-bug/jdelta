package jdelta.core

import java.nio.file.Path

/** build tool에서 export한 project 구조. Gradle 타입이 아닌 순수 data다. */
public data class ProjectModel(
    public val rootDir: Path,
    public val modules: List<ModuleModel>,
    public val toolchain: ToolchainInfo = ToolchainInfo(),
) {
    public fun module(id: ModuleId): ModuleModel? = modules.firstOrNull { it.id == id }
}

public enum class Language { JAVA, KOTLIN }

public data class ModuleModel(
    public val id: ModuleId,
    public val projectDir: Path,
    public val languages: Set<Language> = setOf(Language.JAVA),
    public val sourceSets: List<SourceSetModel> = emptyList(),
    public val dependencies: List<ModuleDependency> = emptyList(),
    public val annotationProcessors: List<String> = emptyList(),
    public val externalDependencies: List<String>? = null,
)

public data class SourceSetModel(
    public val id: SourceSetId,
    public val sourceDirs: List<Path> = emptyList(),
    public val classesDirs: List<Path>,
    public val resourcesDirs: List<Path> = emptyList(),
    public val isTest: Boolean = id.name.contains("test", ignoreCase = true),
)

public data class ModuleDependency(
    public val target: ModuleId,
    public val kind: Kind,
) {
    public enum class Kind { API, IMPLEMENTATION, COMPILE_ONLY, RUNTIME_ONLY, TEST }
}

public data class ToolchainInfo(
    public val javaVersion: String? = null,
    public val kotlinVersion: String? = null,
)
