// Gradle 안에서 실행되는 plugin. Gradle이 내장한 Kotlin stdlib와 충돌하지 않도록 plain Java, runtime dependency 0개 (ARCHITECTURE.md D13).
plugins {
    id("jdelta.java-conventions")
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("jdelta") {
            id = "io.github.not-a-platform-bug.jdelta"
            implementationClass = "jdelta.gradle.JDeltaPlugin"
            displayName = "jdelta"
            description = "Exports the project model, records test traces and runs impacted tests first"
        }
    }
}

// TestKit test가 실제 CLI(javaexec)와 agent를 쓰도록 넘긴다
val cliRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
val testAgent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("jdelta-agent")) }
}
val junitListener: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    cliRuntime(project(":jdelta-cli"))
    testAgent(project(":jdelta-agent"))
    junitListener(project(":jdelta-junit"))
}

fun pluginTestArgs(pluginJar: Provider<RegularFile>, cli: FileCollection, agent: FileCollection, listener: FileCollection) = CommandLineArgumentProvider {
    listOf(
        "-Djdelta.gradlePluginJar=${pluginJar.get().asFile.absolutePath}",
        "-Djdelta.cliClasspath=${cli.files.joinToString(File.pathSeparator) { it.absolutePath }}",
        "-Djdelta.agentJar=${agent.singleFile.absolutePath}",
        "-Djdelta.junitJar=${listener.singleFile.absolutePath}",
    )
}

tasks.test {
    // init script 경로(CLI가 쓰는 방식)를 그대로 검증하기 위해 실제 plugin jar를 넘긴다
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(pluginJar).withPropertyName("pluginJar")
    inputs.files(cliRuntime, testAgent, junitListener).withPropertyName("jdeltaRuntime")
    jvmArgumentProviders += pluginTestArgs(pluginJar, cliRuntime, testAgent, junitListener)
}
