package jdelta.impact

import jdelta.core.DeltaKind

/** profile이 일치했을 때 test를 넓히는 방식 (ARCHITECTURE.md §6.4). */
public enum class TestExpansion {
    /** 바뀐 class(bean, controller, entity)를 실행한 test. INFERRED */
    CLASS_TRACE,

    /** 같은 module의 framework context test(`@SpringBootTest` 등). CONSERVATIVE */
    CONTEXT_TESTS,

    /** 같은 module의 web slice test(`@WebMvcTest`, `@WebFluxTest`). INFERRED */
    WEB_SLICE_TESTS,

    /** 같은 module의 persistence slice test(`@DataJpaTest`). INFERRED */
    JPA_SLICE_TESTS,

    /** 이름에 `Serializ`/`Json`이 들어간 test class. INFERRED */
    SERIALIZATION_NAMED_TESTS,

    /** annotation processor가 생성한 class를 실행한 test. CONSERVATIVE (`PROCESSOR_SENSITIVE_ANNOTATION`) */
    GENERATED_CODE_TESTS,
}

/**
 * framework 지식은 annotation descriptor를 key로 하는 데이터 표다. framework library에 의존하지 않는다.
 *
 * [sensitiveTo]가 비어 있으면 그 annotation이 붙은 subject의 모든 delta에 적용한다.
 */
public data class FrameworkProfile(
    public val framework: String,
    public val annotations: Set<String>,
    public val sensitiveTo: Set<DeltaKind>,
    public val expansions: Set<TestExpansion>,
    public val processorSensitive: Boolean = false,
)

public object FrameworkProfiles {
    private fun d(fqcn: String) = "L" + fqcn.replace('.', '/') + ";"

    private val API_KINDS = setOf(
        DeltaKind.METHOD_ADDED, DeltaKind.METHOD_REMOVED, DeltaKind.METHOD_DESCRIPTOR_CHANGED, DeltaKind.METHOD_ACCESS_CHANGED,
        DeltaKind.METHOD_ANNOTATION_CHANGED, DeltaKind.METHOD_PARAMETERS_CHANGED, DeltaKind.GENERIC_SIGNATURE_CHANGED,
        DeltaKind.CLASS_ANNOTATION_CHANGED, DeltaKind.CLASS_HIERARCHY_CHANGED, DeltaKind.CLASS_ADDED, DeltaKind.CLASS_REMOVED,
        DeltaKind.CLASS_ACCESS_CHANGED,
    )
    private val SHAPE_KINDS = setOf(
        DeltaKind.FIELD_ADDED, DeltaKind.FIELD_REMOVED, DeltaKind.FIELD_DESCRIPTOR_CHANGED, DeltaKind.FIELD_ACCESS_CHANGED,
        DeltaKind.FIELD_ANNOTATION_CHANGED, DeltaKind.METHOD_ADDED, DeltaKind.METHOD_REMOVED, DeltaKind.METHOD_DESCRIPTOR_CHANGED,
        DeltaKind.METHOD_ANNOTATION_CHANGED, DeltaKind.METHOD_PARAMETERS_CHANGED, DeltaKind.CLASS_ANNOTATION_CHANGED,
        DeltaKind.RECORD_COMPONENTS_CHANGED, DeltaKind.ENUM_CONSTANT_ORDER_CHANGED, DeltaKind.GENERIC_SIGNATURE_CHANGED,
        DeltaKind.KOTLIN_METADATA_CHANGED,
    )

    public val SPRING_TEST_ANNOTATIONS: Set<String> = setOf(
        "org.springframework.boot.test.context.SpringBootTest",
        "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest",
        "org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest",
        "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest",
        "org.springframework.boot.test.autoconfigure.jdbc.JdbcTest",
        "org.springframework.boot.test.autoconfigure.json.JsonTest",
        "org.springframework.test.context.ContextConfiguration",
        "org.springframework.test.context.junit.jupiter.SpringJUnitConfig",
        "org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig",
    ).map { d(it) }.toSet()

    public val WEB_SLICE_ANNOTATIONS: Set<String> = setOf(
        "org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest",
        "org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest",
    ).map { d(it) }.toSet()

    public val JPA_SLICE_ANNOTATIONS: Set<String> = setOf(
        "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest",
    ).map { d(it) }.toSet()

