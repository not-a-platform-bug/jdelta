// test classpath에 들어가는 JUnit Platform listener. plain Java, runtime dependency 0개 (ARCHITECTURE.md D8).
plugins {
    id("jdelta.java-library")
}

dependencies {
    // Recorder는 실행 시 agent가 bootstrap classloader에 올린다
    compileOnly(project(":jdelta-agent"))
    compileOnly(platform(libs.junit.bom))
    compileOnly(libs.junit.platform.launcher)
}
