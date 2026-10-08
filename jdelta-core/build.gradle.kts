plugins {
    id("jdelta.kotlin-library")
}

// jdelta-core는 Kotlin stdlib 외 어떤 runtime dependency도 가지면 안 된다 (ARCHITECTURE.md §1.2)
val verifyNoExternalDependencies by tasks.registering {
    val artifacts = configurations.runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }
    doLast {
        val allowed = listOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")
        val violations = artifacts.get()
            .map { it.id.componentIdentifier.displayName }
            .filter { id -> allowed.none { id.startsWith(it) } }
        check(violations.isEmpty()) { "jdelta-core must not depend on: $violations" }
    }
}

tasks.named("check") { dependsOn(verifyNoExternalDependencies) }
