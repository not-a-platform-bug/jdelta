plugins { `java-library` }

// 데모 scenario 6: annotation processor(MapStruct)가 생성하는 code
dependencies {
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
}
