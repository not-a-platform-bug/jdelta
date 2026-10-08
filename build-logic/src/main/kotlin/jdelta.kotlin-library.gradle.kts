// Kotlin module: explicit API, JVM 17 target
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("jdelta.java-conventions")
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    "testImplementation"(kotlin("test-junit5"))
    "testImplementation"("org.junit.jupiter:junit-jupiter-params")
}
