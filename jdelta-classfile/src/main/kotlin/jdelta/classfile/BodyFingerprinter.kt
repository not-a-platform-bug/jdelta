package jdelta.classfile

import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * method body를 debug 정보와 무관한 canonical stream으로 hash한다 (ARCHITECTURE.md §3.3, §3.4).
 *
 * - line number, local variable table, frame, label identity, maxStack/maxLocals는 버린다.
 * - jump target은 실제 instruction index로 기록한다.
 * - lambda impl method는 이름 대신 그 body hash로 치환해서 소유 method에 접는다.
 *   그래서 lambda 하나를 추가해 뒤쪽 `lambda$foo$N` 번호가 밀려도 다른 method의 hash는 그대로다.
 */
internal class BodyFingerprinter(
    private val classNode: ClassNode,
    private val names: NameNormalizer,
) {
    private val methodsByKey: Map<String, MethodNode> = classNode.methods.associateBy { it.name + it.desc }

    /** lambda impl method key -> 그것을 처음 참조한 method key */
    val lambdaOwners: Map<String, String> = findLambdaOwners()

    private val cache = HashMap<String, String?>()
    private val inProgress = HashSet<String>()

    fun hash(method: MethodNode): String? {
        val key = method.name + method.desc
        if (key in cache) return cache[key]
        if (method.instructions.size() == 0) return null.also { cache[key] = null }
        if (!inProgress.add(key)) return "recursive-lambda" // 재귀 lambda 방어
        try {
            return compute(method).also { cache[key] = it }
        } finally {
            inProgress.remove(key)
        }
    }

    /** lambda를 소유 method까지 따라 올라간다 (lambda 안의 lambda). */
    fun rootOwner(key: String): String {
        var current = key
        val seen = HashSet<String>()
        while (true) {
            val owner = lambdaOwners[current] ?: return current
            if (!seen.add(owner)) return owner
            current = owner
        }
    }

    private fun compute(method: MethodNode): String {
        val h = Hasher()
        val insns = method.instructions.toArray()

        // label -> 그 뒤 첫 실제 instruction의 index
        val labelIndex = HashMap<LabelNode, Int>()
        var real = 0
        for (insn in insns) {
            when (insn) {
                is LabelNode -> labelIndex[insn] = real
                is LineNumberNode, is FrameNode -> Unit
                else -> real++
            }
        }
        fun idx(label: LabelNode) = "L${labelIndex.getValue(label)}"

        for (insn in insns) {
            if (insn is LabelNode || insn is LineNumberNode || insn is FrameNode) continue
            h.add(insn.opcode)
            when (insn) {
                is IntInsnNode -> h.add(insn.operand)
                is VarInsnNode -> h.add(insn.`var`)
                is TypeInsnNode -> h.add(names.internalName(insn.desc))
                is FieldInsnNode -> h.add(names.internalName(insn.owner)).add(insn.name).add(names.descriptor(insn.desc))
                is MethodInsnNode -> h.add(names.internalName(insn.owner)).add(insn.name)
                    .add(names.descriptor(insn.desc)).add(insn.itf.toString())
                is InvokeDynamicInsnNode -> addInvokeDynamic(h, insn)
                is JumpInsnNode -> h.add(idx(insn.label))
                is LdcInsnNode -> h.add(Canonical.constant(insn.cst, names))
                is IincInsnNode -> h.add(insn.`var`).add(insn.incr)
                is TableSwitchInsnNode -> {
                    h.add(insn.min).add(insn.max).add(idx(insn.dflt))
                    insn.labels.forEach { h.add(idx(it)) }
                }
                is LookupSwitchInsnNode -> {
                    h.add(idx(insn.dflt))
                    insn.keys.forEach { h.add(it) }
                    insn.labels.forEach { h.add(idx(it)) }
                }
                is MultiANewArrayInsnNode -> h.add(names.descriptor(insn.desc)).add(insn.dims)
                else -> Unit // InsnNode: opcode만으로 충분
            }
        }
        for (tcb in method.tryCatchBlocks) {
            h.add("try").add(idx(tcb.start)).add(idx(tcb.end)).add(idx(tcb.handler)).add(names.internalName(tcb.type))
        }
        return h.hex()
    }

    private fun addInvokeDynamic(h: Hasher, insn: InvokeDynamicInsnNode) {
        h.add(insn.name).add(names.descriptor(insn.desc)).add(Canonical.handle(insn.bsm, names))
        insn.bsmArgs.forEachIndexed { i, arg ->
            val folded = if (isLambdaFactory(insn.bsm) && i == 1 && arg is Handle) foldedLambda(arg) else null
            h.add(folded?.let { "lambda{$it}" } ?: Canonical.constant(arg, names))
        }
    }

    private fun foldedLambda(handle: Handle): String? {
        val key = handle.name + handle.desc
        if (handle.owner != classNode.name || key !in lambdaOwners) return null
        val target = methodsByKey[key] ?: return null
        return hash(target) ?: "no-body"
    }

    private fun findLambdaOwners(): Map<String, String> {
        val owners = LinkedHashMap<String, String>()
        for (method in classNode.methods) {
            for (insn in method.instructions) {
                if (insn !is InvokeDynamicInsnNode || !isLambdaFactory(insn.bsm)) continue
                val impl = insn.bsmArgs.getOrNull(1) as? Handle ?: continue
                if (impl.owner != classNode.name) continue
                val target = methodsByKey[impl.name + impl.desc] ?: continue
                if (isLambdaBody(target)) owners.putIfAbsent(impl.name + impl.desc, method.name + method.desc)
            }
        }
        return owners
    }

    companion object {
        private const val LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory"

        fun isLambdaFactory(bsm: Handle): Boolean = bsm.owner == LAMBDA_METAFACTORY

        /** javac `lambda$foo$0`, kotlinc `foo$lambda$0`. method reference(`Foo::bar`)의 대상은 접지 않는다. */
        fun isLambdaBody(method: MethodNode): Boolean =
            (method.access and Opcodes.ACC_PRIVATE) != 0 &&
                (method.name.startsWith("lambda$") || method.name.contains("\$lambda"))
    }
}
