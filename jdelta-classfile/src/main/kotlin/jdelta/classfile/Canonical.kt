package jdelta.classfile

import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode

/** ASM 값을 정렬/비교 가능한 canonical 문자열로 바꾼다. */
internal object Canonical {
    fun constant(value: Any?, names: NameNormalizer = NameNormalizer.IDENTITY): String = when (value) {
        null -> "null"
        is Int -> "I:$value"
        is Long -> "J:$value"
        is Float -> "F:${java.lang.Float.floatToRawIntBits(value)}"
        is Double -> "D:${java.lang.Double.doubleToRawLongBits(value)}"
        is String -> "S:$value"
        is Type -> "T:${names.descriptor(value.descriptor)}"
        is Handle -> handle(value, names)
        is ConstantDynamic -> buildString {
            append("CD:").append(value.name).append(':').append(names.descriptor(value.descriptor))
            append(':').append(handle(value.bootstrapMethod, names))
            for (i in 0 until value.bootstrapMethodArgumentCount) {
                append(',').append(constant(value.getBootstrapMethodArgument(i), names))
            }
        }
        else -> "?:${value.javaClass.name}:$value"
    }

    fun handle(handle: Handle, names: NameNormalizer = NameNormalizer.IDENTITY): String =
        "H:${handle.tag}:${names.internalName(handle.owner)}.${handle.name}${names.descriptor(handle.desc)}:${handle.isInterface}"

    fun annotationValue(value: Any?): String = when (value) {
        is AnnotationNode -> "@${value.desc}(${annotationValues(value.values)})"
        is Array<*> -> // enum: [descriptor, constantName]
            if (value.size == 2 && value[0] is String) "E:${value[0]}.${value[1]}" else value.joinToString(",", "[", "]") { annotationValue(it) }
        is List<*> -> value.joinToString(",", "[", "]") { annotationValue(it) }
        is ByteArray -> value.joinToString(",", "B[", "]")
        is BooleanArray -> value.joinToString(",", "Z[", "]")
        is CharArray -> value.joinToString(",", "C[", "]") { it.code.toString() }
        is ShortArray -> value.joinToString(",", "S[", "]")
        is IntArray -> value.joinToString(",", "I[", "]")
        is LongArray -> value.joinToString(",", "J[", "]")
        is FloatArray -> value.joinToString(",", "F[", "]") { java.lang.Float.floatToRawIntBits(it).toString() }
        is DoubleArray -> value.joinToString(",", "D[", "]") { java.lang.Double.doubleToRawLongBits(it).toString() }
        is Boolean -> "Z:$value"
        is Char -> "C:${value.code}"
        is Byte -> "B:$value"
        is Short -> "S:$value"
        else -> constant(value)
    }

    /** ASM의 `values`는 [name1, value1, name2, value2, ...] 형식이다. element 이름순으로 정렬한다. */
    fun annotationValues(values: List<Any?>?): String {
        if (values.isNullOrEmpty()) return ""
        return values.chunked(2)
            .map { (name, value) -> "$name=${annotationValue(value)}" }
            .sorted()
            .joinToString(",")
    }
}

/**
 * fingerprint에서 불안정한 이름을 지운다.
 * - 자기 자신 -> `<self>` : anonymous class 번호가 바뀌어도 내용 hash가 같게
 * - 이 class가 선언한 local/anonymous class -> `<local>` : 다른 method에 anonymous class가 추가되어 번호가 밀려도 같게
 */
internal class NameNormalizer(private val self: String?, private val locals: Set<String>) {
    fun internalName(name: String?): String? = when {
        name == null -> null
        name == self -> "<self>"
        name in locals -> "<local>"
        name.startsWith("[") -> descriptor(name)
        else -> name
    }

    fun descriptor(descriptor: String?): String? {
        if (descriptor == null || (self == null && locals.isEmpty())) return descriptor
        if ('L' !in descriptor) return descriptor
        return OBJECT_TYPE.replace(descriptor) { match ->
            val name = match.groupValues[1]
            when (name) {
                self -> "L<self>;"
                in locals -> "L<local>;"
                else -> match.value
            }
        }
    }

    companion object {
        val IDENTITY = NameNormalizer(null, emptySet())
        private val OBJECT_TYPE = Regex("L([^;<>]+);")
    }
}
