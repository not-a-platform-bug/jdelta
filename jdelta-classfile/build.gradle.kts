plugins {
    id("jdelta.kotlin-library")
}

dependencies {
    api(project(":jdelta-core"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.kotlin.metadata.jvm)

    // Kotlin fixture를 실제 kotlinc로 compile한다 (ARCHITECTURE.md §13.1)
    testImplementation(libs.kotlin.compiler.embeddable)
}
