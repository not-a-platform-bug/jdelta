plugins {
    id("jdelta.kotlin-library")
}

dependencies {
    api(project(":jdelta-core"))
    api(project(":jdelta-classfile"))
    api(project(":jdelta-report"))
    api(project(":jdelta-trace"))
    implementation(project(":jdelta-impact"))
    implementation(libs.kotlinx.serialization.json)
}
