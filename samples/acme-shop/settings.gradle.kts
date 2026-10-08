// jdelta 데모 project (JDELTA_DESIGN.md §21). core(Java) <- pricing(Kotlin) <- app(Kotlin)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "acme-shop"
include("core", "pricing", "app")
