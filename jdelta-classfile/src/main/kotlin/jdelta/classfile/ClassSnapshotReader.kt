package jdelta.classfile

import jdelta.core.ClassId
import jdelta.core.ModuleId
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeAnnotationNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.Type
import org.objectweb.asm.Handle

/** classfile bytes -> [ClassSnapshot]. */
public class ClassSnapshotReader {
    public fun read(module: ModuleId, bytes: ByteArray): ClassSnapshot {
        val node = ClassNode()
        // debug 정보는 읽는다(Kotlin inline 탐지에 필요, §3.1). fingerprint에서만 제외한다.
        ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
        return toSnapshot(ClassId(module, node.name), node)
    }

    private fun toSnapshot(id: ClassId, node: ClassNode): ClassSnapshot {
        val ownEntry = node.innerClasses.firstOrNull { it.name == node.name }
            ?.let { InnerClassEntry(it.name, it.outerName, it.innerName, it.access) }
        val localClasses = node.innerClasses
            .filter { it.outerName == null && it.name != node.name && it.name.startsWith(node.name + "$") }
            .map { it.name }
            .toSet()
        val names = NameNormalizer(node.name, localClasses)
        val bodies = BodyFingerprinter(node, names)
        val inlineSites = InlineCallSites(node)

        val kotlinMetadata = node.visibleAnnotations.orEmpty().firstOrNull { it.desc == KOTLIN_METADATA }
        val annotations = annotations(node.visibleAnnotations, node.invisibleAnnotations, node.visibleTypeAnnotations, node.invisibleTypeAnnotations)
            .filterNot { it.descriptor in COMPILER_ANNOTATIONS }

        val methods = node.methods.map { method ->
            val key = method.name + method.desc
            val role = roleOf(method, key, bodies)
            MethodSnapshot(
                name = method.name,
                descriptor = method.desc,
                access = method.access,
                signature = method.signature,
                exceptions = method.exceptions.orEmpty().sorted(),
                annotations = annotations(method.visibleAnnotations, method.invisibleAnnotations, method.visibleTypeAnnotations, method.invisibleTypeAnnotations),
                parameterAnnotations = parameterAnnotations(method),
                parameterNames = method.parameters?.map { it.name ?: "" },
                annotationDefault = method.annotationDefault?.let { Canonical.annotationValue(it) },
                bodyHash = bodies.hash(method),
                role = role,
                foldedInto = if (role == MethodRole.LAMBDA_BODY) bodies.rootOwner(key) else null,
                references = references(method, inlineSites),
            )
        }

        val fields = node.fields.map { field ->
            FieldSnapshot(
                name = field.name,
                descriptor = field.desc,
                access = field.access,
                signature = field.signature,
                constantValue = field.value?.let { Canonical.constant(it) },
                annotations = annotations(field.visibleAnnotations, field.invisibleAnnotations, field.visibleTypeAnnotations, field.invisibleTypeAnnotations),
            )
        }

        val recordComponents = node.recordComponents.orEmpty().map {
            RecordComponentSnapshot(
                name = it.name,
                descriptor = it.descriptor,
                signature = it.signature,
                annotations = annotations(it.visibleAnnotations, it.invisibleAnnotations, it.visibleTypeAnnotations, it.invisibleTypeAnnotations),
            )
        }

        val partial = ClassSnapshot(
            id = id,
            access = node.access,
            superName = node.superName,
            interfaces = node.interfaces.orEmpty(),
            signature = node.signature,
            classfileVersion = node.version and 0xFFFF,
            sourceFile = node.sourceFile,
            annotations = annotations,
            fields = fields,
            methods = methods,
            recordComponents = recordComponents,
            permittedSubclasses = node.permittedSubclasses.orEmpty().sorted(),
            ownInnerClassEntry = ownEntry,
            localClassNames = localClasses,
            enclosingMethod = node.outerClass?.let { EnclosingMethodRef(it, node.outerMethod, node.outerMethodDesc) },
            kotlinMetadataHash = kotlinMetadata?.let { Hasher.of(Canonical.annotationValues(it.values)) },
            kotlin = kotlinMetadata?.let { KotlinMetadataReader.read(it) },
            fingerprints = EMPTY_FINGERPRINTS,
        )
        return partial.copy(fingerprints = AbiFingerprints.compute(partial, names))
    }

