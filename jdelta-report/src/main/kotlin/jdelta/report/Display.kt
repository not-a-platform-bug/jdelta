package jdelta.report

import jdelta.core.ClassId
import jdelta.core.FieldId
import jdelta.core.MethodId
import jdelta.core.ModuleId
import jdelta.core.FileId
import jdelta.core.NodeId
import jdelta.core.ResourceId
import jdelta.core.SourceSetId
import jdelta.core.TaskId
import jdelta.core.TestId

/** 사람이 읽는 이름. `com.acme.PriceCalculator#calculate(Order)` */
internal object Display {
    fun name(id: NodeId): String = when (id) {
        is ClassId -> javaName(id.internalName)
        is MethodId -> "${javaName(id.owner.internalName)}#${methodName(id)}(${parameters(id.descriptor).joinToString(", ")})"
        is FieldId -> "${javaName(id.owner.internalName)}#${id.name}"
        is ResourceId -> "${id.path} (${id.sourceSet.canonical})"
        is SourceSetId -> id.canonical
        is ModuleId -> id.path
        is TestId -> if (id.method == null) id.className else "${id.className}#${id.method}(${id.parameterTypes})"
        is TaskId -> id.canonical
        is FileId -> id.path
    }

    fun module(id: NodeId): ModuleId? = when (id) {
        is ClassId -> id.module
        is MethodId -> id.owner.module
        is FieldId -> id.owner.module
        is ResourceId -> id.sourceSet.module
        is SourceSetId -> id.module
        is ModuleId -> id
        is TaskId -> id.module
        is FileId -> null
        is TestId -> null
    }

    private fun methodName(id: MethodId) = when (id.name) {
        "<init>" -> simpleName(id.owner.internalName)
        "<clinit>" -> "<static init>"
        else -> id.name
    }

    private fun javaName(internalName: String) = internalName.replace('/', '.')

    private fun simpleName(internalName: String) = internalName.substringAfterLast('/').substringAfterLast('$')

    /** method descriptor의 parameter를 단순 type 이름으로 */
    fun parameters(descriptor: String): List<String> {
        val result = ArrayList<String>()
        var i = 1
        while (i < descriptor.length && descriptor[i] != ')') {
            var dims = 0
            while (descriptor[i] == '[') { dims++; i++ }
            val base = when (val c = descriptor[i]) {
                'L' -> {
                    val end = descriptor.indexOf(';', i)
                    simpleName(descriptor.substring(i + 1, end)).also { i = end }
                }
                else -> PRIMITIVES[c] ?: c.toString()
            }
            result += base + "[]".repeat(dims)
            i++
        }
        return result
    }

    private val PRIMITIVES = mapOf(
        'Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short",
        'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double", 'V' to "void",
    )
}
