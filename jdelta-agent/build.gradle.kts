import jdelta.build.RelocatedJar

// test JVM 안에서 실행된다. plain Java, ASM은 relocate해서 jar에 넣는다 (ARCHITECTURE.md D8, §8.1).
plugins {
    id("jdelta.java-library")
}

val shaded: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    compileOnly(libs.asm)
    shaded(libs.asm)
    testImplementation(libs.asm)
}

// bootstrap classloader에 올릴 runtime(jdelta.runtime)만 담은 jar. agent jar 안에 중첩된다.
// agent 자신의 class까지 bootstrap에 올리면 같은 package가 두 classloader로 갈라져 package-private 접근이 깨진다.
val runtimeJar = tasks.register<Jar>("runtimeJar") {
    archiveFileName.set("jdelta-runtime.jar")
    destinationDirectory.set(layout.buildDirectory.dir("runtime"))
    from(sourceSets.main.map { it.output.classesDirs }) { include("jdelta/runtime/**") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val agentJar = tasks.register<RelocatedJar>("agentJar") {
    description = "Builds the -javaagent jar with ASM relocated to jdelta.shaded.asm."
    sources.from(sourceSets.main.map { it.output.classesDirs }, shaded)
    excludes.add("jdelta/runtime/")
    embedded.from(runtimeJar)
    relocations.put("org/objectweb/asm/", "jdelta/shaded/asm/")
    manifestAttributes.putAll(
        mapOf(
            "Premain-Class" to "jdelta.agent.Agent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false",
            "Implementation-Title" to "jdelta-agent",
            "Implementation-Version" to project.version.toString(),
        ),
    )
    outputJar.set(layout.buildDirectory.file("libs/jdelta-agent.jar"))
}

// 다른 module(trace test, CLI 배포본)이 쓰는 실행용 agent jar
val agent: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("jdelta-agent")) }
}
artifacts.add(agent.name, agentJar)

tasks.assemble { dependsOn(agentJar) }
