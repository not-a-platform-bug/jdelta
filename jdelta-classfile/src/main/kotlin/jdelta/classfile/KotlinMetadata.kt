package jdelta.classfile

import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmValueParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.MemberKind
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isConst
import kotlin.metadata.isCrossinline
import kotlin.metadata.isData
import kotlin.metadata.isDefinitelyNonNull
import kotlin.metadata.isDelegated
import kotlin.metadata.isExpect
import kotlin.metadata.isExternal
import kotlin.metadata.isFunInterface
import kotlin.metadata.isInfix
import kotlin.metadata.isInline
import kotlin.metadata.isInner
import kotlin.metadata.isLateinit
import kotlin.metadata.isNoinline
import kotlin.metadata.isNullable
import kotlin.metadata.isOperator
import kotlin.metadata.isReified
import kotlin.metadata.isSuspend
import kotlin.metadata.isTailrec
import kotlin.metadata.isValue
import kotlin.metadata.isVar
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility
import org.objectweb.asm.tree.AnnotationNode

public enum class KotlinClassKind { CLASS, FILE_FACADE, MULTI_FILE_FACADE, MULTI_FILE_PART, SYNTHETIC, UNKNOWN }

public enum class KotlinVisibility {
    PUBLIC,
    PROTECTED,
    INTERNAL,
    PRIVATE,
    LOCAL,
    ;

    /** compile ABI 관점의 노출 범위. internal은 JVM에서 public이지만 module 밖 Kotlin code는 볼 수 없다 (§3.6 규칙 1). */
    internal val scope: Scope
        get() = when (this) {
            PUBLIC, PROTECTED -> Scope.PUBLIC
            // private top-level class는 같은 file의 다른 class가 쓸 수 있으므로 module 안까지로 본다
            INTERNAL, PRIVATE, LOCAL -> Scope.PACKAGE
        }
}

public enum class KotlinDeclarationKind { FUNCTION, CONSTRUCTOR, PROPERTY, TYPE_ALIAS }

/**
 * metadata에 있는 Kotlin 선언 하나.
 * [canonical]은 JVM descriptor로 드러나지 않는 Kotlin 수준의 선언(nullability, 기본값, parameter 이름, modifier 등)을 사람이 읽을 수 있게 쓴 것이다.
 */
public data class KotlinDeclaration(
    public val key: String,
    public val kind: KotlinDeclarationKind,
    public val visibility: KotlinVisibility,
    public val isInline: Boolean,
    /** 이 선언이 만든 JVM method의 `name+descriptor`. */
    public val jvmMethods: List<String>,
    /** backing field 이름. */
    public val jvmField: String?,
    public val canonical: String,
)

/** `kotlin.Metadata`를 해석한 결과 (ARCHITECTURE.md §3.6). */
public data class KotlinClassInfo(
    public val kind: KotlinClassKind,
    public val metadataVersion: String,
    /** [KotlinClassKind.CLASS]일 때 class 자신의 visibility. */
    public val visibility: KotlinVisibility?,
    /** class 수준 선언(kind, modifier, type parameter, supertype, companion, sealed subclass, enum entry). */
    public val header: String,
    public val declarations: Map<String, KotlinDeclaration>,
    /** companion object의 simple name. */
    public val companionObject: String? = null,
) {
    /** Kotlin 관점의 선언 전체 hash. metadata version, module name, string table 순서와는 무관하다. */
    public val viewHash: String by lazy {
        Hasher().add(kind.name).add(header)
            .addAll(declarations.values.map { "${it.key}=${it.canonical}" })
            .hex()
    }

    private val byMethod: Map<String, KotlinDeclaration> by lazy {
        declarations.values.flatMap { d -> d.jvmMethods.map { it to d } }.toMap()
    }
    private val byField: Map<String, KotlinDeclaration> by lazy {
        declarations.values.filter { it.jvmField != null }.associateBy { it.jvmField!! }
    }

    public fun declarationOfMethod(key: String): KotlinDeclaration? = byMethod[key]

    public fun declarationOfField(name: String): KotlinDeclaration? = byField[name]

    /** inline function 또는 inline property accessor인 JVM method인가. */
    public fun isInlineMethod(key: String): Boolean = byMethod[key]?.isInline == true
}

