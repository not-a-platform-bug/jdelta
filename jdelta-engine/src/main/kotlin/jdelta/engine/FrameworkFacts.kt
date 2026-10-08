package jdelta.engine

import jdelta.classfile.ClassSnapshot
import jdelta.classfile.ProjectSnapshot
import jdelta.core.ClassId
import jdelta.core.FieldId
import jdelta.core.ModuleId
import jdelta.core.NodeId
import jdelta.core.ProjectModel
import jdelta.impact.FrameworkProfiles
import java.nio.file.Files

/**
 * framework profile(ARCHITECTURE.md §6.4)이 쓰는 사실을 snapshot에서 모은다.
 *
 * - [annotations]: class/method/field -> annotation descriptor. project 안에 정의된 meta-annotation을 따라간다
 *   (`@MyService`에 `@Service`가 붙어 있으면 `@MyService`가 붙은 class도 `@Service`로 본다).
 * - [processorInputs]: processor-sensitive class(`@Mapper` 등)가 method signature로 참조하는 project class -> 그 processor class.
 *   DTO가 바뀌면 생성된 mapper 구현이 바뀔 수 있다.
 * - [ordinalEnums]: `@Enumerated`(기본 ORDINAL)로 저장되는 enum -> 그 field.
 * - [generatedClasses]: module의 source directory에 대응하는 source 파일이 없는 class(annotation processor 생성물로 본다).
 */
internal class FrameworkFacts(
    val annotations: Map<NodeId, Set<String>>,
    val processorInputs: Map<ClassId, Set<ClassId>>,
    val ordinalEnums: Map<String, Set<FieldId>>,
    val generatedClasses: Set<ClassId>,
    val processorModules: Set<ModuleId>,
) {
    fun of(id: NodeId): Set<String> = annotations[id].orEmpty()

    companion object {
        private const val ENUM_TYPE_STRING = "STRING"

        fun collect(base: ProjectSnapshot, head: ProjectSnapshot, model: ProjectModel?): FrameworkFacts {
            val classes = (base.sourceSets + head.sourceSets).flatMap { it.classes.values }
            val byName = classes.groupBy { it.internalName }
            val meta = MetaAnnotations(byName)

            val annotations = HashMap<NodeId, MutableSet<String>>()
            fun add(id: NodeId, descriptors: Collection<String>) {
                if (descriptors.isEmpty()) return
                annotations.getOrPut(id) { LinkedHashSet() } += descriptors.flatMap { meta.expand(it) }
            }
            for (c in classes) {
                add(c.id, c.annotations.map { it.descriptor })
                for (m in c.methods) add(c.id.method(m.name, m.descriptor), m.annotations.map { it.descriptor } + m.parameterAnnotations.flatten().map { it.descriptor })
                for (f in c.fields) add(c.id.field(f.name, f.descriptor), f.annotations.map { it.descriptor })
            }

            val sensitive = FrameworkProfiles.BUILT_IN.filter { it.processorSensitive }.flatMap { it.annotations }.toSet()
            val processorInputs = HashMap<ClassId, MutableSet<ClassId>>()
            for (c in classes) {
                val isSensitive = annotations[c.id].orEmpty().any { it in sensitive } ||
                    c.methods.any { m -> annotations[c.id.method(m.name, m.descriptor)].orEmpty().any { it in sensitive } }
                if (!isSensitive) continue
                processorInputs.getOrPut(c.id) { LinkedHashSet() } += c.id
                for (m in c.methods) {
                    for (type in referencedTypes(m.descriptor + (m.signature ?: ""))) {
                        byName[type].orEmpty().forEach { processorInputs.getOrPut(it.id) { LinkedHashSet() } += c.id }
                    }
                }
            }

            val ordinalEnums = HashMap<String, MutableSet<FieldId>>()
            for (c in classes) {
                for (f in c.fields) {
                    val enumerated = f.annotations.firstOrNull { it.descriptor in FrameworkProfiles.ENUMERATED } ?: continue
                    if (enumerated.values.contains(ENUM_TYPE_STRING)) continue
                    if (!f.descriptor.startsWith("L")) continue
                    ordinalEnums.getOrPut(f.descriptor.substring(1, f.descriptor.length - 1)) { LinkedHashSet() } += c.id.field(f.name, f.descriptor)
                }
            }

            val processorModules = model?.modules?.filter { it.annotationProcessors.isNotEmpty() }?.map { it.id }?.toSet().orEmpty()
            val generated = if (model == null) emptySet() else generatedClasses(head, model, processorModules)
            return FrameworkFacts(annotations, processorInputs, ordinalEnums, generated, processorModules)
        }

        /** `Lcom/acme/Foo;` 형태로 나타나는 모든 type의 internal name. */
        private fun referencedTypes(descriptorOrSignature: String): Set<String> =
            Regex("L([\\w/$]+)[;<]").findAll(descriptorOrSignature).map { it.groupValues[1] }.toSet()

        private fun generatedClasses(head: ProjectSnapshot, model: ProjectModel, processorModules: Set<ModuleId>): Set<ClassId> {
            val result = HashSet<ClassId>()
            for (module in model.modules.filter { it.id in processorModules }) {
                for (sourceSet in module.sourceSets) {
                    val snapshot = head.sourceSet(sourceSet.id) ?: continue
                    for (c in snapshot.classes.values) {
                        if (c.isBodyDependent || c.sourceFile == null) continue
                        val path = c.packageName.let { if (it.isEmpty()) "" else "$it/" } + c.sourceFile
                        if (sourceSet.sourceDirs.none { Files.exists(it.resolve(path)) }) result += c.id
                    }
                }
            }
            return result
        }
    }

    /** project 안에 정의된 annotation type이 다른 annotation으로 꾸며져 있으면 그것까지 펼친다. */
    private class MetaAnnotations(private val byName: Map<String, List<ClassSnapshot>>) {
        private val cache = HashMap<String, Set<String>>()

        fun expand(descriptor: String, seen: MutableSet<String> = HashSet()): Set<String> {
            cache[descriptor]?.let { return it }
            if (!seen.add(descriptor)) return setOf(descriptor)
            val type = byName[descriptor.removePrefix("L").removeSuffix(";")]?.firstOrNull()
            val result = LinkedHashSet<String>().apply { add(descriptor) }
            if (type != null && (type.access and ACC_ANNOTATION) != 0) {
                type.annotations.map { it.descriptor }.filterNot { it.startsWith("Ljava/lang/annotation/") }.forEach { result += expand(it, seen) }
            }
            cache[descriptor] = result
            return result
        }

        private companion object {
            const val ACC_ANNOTATION = 0x2000
        }
    }
}
