plugins {
    id("jdelta.kotlin-library")
}

dependencies {
    api(project(":jdelta-core"))
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
