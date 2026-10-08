package jdelta.classfile

import jdelta.core.ClassId
import jdelta.core.SourceSetId

/** classfile 하나에서 추출한 의미 모델. ARCHITECTURE.md §3.1. */
public data class ClassSnapshot(
    public val id: ClassId,
    public val access: Int,
    public val superName: String?,
    public val interfaces: List<String>,
    public val signature: String?,
    public val classfileVersion: Int,
    /** `SourceFile` attribute (예: `Foo.java`). source 파일과 classfile을 잇는다 (§4.5). */
    public val sourceFile: String?,
    public val annotations: List<AnnotationInfo>,
    public val fields: List<FieldSnapshot>,
    public val methods: List<MethodSnapshot>,
    public val recordComponents: List<RecordComponentSnapshot>,
    public val permittedSubclasses: List<String>,
    /** nested class일 때 InnerClasses attribute의 자기 자신 entry. 실제 private/protected/static flag가 여기 있다. */
    public val ownInnerClassEntry: InnerClassEntry?,
    /** 이 class 안에서 선언된 local/anonymous class의 internal name. */
    public val localClassNames: Set<String>,
    public val enclosingMethod: EnclosingMethodRef?,
    /** `kotlin.Metadata` annotation의 원본 canonical hash. Kotlin class가 아니면 null. */
    public val kotlinMetadataHash: String?,
    /** 해석한 Kotlin metadata. Kotlin class가 아니거나 해석하지 못했으면 null (§3.5). */
    public val kotlin: KotlinClassInfo?,
    public val fingerprints: ClassFingerprints,
) {
    public val internalName: String get() = id.internalName

    /** anonymous 또는 local class. 이름 번호가 불안정하므로 enclosing method 단위로 비교한다 (§3.4). */
    public val isLocalOrAnonymous: Boolean get() = ownInnerClassEntry != null && ownInnerClassEntry.outerName == null

    /** compiler가 만든 class (javac switch map `Foo$1`, kotlinc `Foo$WhenMappings` 등). */
    public val isSynthetic: Boolean get() = (access and org.objectweb.asm.Opcodes.ACC_SYNTHETIC) != 0

    /**
     * 존재와 이름이 다른 code의 body에 달린 class. 이름 단위로 비교하지 않고 enclosing class/method 그룹으로 비교한다 (§3.4).
     */
    public val isBodyDependent: Boolean get() = isLocalOrAnonymous || isSynthetic

    /** body-dependent class가 속한 class. EnclosingMethod가 없으면 InnerClasses나 이름에서 유추한다. */
    public val enclosingClassName: String?
        get() = enclosingMethod?.owner
            ?: ownInnerClassEntry?.outerName?.takeIf { isSynthetic }
            ?: if (isBodyDependent) internalName.substringBeforeLast('$') else null

    public val packageName: String get() = internalName.substringBeforeLast('/', "")

    public val isKotlin: Boolean get() = kotlinMetadataHash != null

    /** fingerprint와 비교에 쓰는 Kotlin 선언 hash. 해석하지 못했으면 원본 metadata hash를 쓴다. */
    public val kotlinViewHash: String? get() = kotlin?.viewHash ?: kotlinMetadataHash
}

/**
 * [values]는 element 이름순으로 정렬된 canonical 표현이다.
 * [typeTarget]이 있으면 type annotation이다 (`<typeRef>:<typePath>`).
 */
public data class AnnotationInfo(
    public val descriptor: String,
    public val visible: Boolean,
    public val values: String,
    public val typeTarget: String? = null,
) {
    public val canonical: String
        get() = buildString {
            if (typeTarget != null) append("type[").append(typeTarget).append("]")
            append(if (visible) "@" else "@invisible:").append(descriptor).append('(').append(values).append(')')
        }
}

