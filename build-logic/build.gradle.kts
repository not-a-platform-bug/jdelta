plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    // RelocatedJar task (agent jar 안의 ASM relocate)
    implementation(libs.asm)
    implementation(libs.asm.commons)
}
