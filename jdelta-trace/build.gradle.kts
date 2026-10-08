plugins {
    id("jdelta.kotlin-library")
}

// end-to-end test가 별도 JVM에 붙이는 실제 agent/listener jar
val agentJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("jdelta-agent")) }
}
val junitListenerJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    api(project(":jdelta-core"))
    implementation(libs.kotlinx.serialization.json)
    agentJar(project(":jdelta-agent"))
    junitListenerJar(project(":jdelta-junit"))
}

tasks.test {
    val agent: FileCollection = agentJar
    val listener: FileCollection = junitListenerJar
    inputs.files(agent).withPropertyName("agentJar")
    inputs.files(listener).withPropertyName("junitListenerJar")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Djdelta.agentJar=${agent.singleFile.absolutePath}",
            "-Djdelta.junitJar=${listener.singleFile.absolutePath}",
        )
    }
}
