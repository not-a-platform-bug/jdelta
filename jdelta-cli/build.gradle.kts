plugins {
    id("jdelta.kotlin-library")
    application
}

// 배포본의 plugin/ 디렉터리에 들어가는 Gradle plugin jar. CLI classpath에는 넣지 않는다.
val gradlePlugin: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

// 배포본의 agent/ 디렉터리: test JVM에 붙는 agent와 JUnit listener
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
    implementation(project(":jdelta-engine"))
    implementation(libs.clikt.core)
    implementation(libs.kotlinx.serialization.json)
    gradlePlugin(project(":jdelta-gradle-plugin"))
    testAgent(project(":jdelta-agent"))
    junitListener(project(":jdelta-junit"))
}

application {
    applicationName = "jdelta"
    mainClass.set("jdelta.cli.MainKt")
}

distributions {
    main {
        contents {
            into("plugin") {
                from(gradlePlugin)
                rename { "jdelta-gradle-plugin.jar" }
            }
            into("agent") {
                from(testAgent)
                from(junitListener) { rename { "jdelta-junit.jar" } }
            }
        }
    }
}

// configuration cache: lambda가 script 객체를 잡지 않도록 parameter로만 받는다
fun jdeltaJarArgs(plugin: FileCollection, agent: FileCollection, listener: FileCollection) = CommandLineArgumentProvider {
    listOf(
        "-Djdelta.gradlePluginJar=${plugin.singleFile.absolutePath}",
        "-Djdelta.agentJar=${agent.singleFile.absolutePath}",
        "-Djdelta.junitJar=${listener.singleFile.absolutePath}",
    )
}

tasks.test {
    inputs.files(gradlePlugin, testAgent, junitListener).withPropertyName("jdeltaJars")
    jvmArgumentProviders += jdeltaJarArgs(gradlePlugin, testAgent, junitListener)
    useJUnitPlatform { excludeTags("e2e") }
}

// 데모 project(samples/acme-shop)를 실제 Gradle로 build/record하는 end-to-end test. 느리므로 check에 넣지 않는다.
val e2eTest = tasks.register<Test>("e2eTest") {
    description = "Runs the demo scenarios (JDELTA_DESIGN.md §21) against samples/acme-shop with a real Gradle build."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    inputs.files(gradlePlugin, testAgent, junitListener).withPropertyName("jdeltaJars")
    inputs.dir(rootProject.layout.projectDirectory.dir("samples/acme-shop")).withPropertyName("sample")
    jvmArgumentProviders += jdeltaJarArgs(gradlePlugin, testAgent, junitListener)
    systemProperty("jdelta.repoDir", rootProject.layout.projectDirectory.asFile.absolutePath)
    shouldRunAfter(tasks.test)
}