public data class FieldSnapshot(
    public val name: String,
    public val descriptor: String,
    public val access: Int,
    public val signature: String?,
    /** `ConstantValue` attribute. type tag 포함 canonical 값 (`I:42`, `S:text`). */
    public val constantValue: String?,
    public val annotations: List<AnnotationInfo>,
) {
    public val key: String get() = "$name:$descriptor"
}

public enum class MethodRole {
    NORMAL,

    /** lambda body. 소유 method의 body fingerprint에 접힌다. */
    LAMBDA_BODY,

    /** 컴파일러 생성 bridge. binary ABI에만 포함한다. */
    BRIDGE,

    /** `access$000` 같은 synthetic accessor. */
    SYNTHETIC_ACCESSOR,

    /** `$values()`, `$deserializeLambda$` 같은 컴파일러 지원 method. */
    COMPILER_SUPPORT,
}

public data class MethodSnapshot(
    public val name: String,
    public val descriptor: String,
    public val access: Int,
    public val signature: String?,
    public val exceptions: List<String>,
    public val annotations: List<AnnotationInfo>,
    public val parameterAnnotations: List<List<AnnotationInfo>>,
    /** `MethodParameters` attribute (javac `-parameters`). 없으면 null. */
    public val parameterNames: List<String>?,
    public val annotationDefault: String?,
    /** abstract/native면 null. */
    public val bodyHash: String?,
    public val role: MethodRole,
    /** [role]이 [MethodRole.LAMBDA_BODY]일 때 최종 소유 method의 `name+descriptor`. */
    public val foldedInto: String?,
    public val references: MethodReferences,
) {
    public val key: String get() = name + descriptor

    public val isSynthetic: Boolean get() = (access and org.objectweb.asm.Opcodes.ACC_SYNTHETIC) != 0
}

/** graph edge 재료 (§5.2). 값은 `owner.name+descriptor` / internal name 문자열. */
public data class MethodReferences(
    public val calls: Set<String> = emptySet(),
    public val fieldReads: Set<String> = emptySet(),
    public val fieldWrites: Set<String> = emptySet(),
    public val types: Set<String> = emptySet(),
    /** 이 method에 inline된 Kotlin inline function (`owner.name`, owner를 모르면 `?.name`). §3.6 규칙 3. */
    public val inlinedFunctions: Set<String> = emptySet(),
)

public data class RecordComponentSnapshot(
    public val name: String,
    public val descriptor: String,
    public val signature: String?,
    public val annotations: List<AnnotationInfo>,
)

public data class InnerClassEntry(
    public val name: String,
    public val outerName: String?,
    public val innerName: String?,
    public val access: Int,
)

public data class EnclosingMethodRef(
    public val owner: String,
    public val name: String?,
    public val descriptor: String?,
) {
    public val methodKey: String? get() = if (name != null && descriptor != null) name + descriptor else null
}

/** ABI layer별 fingerprint (§3.2). [content]는 이름 번호와 무관한 전체 내용 hash. */
public data class ClassFingerprints(
    public val compilePublic: String,
    public val compilePackage: String,
    public val binary: String,
    public val reflection: String,
    public val content: String,
)

/** source set 하나의 output. */
public data class SourceSetSnapshot(
    public val id: SourceSetId,
    public val classes: Map<String, ClassSnapshot>,
    /** parse하지 못한 classfile: internal name(또는 상대 경로) -> byte hash */
    public val unparseable: Map<String, String> = emptyMap(),
    /** 상대 경로 -> content hash */
    public val resources: Map<String, String> = emptyMap(),
    /** `module-info.class` byte hash */
    public val moduleDescriptorHash: String? = null,
)

public data class ProjectSnapshot(
    public val sourceSets: List<SourceSetSnapshot>,
) {
    public val classCount: Int get() = sourceSets.sumOf { it.classes.size }

    public fun sourceSet(id: SourceSetId): SourceSetSnapshot? = sourceSets.firstOrNull { it.id == id }
}
