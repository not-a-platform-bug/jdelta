package jdelta.classfile

import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode

/**
 * Kotlin inline function이 어느 method에 inline되었는지 찾는다 (ARCHITECTURE.md §3.6 규칙 3).
 *
 * inline된 code는 caller에 invoke 명령을 남기지 않는다. kotlinc가 남기는 두 흔적을 쓴다.
 * - LocalVariableTable의 `$i$f$<functionName>` marker: 무엇이 inline되었는가(이름만)
 * - SMAP(`SourceDebugExtension`)의 line mapping: inline된 줄이 어느 class에서 왔는가
 *
 * 결과는 `owner.name` 문자열이다. owner를 찾지 못하면 `?.name`이다.
 * descriptor는 알 수 없으므로 impact solver가 같은 이름의 inline method 전체에 연결한다(`INFERRED`).
 */
internal class InlineCallSites(private val node: ClassNode) {
    private val mappings: List<LineMapping> = parseSmap(node.sourceDebug)

    fun of(method: MethodNode): Set<String> {
        val functions = method.localVariables.orEmpty()
            .map { it.name }
            .filter { it.startsWith(INLINE_FUNCTION_MARKER) }
            .map { it.removePrefix(INLINE_FUNCTION_MARKER) }
            .toSet()
        if (functions.isEmpty()) return emptySet()
        val lines = method.instructions.filterIsInstance<LineNumberNode>().map { it.line }.toSet()
        val owners = mappings
            .filter { m -> !m.isIdentity(node.name) && lines.any { it in m.output } }
            .map { it.owner }
            .toSet()
        // inline function 자신의 body에도 자기 이름의 marker가 있다. 다른 곳에서 온 줄이 없으면 그것이다
        val inlined = if (owners.isEmpty()) functions - method.name else functions
        if (inlined.isEmpty()) return emptySet()
        return owners.ifEmpty { setOf(UNKNOWN_OWNER) }.flatMap { owner -> inlined.map { "$owner.$it" } }.toSet()
    }

    private data class LineMapping(val input: Int, val output: IntRange, val owner: String) {
        /** 자기 file의 줄을 그대로 옮긴 mapping. inline과 무관하다. */
        fun isIdentity(self: String) = owner == self && input == output.first
    }

    private companion object {
        const val INLINE_FUNCTION_MARKER = "\$i\$f\$"
        const val UNKNOWN_OWNER = "?"
        val LINE_INFO = Regex("""^(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?$""")

        /** JSR-045 SMAP에서 `Kotlin` stratum의 file 표와 line mapping만 읽는다. */
        fun parseSmap(smap: String?): List<LineMapping> {
            if (smap == null || !smap.startsWith("SMAP")) return emptyList()
            val lines = smap.lines()
            val start = lines.indexOf("*S Kotlin").takeIf { it >= 0 } ?: return emptyList()
            val files = HashMap<Int, String>()
            val result = ArrayList<LineMapping>()
            var section = ""
            var fileId = 1
            var i = start + 1
            while (i < lines.size) {
                val line = lines[i]
                when {
                    line == "*E" || line.startsWith("*S ") -> break
                    line.startsWith("*") -> section = line
                    section == "*F" -> {
                        // "+ id name" 다음 줄이 path(class internal name), "id name"이면 path 없음
                        val withPath = line.startsWith("+ ")
                        val parts = line.removePrefix("+ ").split(' ', limit = 2)
                        val id = parts[0].toIntOrNull()
                        if (id != null) {
                            files[id] = if (withPath && i + 1 < lines.size) lines[++i] else parts.getOrElse(1) { "" }
                        }
                    }
                    section == "*L" -> {
                        val m = LINE_INFO.matchEntire(line.trim())
                        if (m != null) {
                            val (inStart, id, repeat, outStart, increment) = m.destructured
                            if (id.isNotEmpty()) fileId = id.toInt()
                            val count = (repeat.toIntOrNull() ?: 1) * (increment.toIntOrNull() ?: 1)
                            val out = outStart.toInt()
                            files[fileId]?.let { result += LineMapping(inStart.toInt(), out until out + maxOf(count, 1), it) }
                        }
                    }
                }
                i++
            }
            return result
        }
    }
}
