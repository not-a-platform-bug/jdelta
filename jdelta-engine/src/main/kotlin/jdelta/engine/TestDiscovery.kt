package jdelta.engine

import jdelta.classfile.ClassSnapshot
import jdelta.classfile.MethodSnapshot
import jdelta.classfile.ProjectSnapshot
import jdelta.core.ClassId
import jdelta.core.ProjectModel
import jdelta.core.TestId

/** 정적으로 찾은 test method와 그 class. */
public data class DiscoveredTest(public val id: TestId, public val classId: ClassId)

/**
 * test source set의 classfile에서 JUnit test method를 찾는다.
 *
 * trace가 없는 test를 빠뜨리지 않기 위한 test 목록이다(§6.6 "trace가 없는 test는 항상 포함").
 * 상속한 test method는 실행되는 subclass의 test로 센다(JUnit의 MethodSource와 같다).
 * meta-annotation으로 만든 사용자 정의 test annotation은 찾지 못한다. v0.1은 full suite를 항상 함께 실행하므로 순서에만 영향이 있다.
 */
public object TestDiscovery {
    private val JUPITER = setOf(
        "Lorg/junit/jupiter/api/Test;",
        "Lorg/junit/jupiter/api/RepeatedTest;",
        "Lorg/junit/jupiter/api/TestFactory;",
        "Lorg/junit/jupiter/api/TestTemplate;",
        "Lorg/junit/jupiter/params/ParameterizedTest;",
    )
    private const val JUNIT4 = "Lorg/junit/Test;"
    private const val ACC_INTERFACE = 0x0200
    private const val ACC_ABSTRACT = 0x0400

    public fun discover(snapshot: ProjectSnapshot, model: ProjectModel): List<DiscoveredTest> {
        val testSourceSets = model.modules.flatMap { it.sourceSets }.filter { it.isTest }.map { it.id }.toSet()
        val byName = snapshot.sourceSets.flatMap { it.classes.values }.groupBy { it.internalName }
        val result = ArrayList<DiscoveredTest>()
        for (sourceSet in snapshot.sourceSets.filter { it.id in testSourceSets }) {
            for (c in sourceSet.classes.values.sortedBy { it.internalName }) {
                if (c.isBodyDependent || (c.access and (ACC_INTERFACE or ACC_ABSTRACT)) != 0) continue
                val seen = HashSet<String>()
                for (owner in hierarchy(c, byName)) {
                    for (m in owner.methods) {
                        if (!seen.add(m.key)) continue // override된 상위 method는 세지 않는다
                        val engine = engine(m) ?: continue
                        result += DiscoveredTest(TestId(engine, c.id.binaryName, m.name, parameterTypes(m.descriptor)), c.id)
                    }
                }
            }
        }
        return result
    }

    private fun engine(m: MethodSnapshot): String? = when {
        m.annotations.any { it.descriptor in JUPITER } -> "junit-jupiter"
        m.annotations.any { it.descriptor == JUNIT4 } -> "junit-vintage"
        else -> null
    }

    private fun hierarchy(c: ClassSnapshot, byName: Map<String, List<ClassSnapshot>>): Sequence<ClassSnapshot> =
        generateSequence(c) { current -> current.superName?.let { byName[it]?.firstOrNull() } }.take(MAX_DEPTH)

    /** JUnit `MethodSource`의 parameter type 표기(`Class.getName()`, 쉼표+공백 구분). */
    internal fun parameterTypes(descriptor: String): String {
        val types = ArrayList<String>()
        var i = 1
        while (descriptor[i] != ')') {
            val start = i
            while (descriptor[i] == '[') i++
            if (descriptor[i] == 'L') i = descriptor.indexOf(';', i)
            val type = descriptor.substring(start, i + 1)
            i++
            types += when {
                type.startsWith("[") -> type.replace('/', '.')
                type.startsWith("L") -> type.substring(1, type.length - 1).replace('/', '.')
                else -> PRIMITIVES.getValue(type[0])
            }
        }
        return types.joinToString(", ")
    }

    private const val MAX_DEPTH = 16
    private val PRIMITIVES = mapOf(
        'Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short",
        'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double",
    )
}
