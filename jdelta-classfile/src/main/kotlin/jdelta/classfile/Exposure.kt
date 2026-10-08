package jdelta.classfile

import jdelta.core.ModuleId
import org.objectweb.asm.Opcodes

/** member가 어디까지 보이는가. compile/binary impact level을 정한다. */
internal enum class Scope(val rank: Int) {
    PRIVATE(0),
    PACKAGE(1),
    PUBLIC(2),
    ;

    infix fun wider(other: Scope): Scope = if (rank >= other.rank) this else other

    infix fun narrower(other: Scope): Scope = if (rank <= other.rank) this else other
}

/**
 * module 단위로 class가 downstream에 노출되는지 계산한다 (ARCHITECTURE.md §3.2).
 *
 * public이 아닌 class라도 같은 module의 노출된 subclass가 상속하면 그 public/protected member는 downstream에서 보인다.
 */
internal class Exposure(snapshot: ProjectSnapshot) {
    private val classesByModule: Map<ModuleId, Map<String, ClassSnapshot>> = snapshot.sourceSets
        .groupBy { it.id.module }
        .mapValues { (_, sets) -> sets.flatMap { it.classes.values }.associateBy { it.internalName } }
    private val subclassesByModule: Map<ModuleId, Map<String, List<ClassSnapshot>>> = classesByModule
        .mapValues { (_, classes) -> classes.values.filter { it.superName != null }.groupBy { it.superName!! } }
    private val exposed = HashMap<jdelta.core.ClassId, Boolean>()

    fun classScope(c: ClassSnapshot): Scope = when {
        isExposed(c) -> Scope.PUBLIC
        else -> Scope.PACKAGE
    }

    fun memberScope(c: ClassSnapshot, m: MethodSnapshot): Scope =
        memberScope(c, m.access) narrower kotlinScope(c.kotlin?.declarationOfMethod(m.key))

    fun memberScope(c: ClassSnapshot, f: FieldSnapshot): Scope =
        memberScope(c, f.access) narrower kotlinScope(c.kotlin?.declarationOfField(f.name) ?: companionProperty(c, f.name))

    /** companion object의 `const val`과 `@JvmField`는 field가 바깥 class에 생기지만 선언은 companion metadata에 있다. */
    private fun companionProperty(c: ClassSnapshot, field: String): KotlinDeclaration? {
        val companion = c.kotlin?.companionObject ?: return null
        return classesByModule[c.id.module]?.get("${c.internalName}$$companion")?.kotlin?.declarationOfField(field)
    }

    /** JVM access만으로 본 범위. Kotlin 선언과 연결되지 않는 member(bridge 등)에 쓴다. */
    fun memberScope(c: ClassSnapshot, access: Int): Scope = when {
        access.isPrivate() -> Scope.PRIVATE
        !access.isPublicOrProtected() -> Scope.PACKAGE
        isExposed(c) -> Scope.PUBLIC
        else -> Scope.PACKAGE
    }

    /** Kotlin 선언 자체의 범위. class가 노출되지 않으면 그보다 넓을 수 없다. */
    fun declarationScope(c: ClassSnapshot, d: KotlinDeclaration): Scope = when {
        d.visibility == KotlinVisibility.PRIVATE && d.jvmMethods.isEmpty() && d.jvmField == null -> Scope.PRIVATE
        else -> d.visibility.scope narrower classScope(c)
    }

    /** internal/private Kotlin 선언은 JVM에서 public이어도 module 밖 Kotlin code가 볼 수 없다 (§3.6 규칙 1). */
    private fun kotlinScope(d: KotlinDeclaration?): Scope = d?.visibility?.scope ?: Scope.PUBLIC

    /** public이 아닌데 public subclass를 통해 노출되는가 (report reason용). */
    fun isExposedOnlyViaSubclass(c: ClassSnapshot): Boolean = isExposed(c) && !isEffectivelyPublic(c)

    fun isExposed(c: ClassSnapshot): Boolean {
        exposed[c.id]?.let { return it }
        exposed[c.id] = false // 순환 방어
        val result = isEffectivelyPublic(c) ||
            subclassesByModule[c.id.module]?.get(c.internalName).orEmpty().any { isExposed(it) }
        exposed[c.id] = result
        return result
    }

    private fun isEffectivelyPublic(c: ClassSnapshot): Boolean {
        if (c.isBodyDependent) return false
        if (c.kotlin?.visibility?.let { it.scope != Scope.PUBLIC } == true) return false
        val access = AbiFingerprints.effectiveClassAccess(c)
        if (!access.isPublicOrProtected()) return false
        val outer = c.ownInnerClassEntry?.outerName ?: return true
        val outerClass = classesByModule[c.id.module]?.get(outer) ?: return true
        return isEffectivelyPublic(outerClass)
    }

    companion object {
        fun visibilityRank(access: Int): Int = when {
            access.has(Opcodes.ACC_PUBLIC) -> 3
            access.has(Opcodes.ACC_PROTECTED) -> 2
            access.has(Opcodes.ACC_PRIVATE) -> 0
            else -> 1
        }
    }
}