    private fun roleOf(method: MethodNode, key: String, bodies: BodyFingerprinter): MethodRole {
        val synthetic = (method.access and Opcodes.ACC_SYNTHETIC) != 0
        return when {
            key in bodies.lambdaOwners -> MethodRole.LAMBDA_BODY
            (method.access and Opcodes.ACC_BRIDGE) != 0 -> MethodRole.BRIDGE
            synthetic && method.name.startsWith("access$") -> MethodRole.SYNTHETIC_ACCESSOR
            synthetic && (method.name == "\$values" || method.name == "\$deserializeLambda\$") -> MethodRole.COMPILER_SUPPORT
            else -> MethodRole.NORMAL
        }
    }

    private fun annotations(
        visible: List<AnnotationNode>?,
        invisible: List<AnnotationNode>?,
        visibleType: List<TypeAnnotationNode>?,
        invisibleType: List<TypeAnnotationNode>?,
    ): List<AnnotationInfo> {
        val result = ArrayList<AnnotationInfo>()
        visible?.forEach { result += it.toInfo(true) }
        invisible?.forEach { result += it.toInfo(false) }
        visibleType?.forEach { result += it.toInfo(true, "${it.typeRef}:${it.typePath}") }
        invisibleType?.forEach { result += it.toInfo(false, "${it.typeRef}:${it.typePath}") }
        return result.sortedBy { it.canonical }
    }

    private fun parameterAnnotations(method: MethodNode): List<List<AnnotationInfo>> {
        val count = Type.getArgumentCount(method.desc)
        if (method.visibleParameterAnnotations == null && method.invisibleParameterAnnotations == null) return emptyList()
        return (0 until count).map { i ->
            annotations(method.visibleParameterAnnotations?.getOrNull(i), method.invisibleParameterAnnotations?.getOrNull(i), null, null)
        }
    }

    private fun AnnotationNode.toInfo(visible: Boolean, typeTarget: String? = null) =
        AnnotationInfo(desc, visible, Canonical.annotationValues(values), typeTarget)

    private fun references(method: MethodNode, inlineSites: InlineCallSites): MethodReferences {
        if (method.instructions.size() == 0) return EMPTY_REFERENCES
        val calls = HashSet<String>()
        val reads = HashSet<String>()
        val writes = HashSet<String>()
        val types = HashSet<String>()
        for (insn in method.instructions) {
            when (insn) {
                is MethodInsnNode -> calls += "${insn.owner}.${insn.name}${insn.desc}"
                is FieldInsnNode -> {
                    val ref = "${insn.owner}.${insn.name}:${insn.desc}"
                    if (insn.opcode == Opcodes.GETFIELD || insn.opcode == Opcodes.GETSTATIC) reads += ref else writes += ref
                }
                is TypeInsnNode -> types += insn.desc
                is MultiANewArrayInsnNode -> types += insn.desc
                is LdcInsnNode -> (insn.cst as? Type)?.let { types += it.internalName }
                is InvokeDynamicInsnNode -> insn.bsmArgs.filterIsInstance<Handle>()
                    .forEach { calls += "${it.owner}.${it.name}${it.desc}" }
            }
        }
        return MethodReferences(calls, reads, writes, types, inlineSites.of(method))
    }

    private companion object {
        const val KOTLIN_METADATA = "Lkotlin/Metadata;"

        /**
         * 따로 해석하는 compiler annotation. `SourceDebugExtension`은 SMAP(줄 번호 표)을 그대로 담으므로
         * 줄만 밀려도 바뀐다. debug 정보이므로 fingerprint에서 뺀다 (D2).
         */
        val COMPILER_ANNOTATIONS = setOf(KOTLIN_METADATA, "Lkotlin/jvm/internal/SourceDebugExtension;")
        val EMPTY_REFERENCES = MethodReferences()
        val EMPTY_FINGERPRINTS = ClassFingerprints("", "", "", "", "")
    }
}