    /** v0.1 내장 profile. */
    public val BUILT_IN: List<FrameworkProfile> = listOf(
        FrameworkProfile(
            "spring-core",
            setOf(
                "org.springframework.stereotype.Component", "org.springframework.stereotype.Service",
                "org.springframework.stereotype.Repository", "org.springframework.stereotype.Controller",
                "org.springframework.context.annotation.Configuration", "org.springframework.context.annotation.Bean",
                "org.springframework.context.annotation.Conditional", "org.springframework.context.annotation.Profile",
                "org.springframework.context.annotation.Import", "org.springframework.context.annotation.Primary",
                "org.springframework.beans.factory.annotation.Autowired", "org.springframework.beans.factory.annotation.Qualifier",
                "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
                "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
            ).map { d(it) }.toSet(),
            API_KINDS,
            setOf(TestExpansion.CLASS_TRACE, TestExpansion.CONTEXT_TESTS),
        ),
        FrameworkProfile(
            "spring-web",
            setOf(
                "org.springframework.web.bind.annotation.RestController", "org.springframework.web.bind.annotation.RequestMapping",
                "org.springframework.web.bind.annotation.GetMapping", "org.springframework.web.bind.annotation.PostMapping",
                "org.springframework.web.bind.annotation.PutMapping", "org.springframework.web.bind.annotation.DeleteMapping",
                "org.springframework.web.bind.annotation.PatchMapping", "org.springframework.web.bind.annotation.RequestBody",
                "org.springframework.web.bind.annotation.PathVariable", "org.springframework.web.bind.annotation.RequestParam",
                "org.springframework.web.bind.annotation.ResponseStatus", "org.springframework.web.bind.annotation.ExceptionHandler",
            ).map { d(it) }.toSet(),
            API_KINDS,
            setOf(TestExpansion.CLASS_TRACE, TestExpansion.WEB_SLICE_TESTS),
        ),
        FrameworkProfile(
            "spring-tx-aop",
            setOf(
                "org.springframework.transaction.annotation.Transactional", "org.springframework.scheduling.annotation.Async",
                "org.springframework.cache.annotation.Cacheable", "org.springframework.cache.annotation.CacheEvict",
                "org.springframework.retry.annotation.Retryable", "jakarta.transaction.Transactional",
            ).map { d(it) }.toSet(),
            API_KINDS,
            setOf(TestExpansion.CLASS_TRACE),
        ),
        FrameworkProfile(
            "spring-config",
            setOf(
                "org.springframework.boot.context.properties.ConfigurationProperties", "org.springframework.beans.factory.annotation.Value",
            ).map { d(it) }.toSet(),
            SHAPE_KINDS + API_KINDS,
            setOf(TestExpansion.CLASS_TRACE, TestExpansion.CONTEXT_TESTS),
        ),
        FrameworkProfile(
            "jackson",
            setOf(
                "com.fasterxml.jackson.annotation.JsonProperty", "com.fasterxml.jackson.annotation.JsonCreator",
                "com.fasterxml.jackson.annotation.JsonIgnore", "com.fasterxml.jackson.annotation.JsonFormat",
                "com.fasterxml.jackson.annotation.JsonInclude", "com.fasterxml.jackson.annotation.JsonTypeInfo",
                "com.fasterxml.jackson.annotation.JsonSubTypes", "com.fasterxml.jackson.annotation.JsonAlias",
                "com.fasterxml.jackson.databind.annotation.JsonSerialize", "com.fasterxml.jackson.databind.annotation.JsonDeserialize",
            ).map { d(it) }.toSet(),
            SHAPE_KINDS,
            setOf(TestExpansion.CLASS_TRACE, TestExpansion.SERIALIZATION_NAMED_TESTS),
        ),
        FrameworkProfile(
            "jpa",
            listOf("jakarta.persistence", "javax.persistence").flatMap { p ->
                listOf("Entity", "Table", "Id", "Column", "OneToMany", "ManyToOne", "OneToOne", "ManyToMany", "JoinColumn", "Enumerated", "Embedded", "Embeddable", "Version", "Transient")
                    .map { d("$p.$it") }
            }.toSet(),
            SHAPE_KINDS,
            setOf(TestExpansion.CLASS_TRACE, TestExpansion.JPA_SLICE_TESTS),
        ),
        FrameworkProfile(
            "mapstruct",
            setOf("org.mapstruct.Mapper", "org.mapstruct.Mapping", "org.mapstruct.Mappings", "org.mapstruct.MapperConfig").map { d(it) }.toSet(),
            emptySet(),
            setOf(TestExpansion.CLASS_TRACE, TestExpansion.GENERATED_CODE_TESTS),
            processorSensitive = true,
        ),
        FrameworkProfile(
            // Lombok annotation은 SOURCE retention이라 classfile에 남지 않는다. 생성된 member의 변화로만 보인다.
            "lombok",
            setOf("lombok.Data", "lombok.Value", "lombok.Builder", "lombok.Getter", "lombok.Setter").map { d(it) }.toSet(),
            emptySet(),
            setOf(TestExpansion.CLASS_TRACE),
            processorSensitive = true,
        ),
    )

    /** `@Enumerated(EnumType.ORDINAL)`(기본값)와 enum 순서 변경의 조합은 저장된 data의 의미를 바꾼다. */
    public val ENUMERATED: Set<String> = setOf(d("jakarta.persistence.Enumerated"), d("javax.persistence.Enumerated"))
}

public object FrameworkMatch {
    /** [annotations]가 붙은 subject의 [kind] 변경에 해당하는 profile. */
    public fun matches(kind: DeltaKind, annotations: Set<String>): List<FrameworkProfile> =
        FrameworkProfiles.BUILT_IN.filter { p -> p.annotations.any { it in annotations } && (p.sensitiveTo.isEmpty() || kind in p.sensitiveTo) }

    /** 생성 code를 바꿀 수 있는 변경. body만 바뀐 경우는 생성물에 영향이 없다. */
    public fun changesProcessorInput(kind: DeltaKind): Boolean = kind !in setOf(
        DeltaKind.METHOD_BODY_CHANGED, DeltaKind.KOTLIN_INLINE_BODY_CHANGED, DeltaKind.SOURCE_ONLY_CHANGED,
        DeltaKind.CONSTANT_VALUE_CHANGED, DeltaKind.METHOD_EXCEPTIONS_CHANGED,
    )

    public fun simpleName(descriptor: String): String = "@" + descriptor.removePrefix("L").removeSuffix(";").substringAfterLast('/').substringAfterLast('$')
}