/**
 * `kotlin.Metadata` annotation -> [KotlinClassInfo]. 읽지 못하면 null을 돌려준다 (§3.5).
 * context receiver/parameter는 실험 기능이라 해석하지 않는다. JVM descriptor에 parameter로 나타나므로 Java 규칙이 잡는다.
 */
internal object KotlinMetadataReader {
    fun read(annotation: AnnotationNode): KotlinClassInfo? {
        val values = annotation.values.orEmpty().chunked(2).associate { (k, v) -> k as String to v }
        val metadataVersion = (values["mv"] as? List<*>)?.joinToString(".") ?: "?"
        val metadata = Metadata(
            values["k"] as? Int,
            (values["mv"] as? List<*>)?.map { it as Int }?.toIntArray(),
            (values["d1"] as? List<*>)?.map { it as String }?.toTypedArray(),
            (values["d2"] as? List<*>)?.map { it as String }?.toTypedArray(),
            values["xs"] as? String,
            values["pn"] as? String,
            values["xi"] as? Int,
        )
        return try {
            when (val parsed = KotlinClassMetadata.readLenient(metadata)) {
                is KotlinClassMetadata.Class -> classInfo(parsed.kmClass, metadataVersion)
                is KotlinClassMetadata.FileFacade -> containerInfo(KotlinClassKind.FILE_FACADE, parsed.kmPackage, metadataVersion)
                is KotlinClassMetadata.MultiFileClassPart -> containerInfo(KotlinClassKind.MULTI_FILE_PART, parsed.kmPackage, metadataVersion)
                is KotlinClassMetadata.MultiFileClassFacade ->
                    KotlinClassInfo(KotlinClassKind.MULTI_FILE_FACADE, metadataVersion, null, "parts=${parsed.partClassNames.sorted()}", emptyMap())
                is KotlinClassMetadata.SyntheticClass -> KotlinClassInfo(KotlinClassKind.SYNTHETIC, metadataVersion, null, "", emptyMap())
                else -> KotlinClassInfo(KotlinClassKind.UNKNOWN, metadataVersion, null, "", emptyMap())
            }
        } catch (_: IllegalArgumentException) {
            null // 지원하지 않는 metadata version 또는 손상된 metadata
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun classInfo(c: KmClass, version: String): KotlinClassInfo {
        val names = TypeParameterNames(c.typeParameters)
        val header = buildString {
            append(c.visibility.render()).append(' ').append(c.modality.render()).append(' ')
            append(c.kind.name.lowercase())
            if (c.isData) append(" data")
            if (c.isValue) append(" value")
            if (c.isInner) append(" inner")
            if (c.isFunInterface) append(" fun")
            if (c.isExpect) append(" expect")
            if (c.isExternal) append(" external")
            append(' ').append(c.name)
            append(typeParameters(c.typeParameters, names))
            append(" : ").append(c.supertypes.joinToString(", ") { render(it, names) })
            c.companionObject?.let { append(" companion=").append(it) }
            if (c.sealedSubclasses.isNotEmpty()) append(" sealed=").append(c.sealedSubclasses.sorted())
            if (c.kind == ClassKind.ENUM_CLASS) append(" entries=").append(c.kmEnumEntries.map { it.name })
            c.inlineClassUnderlyingPropertyName?.let { append(" underlying=").append(it) }
            c.inlineClassUnderlyingType?.let { append(":").append(render(it, names)) }
        }
        val declarations = members(c, names) + c.constructors.map { constructor(it, names) }
        return KotlinClassInfo(KotlinClassKind.CLASS, version, kotlinVisibility(c.visibility), header, declarations.sortedByKey(), c.companionObject)
    }

    private fun containerInfo(kind: KotlinClassKind, p: KmDeclarationContainer, version: String): KotlinClassInfo =
        KotlinClassInfo(kind, version, null, "", members(p, TypeParameterNames(emptyList())).sortedByKey())

    private fun members(container: KmDeclarationContainer, names: TypeParameterNames): List<KotlinDeclaration> =
        container.functions.map { function(it, names) } +
            container.properties.map { property(it, names) } +
            container.typeAliases.map { typeAlias(it, names) }

    private fun List<KotlinDeclaration>.sortedByKey(): Map<String, KotlinDeclaration> =
        sortedBy { it.key }.associateBy { it.key }

    private fun function(f: KmFunction, outer: TypeParameterNames): KotlinDeclaration {
        val names = outer + f.typeParameters
        val jvm = f.signature?.let { it.name + it.descriptor }
        val canonical = buildString {
            append(f.visibility.render()).append(' ').append(f.modality.render()).append(' ')
            append(modifiers(f))
            append("fun").append(typeParameters(f.typeParameters, names)).append(' ')
            f.receiverParameterType?.let { append(render(it, names)).append('.') }
            append(f.name)
            append(parameters(f.valueParameters, names))
            append(": ").append(render(f.returnType, names))
            append(memberKind(f.kind))
        }
        return KotlinDeclaration(
            key = "fun ${jvm ?: f.name}",
            kind = KotlinDeclarationKind.FUNCTION,
            visibility = kotlinVisibility(f.visibility),
            isInline = f.isInline,
            jvmMethods = listOfNotNull(jvm),
            jvmField = null,
            canonical = canonical,
        )
    }

    private fun modifiers(f: KmFunction) = buildString {
        if (f.isOperator) append("operator ")
        if (f.isInfix) append("infix ")
        if (f.isInline) append("inline ")
        if (f.isTailrec) append("tailrec ")
        if (f.isExternal) append("external ")
        if (f.isSuspend) append("suspend ")
        if (f.isExpect) append("expect ")
    }

    private fun constructor(c: KmConstructor, names: TypeParameterNames): KotlinDeclaration {
        val jvm = c.signature?.let { it.name + it.descriptor }
        return KotlinDeclaration(
            key = "constructor ${jvm ?: c.valueParameters.size}",
            kind = KotlinDeclarationKind.CONSTRUCTOR,
            visibility = kotlinVisibility(c.visibility),
            isInline = false,
            jvmMethods = listOfNotNull(jvm),
            jvmField = null,
            canonical = c.visibility.render() + " constructor" + parameters(c.valueParameters, names),
        )
    }

    private fun property(p: KmProperty, outer: TypeParameterNames): KotlinDeclaration {
        val names = outer + p.typeParameters
        val getter = p.getterSignature?.let { it.name + it.descriptor }
        val setter = p.setterSignature?.let { it.name + it.descriptor }
        val receiver = p.receiverParameterType?.let { render(it, names) }
        val canonical = buildString {
            append(p.visibility.render()).append(' ').append(p.modality.render()).append(' ')
            if (p.isConst) append("const ")
            if (p.isLateinit) append("lateinit ")
            if (p.isExternal) append("external ")
            if (p.isExpect) append("expect ")
            append(if (p.isVar) "var" else "val").append(typeParameters(p.typeParameters, names)).append(' ')
            receiver?.let { append(it).append('.') }
            append(p.name).append(": ").append(render(p.returnType, names))
            if (p.isDelegated) append(" by delegate")
            append(" get=").append(p.getter.visibility.render()).append(if (p.getter.isInline) " inline" else "")
            p.setter?.let { append(" set=").append(it.visibility.render()).append(if (it.isInline) " inline" else "") }
            p.setterParameter?.let { append(" setParam=").append(it.name) }
            append(memberKind(p.kind))
        }
        return KotlinDeclaration(
            key = "property " + (receiver?.let { "$it." } ?: "") + p.name,
            kind = KotlinDeclarationKind.PROPERTY,
            visibility = kotlinVisibility(p.visibility),
            isInline = p.getter.isInline || p.setter?.isInline == true,
            jvmMethods = listOfNotNull(getter, setter),
            jvmField = p.fieldSignature?.name,
            canonical = canonical,
        )
    }

    private fun typeAlias(t: KmTypeAlias, outer: TypeParameterNames): KotlinDeclaration {
        val names = outer + t.typeParameters
        return KotlinDeclaration(
            key = "typealias ${t.name}",
            kind = KotlinDeclarationKind.TYPE_ALIAS,
            visibility = kotlinVisibility(t.visibility),
            isInline = false,
            jvmMethods = emptyList(),
            jvmField = null,
            canonical = "${t.visibility.render()} typealias ${t.name}${typeParameters(t.typeParameters, names)} = ${render(t.underlyingType, names)}",
        )
    }

    private fun parameters(params: List<KmValueParameter>, names: TypeParameterNames): String =
        params.joinToString(", ", "(", ")") { p ->
            buildString {
                if (p.isCrossinline) append("crossinline ")
                if (p.isNoinline) append("noinline ")
                if (p.varargElementType != null) append("vararg ")
                append(p.name).append(": ").append(render(p.varargElementType ?: p.type, names))
                if (p.declaresDefaultValue) append(" = default")
            }
        }

    private fun typeParameters(params: List<KmTypeParameter>, names: TypeParameterNames): String {
        if (params.isEmpty()) return ""
        return params.joinToString(", ", "<", ">") { t ->
            buildString {
                if (t.isReified) append("reified ")
                when (t.variance) {
                    KmVariance.IN -> append("in ")
                    KmVariance.OUT -> append("out ")
                    KmVariance.INVARIANT -> {}
                }
                append(t.name)
                if (t.upperBounds.isNotEmpty()) append(" : ").append(t.upperBounds.joinToString(" & ") { render(it, names) })
            }
        }
    }

    /** type alias 약어(abbreviation)는 source 표기일 뿐이므로 펼친 type만 쓴다. */
    private fun render(t: KmType, names: TypeParameterNames): String = buildString {
        if (t.isSuspend) append("suspend ")
        t.outerType?.let { append(render(it, names)).append('.') }
        append(
            when (val c = t.classifier) {
                is KmClassifier.Class -> c.name
                is KmClassifier.TypeAlias -> c.name
                is KmClassifier.TypeParameter -> names.name(c.id)
            },
        )
        if (t.arguments.isNotEmpty()) {
            append(
                t.arguments.joinToString(", ", "<", ">") { arg ->
                    val type = arg.type ?: return@joinToString "*"
                    when (arg.variance) {
                        KmVariance.IN -> "in "
                        KmVariance.OUT -> "out "
                        else -> ""
                    } + render(type, names)
                },
            )
        }
        if (t.isNullable) append('?')
        if (t.isDefinitelyNonNull) append(" & Any")
        t.flexibleTypeUpperBound?.let { append("..").append(render(it.type, names)) }
    }

    /** 직접 선언한 member는 표시하지 않는다. data class `copy`/`componentN` 같은 synthesized member만 표시한다. */
    private fun memberKind(kind: MemberKind) = if (kind == MemberKind.DECLARATION) "" else " [${kind.name.lowercase()}]"

    private fun Visibility.render() = name.lowercase()

    private fun Modality.render() = name.lowercase()

    /**
     * type은 type parameter를 id로 참조한다. report에서 읽을 수 있도록 선언 이름으로 바꾸고, 찾지 못하면 id를 쓴다.
     * 그래서 type parameter 이름만 바꿔도 선언 변경으로 보고된다(source 호환이지만 보수적으로 둔다).
     */
    private class TypeParameterNames(params: List<KmTypeParameter>, parent: Map<Int, String> = emptyMap()) {
        private val byId: Map<Int, String> = parent + params.associate { it.id to it.name }

        operator fun plus(params: List<KmTypeParameter>) = TypeParameterNames(params, byId)

        fun name(id: Int): String = byId[id] ?: "#$id"
    }
}

private fun kotlinVisibility(v: Visibility): KotlinVisibility = when (v) {
    Visibility.PUBLIC -> KotlinVisibility.PUBLIC
    Visibility.PROTECTED -> KotlinVisibility.PROTECTED
    Visibility.INTERNAL -> KotlinVisibility.INTERNAL
    Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS -> KotlinVisibility.PRIVATE
    Visibility.LOCAL -> KotlinVisibility.LOCAL
}
