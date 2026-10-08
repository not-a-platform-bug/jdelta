pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jdelta"

include(
    "jdelta-core",
    "jdelta-classfile",
    "jdelta-trace",
    "jdelta-impact",
    "jdelta-report",
    "jdelta-engine",
    "jdelta-agent",
    "jdelta-junit",
    "jdelta-gradle-plugin",
    "jdelta-cli",
)
