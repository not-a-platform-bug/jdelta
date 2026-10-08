package jdelta.classfile

import org.objectweb.asm.Opcodes

/**
 * ABI layer별 fingerprint (ARCHITECTURE.md §3.2).
 * 각 layer는 해당 항목을 정렬된 canonical stream으로 직렬화해 hash한다.
 * fingerprint는 "바뀌었는가"의 빠른 판단과 분류 누락 감지(§5.3 10번)에 쓰고, 분류 자체는 [DeltaClassifier]가 한다.
 */
internal object AbiFingerprints {
    // compile에 의미 있는 access flag. ACC_SUPER, ACC_SYNTHETIC, ACC_NATIVE, ACC_STRICT 등은 제외한다.
    const val CLASS_COMPILE_MASK = Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED or
        Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or
        Opcodes.ACC_ANNOTATION or Opcodes.ACC_ENUM or Opcodes.ACC_RECORD
    const val METHOD_COMPILE_MASK = Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED or
        Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ABSTRACT or Opcodes.ACC_VARARGS
    const val FIELD_COMPILE_MASK = Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED or
        Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ENUM
    const val BINARY_MASK = Opcodes.ACC_PUBLIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED or
        Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE
    const val REFLECTION_MASK = METHOD_COMPILE_MASK or Opcodes.ACC_TRANSIENT or Opcodes.ACC_VOLATILE or
        Opcodes.ACC_SYNCHRONIZED or Opcodes.ACC_ENUM or Opcodes.ACC_SYNTHETIC

    fun compute(c: ClassSnapshot, names: NameNormalizer): ClassFingerprints {
        val methods = c.methods.filter { it.role == MethodRole.NORMAL }
        val (publicMethods, otherMethods) = methods.partition { it.access.isPublicOrProtected() }
        val packageMethods = otherMethods.filterNot { it.access.isPrivate() }
        val (publicFields, otherFields) = c.fields.partition { it.access.isPublicOrProtected() }
        val packageFields = otherFields.filterNot { it.access.isPrivate() }

        val header = classHeader(c)

        val compilePublic = Hasher().add("compile-public").addAll(header)
            .addAll(publicFields.map(::fieldCompile).sorted())
            .addAll(publicMethods.map(::methodCompile).sorted())
            .hex()
        val compilePackage = Hasher().add("compile-package")
            .addAll(packageFields.map(::fieldCompile).sorted())
            .addAll(packageMethods.map(::methodCompile).sorted())
            .hex()
        val binary = Hasher().add("binary")
            .add(c.access and BINARY_MASK).add(c.superName).addAll(c.interfaces)
            .addAll(c.fields.filterNot { it.access.isPrivate() }.map { "F|${it.access and BINARY_MASK}|${it.name}|${it.descriptor}" }.sorted())
            .addAll(c.methods.filterNot { it.access.isPrivate() || it.role == MethodRole.LAMBDA_BODY }
                .map { "M|${it.access and BINARY_MASK}|${it.name}|${it.descriptor}" }.sorted())
            .hex()
        val reflection = Hasher().add("reflection").addAll(reflectionCanon(c, methods)).hex()

        // content: 이름 번호와 무관한 전체 내용. local/anonymous class 그룹 비교와 분류 누락 감지에 쓴다.
        val content = Hasher().add("content")
            .addAll((header + fieldsAndMethodsFull(c, methods)).map { names.descriptor(it) })
            .addAll(reflectionCanon(c, methods, includeParameterNames = false).map { names.descriptor(it) })
            .addAll(methods.map { names.descriptor("${it.key}=${it.bodyHash}")!! }.sorted())
            .hex()

        return ClassFingerprints(compilePublic, compilePackage, binary, reflection, content)
    }

    fun classHeader(c: ClassSnapshot): List<String> = listOf(
        "access=${effectiveClassAccess(c) and CLASS_COMPILE_MASK}",
        "super=${c.superName}",
        "interfaces=${c.interfaces.joinToString(",")}",
        "signature=${c.signature}",
        "annotations=${c.annotations.joinToString(";") { it.canonical }}",
        "permitted=${c.permittedSubclasses.joinToString(",")}",
        "record=${c.recordComponents.joinToString(";") { recordCanon(it) }}",
        "kotlin=${c.kotlinViewHash}",
    )

    fun effectiveClassAccess(c: ClassSnapshot): Int = c.ownInnerClassEntry?.access ?: c.access

    fun fieldCompile(f: FieldSnapshot): String =
        "F|${f.access and FIELD_COMPILE_MASK}|${f.name}|${f.descriptor}|${f.signature}|${f.constantValue}|" +
            f.annotations.joinToString(";") { it.canonical }

    fun methodCompile(m: MethodSnapshot): String =
        "M|${m.access and METHOD_COMPILE_MASK}|${m.name}|${m.descriptor}|${m.signature}|${m.exceptions.joinToString(",")}|" +
            m.annotations.joinToString(";") { it.canonical } + "|" +
            m.parameterAnnotations.joinToString("/") { p -> p.joinToString(";") { it.canonical } } + "|" +
            m.annotationDefault

    private fun fieldsAndMethodsFull(c: ClassSnapshot, methods: List<MethodSnapshot>): List<String> =
        (c.fields.map(::fieldCompile) + methods.map(::methodCompile)).sorted()

    private fun reflectionCanon(c: ClassSnapshot, methods: List<MethodSnapshot>, includeParameterNames: Boolean = true): List<String> {
        val visible = { list: List<AnnotationInfo> -> list.filter { it.visible }.joinToString(";") { it.canonical } }
        val result = ArrayList<String>()
        result += "access=${effectiveClassAccess(c) and REFLECTION_MASK}"
        result += "annotations=${visible(c.annotations)}"
        result += "enumOrder=${c.fields.filter { (it.access and Opcodes.ACC_ENUM) != 0 }.joinToString(",") { it.name }}"
        result += "record=${c.recordComponents.joinToString(";") { recordCanon(it) }}"
        result += c.fields.map { "F|${it.access and REFLECTION_MASK}|${it.name}|${it.descriptor}|${it.signature}|${visible(it.annotations)}" }.sorted()
        result += methods.map {
            "M|${it.access and REFLECTION_MASK}|${it.name}|${it.descriptor}|${it.signature}|${visible(it.annotations)}|" +
                it.parameterAnnotations.joinToString("/") { p -> visible(p) } + "|" + (if (includeParameterNames) it.parameterNames else "")
        }.sorted()
        return result
    }

    private fun recordCanon(r: RecordComponentSnapshot) =
        "${r.name}:${r.descriptor}:${r.signature}:${r.annotations.joinToString(",") { it.canonical }}"
}

internal fun Int.isPrivate(): Boolean = (this and Opcodes.ACC_PRIVATE) != 0

internal fun Int.isPublicOrProtected(): Boolean = (this and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED)) != 0

internal fun Int.has(flag: Int): Boolean = (this and flag) != 0
