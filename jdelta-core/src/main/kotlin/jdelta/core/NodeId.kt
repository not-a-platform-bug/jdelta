package jdelta.core

/**
 * revision 사이에서 같은 의미 단위를 가리키는 안정적인 ID.
 *
 * [canonical] 문자열은 JSON, trace, report에 그대로 나타나므로 사람이 읽을 수 있고
 * [NodeId.parse]로 되돌릴 수 있어야 한다. 형식은 ARCHITECTURE.md §2.1을 따른다.
 */
public sealed interface NodeId {
    public val canonical: String

    public companion object {
        public fun parse(canonical: String): NodeId = NodeIdParser.parse(canonical)
    }
}

/** Gradle project path. 예: `:app`, root는 `:`. */
public data class ModuleId(public val path: String) : NodeId {
    init {
        require(path.startsWith(":")) { "module path must start with ':': $path" }
        require(path.none { it in RESERVED }) { "module path must not contain any of '$RESERVED': $path" }
    }

    override val canonical: String get() = path

    override fun toString(): String = canonical

    private companion object {
        const val RESERVED = "|@!#"
    }
}

public data class SourceSetId(public val module: ModuleId, public val name: String) : NodeId {
    init {
        require(name.isNotEmpty() && name.none { it in "/@|" }) { "invalid source set name: $name" }
    }

    override val canonical: String get() = "${module.path}@$name"

    override fun toString(): String = canonical
}

/** JVM class. [internalName]은 `com/acme/Foo$Bar` 형식. */
public data class ClassId(public val module: ModuleId, public val internalName: String) : NodeId {
    override val canonical: String get() = "${module.path}|$internalName"

    /** `com.acme.Foo$Bar` */
    public val binaryName: String get() = internalName.replace('/', '.')

    public fun method(name: String, descriptor: String): MethodId = MethodId(this, name, descriptor)

    public fun field(name: String, descriptor: String): FieldId = FieldId(this, name, descriptor)

    override fun toString(): String = canonical
}

/** method, constructor(`<init>`), static initializer(`<clinit>`). */
public data class MethodId(
    public val owner: ClassId,
    public val name: String,
    public val descriptor: String,
) : NodeId {
    override val canonical: String get() = "${owner.canonical}#$name$descriptor"

    public val isConstructor: Boolean get() = name == "<init>"

    override fun toString(): String = canonical
}

public data class FieldId(
    public val owner: ClassId,
    public val name: String,
    public val descriptor: String,
) : NodeId {
    override val canonical: String get() = "${owner.canonical}.$name:$descriptor"

    override fun toString(): String = canonical
}

/** source set output 안의 resource. [path]는 `/`로 구분된 상대 경로. */
public data class ResourceId(public val sourceSet: SourceSetId, public val path: String) : NodeId {
    override val canonical: String get() = "${sourceSet.canonical}/$path"

    override fun toString(): String = canonical
}

/**
 * test class([method] == null) 또는 test method.
 * [parameterTypes]는 JUnit MethodSource의 parameter type 목록(쉼표 구분) 그대로다.
 */
public data class TestId(
    public val engine: String,
    public val className: String,
    public val method: String? = null,
    public val parameterTypes: String = "",
) : NodeId {
    init {
        require(engine.isNotEmpty() && ':' !in engine && !engine.startsWith(":")) { "invalid engine id: $engine" }
        require(method != null || parameterTypes.isEmpty()) { "parameterTypes requires a method" }
    }

    override val canonical: String
        get() = if (method == null) "$engine:$className" else "$engine:$className#$method($parameterTypes)"

    override fun toString(): String = canonical
}

public data class TaskId(public val module: ModuleId, public val name: String) : NodeId {
    override val canonical: String get() = "${module.path}!$name"

    override fun toString(): String = canonical
}

/** repository root 기준 상대 경로의 파일(source, build script). 예: `//app/src/main/java/com/acme/Foo.java`. */
public data class FileId(public val path: String) : NodeId {
    init {
        require(path.isNotEmpty() && !path.startsWith("/") && '\\' !in path) { "file path must be relative and '/'-separated: $path" }
    }

    override val canonical: String get() = "//$path"

    override fun toString(): String = canonical
}

internal object NodeIdParser {
    fun parse(s: String): NodeId {
        require(s.isNotEmpty()) { "empty node id" }
        if (s.startsWith("//")) return FileId(s.substring(2))
        if (!s.startsWith(":")) return parseTest(s)

        val bar = s.indexOf('|')
        if (bar >= 0) return parseMember(ModuleId(s.substring(0, bar)), s.substring(bar + 1))

        val bang = s.indexOf('!')
        if (bang >= 0) return TaskId(ModuleId(s.substring(0, bang)), s.substring(bang + 1))

        val at = s.indexOf('@')
        if (at >= 0) {
            val sourceSetAndPath = s.substring(at + 1)
            val slash = sourceSetAndPath.indexOf('/')
            val sourceSet = SourceSetId(
                ModuleId(s.substring(0, at)),
                if (slash >= 0) sourceSetAndPath.substring(0, slash) else sourceSetAndPath,
            )
            return if (slash >= 0) ResourceId(sourceSet, sourceSetAndPath.substring(slash + 1)) else sourceSet
        }
        return ModuleId(s)
    }

    private fun parseMember(module: ModuleId, rest: String): NodeId {
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            val owner = ClassId(module, rest.substring(0, hash))
            val nameAndDesc = rest.substring(hash + 1)
            val paren = nameAndDesc.indexOf('(')
            require(paren > 0) { "method id without descriptor: $rest" }
            return MethodId(owner, nameAndDesc.substring(0, paren), nameAndDesc.substring(paren))
        }
        // internal name에는 '.'이 없으므로 첫 '.'이 field 구분자다
        val dot = rest.indexOf('.')
        if (dot >= 0) {
            val owner = ClassId(module, rest.substring(0, dot))
            val nameAndDesc = rest.substring(dot + 1)
            val colon = nameAndDesc.lastIndexOf(':')
            require(colon > 0) { "field id without descriptor: $rest" }
            return FieldId(owner, nameAndDesc.substring(0, colon), nameAndDesc.substring(colon + 1))
        }
        return ClassId(module, rest)
    }

    private fun parseTest(s: String): TestId {
        val colon = s.indexOf(':')
        require(colon > 0) { "unrecognized node id: $s" }
        val engine = s.substring(0, colon)
        val rest = s.substring(colon + 1)
        val hash = rest.indexOf('#')
        if (hash < 0) return TestId(engine, rest)
        val methodPart = rest.substring(hash + 1)
        val paren = methodPart.indexOf('(')
        require(paren > 0 && methodPart.endsWith(")")) { "invalid test method id: $s" }
        return TestId(
            engine = engine,
            className = rest.substring(0, hash),
            method = methodPart.substring(0, paren),
            parameterTypes = methodPart.substring(paren + 1, methodPart.length - 1),
        )
    }
}
