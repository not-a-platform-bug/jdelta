package jdelta.core

/** baseline과 current 사이의 의미적 변경 하나와 그 ABI layer별 영향 profile. */
public data class SemanticDelta(
    public val subject: NodeId,
    public val kind: DeltaKind,
    public val compileImpact: ImpactLevel = ImpactLevel.NONE,
    public val binaryImpact: ImpactLevel = ImpactLevel.NONE,
    public val reflectionImpact: ImpactLevel = ImpactLevel.NONE,
    public val frameworkImpact: ImpactLevel = ImpactLevel.NONE,
    public val testImpact: ImpactLevel = ImpactLevel.NONE,
    public val confidence: Confidence,
    public val reasons: List<Reason>,
)

public data class Reason(
    public val code: ReasonCode,
    public val message: String,
    public val evidence: List<NodeId> = emptyList(),
)

/** report JSON과 metric에 나타나는 안정적인 reason 식별자. "모른다"도 code로 남는다. */
public enum class ReasonCode {
    // 변경 사실
    BODY_ONLY,
    BODY_CHANGED,
    LOCAL_CLASS_CHANGED,
    MEMBER_ADDED,
    MEMBER_REMOVED,
    DESCRIPTOR_CHANGED,
    CLASS_MOVED,
    BRIDGE_CHANGED,
    ACCESS_CHANGED,
    LINKAGE_BREAKING_ACCESS_CHANGE,
    HIERARCHY_CHANGED,
    ANNOTATION_CHANGED,
    RUNTIME_VISIBLE_ANNOTATION,
    ANNOTATION_DEFAULT_CHANGED,
    GENERIC_SIGNATURE_CHANGED,
    THROWS_CHANGED,
    PARAMETER_NAMES_CHANGED,
    CONSTANT_INLINED_AT_CALLERS,
    ENUM_ORDINAL_CHANGED,
    SEALED_HIERARCHY_CHANGED,
    RECORD_SHAPE_CHANGED,
    RESOURCE_ADDED,
    RESOURCE_REMOVED,
    RESOURCE_MODIFIED,
    KOTLIN_METADATA_CHANGED,
    KOTLIN_INLINE_BODY_CHANGED,

    // 영향 범위 판단 근거
    PACKAGE_PRIVATE_SCOPE,
    PRIVATE_SCOPE,
    EXPOSED_VIA_PUBLIC_SUBCLASS,
    ABSTRACT_METHOD_ADDED,
    OVERLOAD_RESOLUTION_MAY_CHANGE,
    NAME_RESOLUTION_MAY_CHANGE,
    KOTLIN_CALLERS_ONLY,
    COMPILE_ABI_CHANGED,
    IMPACT_PATH,
    TEST_CHANGED,
    SYNTHETIC_MEMBER,

    // 모름 / fallback
    UNPARSEABLE_CLASSFILE,
    UNEXPLAINED_FINGERPRINT_CHANGE,
    MODULE_DESCRIPTOR_CHANGED,
    KOTLIN_METADATA_NOT_ANALYZED,
    RESOURCE_CONSUMERS_UNKNOWN,
    TOOLCHAIN_CHANGED,
    BUILD_SCRIPT_CHANGED,
    DEPENDENCY_DECLARATION_CHANGED,
    SOURCE_ONLY,

    // trace / framework (v0.1 후반)
    NO_TRACE_FOR_TEST,
    KOTLIN_INLINE_CALLERS_UNKNOWN,
    FULL_SUITE_ALWAYS,
    TRACE_STALE,
    FRAMEWORK_PROFILE_MATCH,
    PROCESSOR_SENSITIVE_ANNOTATION,
    ONE_TIME_INITIALIZATION,
}
