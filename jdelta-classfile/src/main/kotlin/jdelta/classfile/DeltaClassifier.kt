package jdelta.classfile

import jdelta.core.ClassId
import jdelta.core.Confidence
import jdelta.core.DeltaKind
import jdelta.core.ImpactLevel
import jdelta.core.ImpactLevel.DOWNSTREAM
import jdelta.core.ImpactLevel.LOCAL
import jdelta.core.ImpactLevel.NONE
import jdelta.core.ImpactLevel.RUNTIME
import jdelta.core.NodeId
import jdelta.core.Reason
import jdelta.core.ReasonCode
import jdelta.core.ResourceId
import jdelta.core.SemanticDelta
import jdelta.core.SourceSetId
import org.objectweb.asm.Opcodes

/**
 * baseline과 current snapshot을 비교해 [SemanticDelta]를 만든다 (ARCHITECTURE.md §5, §6.2).
 *
 * 영향 level은 member의 노출 범위([Scope])로 정한다.
 * public이면 DOWNSTREAM, package-private이면 LOCAL, private이면 NONE.
 * fingerprint가 다른데 어떤 delta로도 설명되지 않으면 [DeltaKind.UNKNOWN]을 낸다. 조용히 무시하지 않는다.
 */
public class DeltaClassifier {
    public fun diff(base: ProjectSnapshot, head: ProjectSnapshot): List<SemanticDelta> {
        val sink = DeltaSink()
        val context = Context(Exposure(base), Exposure(head), sink)
        val ids = (base.sourceSets.map { it.id } + head.sourceSets.map { it.id }).distinct()
        for (id in ids) {
            SourceSetDiff(context, base.sourceSet(id) ?: empty(id), head.sourceSet(id) ?: empty(id)).run()
        }
        markMovedClasses(sink)
        return sink.result()
    }

    private fun empty(id: SourceSetId) = SourceSetSnapshot(id, emptyMap())

    /** 같은 internal name이 한 module에서 사라지고 다른 module에 생겼으면 "moved"로 표시한다. */
    private fun markMovedClasses(sink: DeltaSink) {
        val removed = sink.deltas.filter { it.kind == DeltaKind.CLASS_REMOVED }.map { it.subject as ClassId }
        val added = sink.deltas.filter { it.kind == DeltaKind.CLASS_ADDED }.map { it.subject as ClassId }
        val addedByName = added.groupBy { it.internalName }
        for (from in removed) {
            val to = addedByName[from.internalName]?.firstOrNull { it.module != from.module } ?: continue
            val reason = Reason(ReasonCode.CLASS_MOVED, "moved from ${from.module} to ${to.module}", listOf(from, to))
            sink.amend(from, DeltaKind.CLASS_REMOVED, reason)
            sink.amend(to, DeltaKind.CLASS_ADDED, reason)
        }
    }

    private class Context(val baseExposure: Exposure, val headExposure: Exposure, val sink: DeltaSink)

    private class SourceSetDiff(
        private val ctx: Context,
        private val base: SourceSetSnapshot,
        private val head: SourceSetSnapshot,
    ) {
        private val sink = ctx.sink
        private val module = base.id.module

        fun run() {
            val names = (base.classes.keys + head.classes.keys).toSortedSet()
            for (name in names) {
                val b = base.classes[name]
                val h = head.classes[name]
                if ((b ?: h)!!.isBodyDependent) continue // enclosing method 단위로 따로 비교
                when {
                    b == null -> classAdded(h!!)
                    h == null -> classRemoved(b)
                    else -> ClassDiff(b, h).run()
                }
            }
            localClassGroups()
            resources()
            unparseable()
            if (base.moduleDescriptorHash != head.moduleDescriptorHash) {
                sink.add(unknown(base.id, ReasonCode.MODULE_DESCRIPTOR_CHANGED, "module-info.class changed; module descriptors are not analyzed in v0.1"))
            }
        }

        private fun classAdded(h: ClassSnapshot) {
            val scope = ctx.headExposure.classScope(h)
            sink.add(
                delta(
                    h.id, DeltaKind.CLASS_ADDED,
                    compile = compileLevel(scope),
                    reflection = LOCAL,
                    confidence = if (scope == Scope.PUBLIC) Confidence.INFERRED else Confidence.EXACT,
                    reasons = listOf(Reason(ReasonCode.MEMBER_ADDED, "class added")) +
                        if (scope == Scope.PUBLIC) listOf(Reason(ReasonCode.NAME_RESOLUTION_MAY_CHANGE, "a new visible class can shadow on-demand imports downstream")) else scopeReasons(scope),
                ),
            )
        }

        private fun classRemoved(b: ClassSnapshot) {
            val scope = ctx.baseExposure.classScope(b)
            sink.add(
                delta(
                    b.id, DeltaKind.CLASS_REMOVED,
                    compile = compileLevel(scope), binary = compileLevel(scope), reflection = LOCAL,
                    reasons = listOf(Reason(ReasonCode.MEMBER_REMOVED, "class removed")) + scopeReasons(scope),
                ),
            )
        }

        /** local/anonymous class를 (최상위 소유 class, enclosing method) 그룹으로 묶어 내용 목록을 비교한다 (§3.4). */
        private fun localClassGroups() {
            val baseGroups = groups(base)
            val headGroups = groups(head)
            for (key in (baseGroups.keys + headGroups.keys)) {
                if (baseGroups[key] == headGroups[key]) continue
                val (outer, methodKey) = key
                if (outer !in base.classes || outer !in head.classes) continue // class 추가/삭제가 이미 설명한다
                val ownerId = ClassId(module, outer)
                val subject: NodeId = methodKey?.let { ownerId.method(it.substringBefore('('), "(" + it.substringAfter('(')) } ?: ownerId
                val before = baseGroups[key]?.size ?: 0
                val after = headGroups[key]?.size ?: 0
                val message = "local/anonymous classes changed ($before -> $after classes)"
                // inline function 안의 anonymous object는 call site마다 다시 만들어진다
                val inlineScope = methodKey?.let { inlineMethodScope(outer, it) }
                if (inlineScope != null) {
                    sink.add(inlineBodyDelta(subject, inlineScope, message))
                    continue
                }
                sink.add(delta(subject, DeltaKind.METHOD_BODY_CHANGED, reasons = listOf(Reason(ReasonCode.LOCAL_CLASS_CHANGED, message))))
            }
        }

        /** [owner]의 [methodKey]가 private이 아닌 Kotlin inline function이면 그 범위. */
        private fun inlineMethodScope(owner: String, methodKey: String): Scope? {
            val c = head.classes[owner] ?: return null
            if (c.kotlin?.isInlineMethod(methodKey) != true) return null
            val method = c.methods.firstOrNull { it.key == methodKey } ?: return null
            return ctx.headExposure.memberScope(c, method).takeIf { it != Scope.PRIVATE }
        }

        /** inline function body는 caller classfile에 복사된다. 이미 compile된 caller는 recompile 전까지 옛 body를 실행한다 (§3.6 규칙 2). */
        private fun inlineBodyDelta(subject: NodeId, scope: Scope, message: String) = delta(
            subject, DeltaKind.KOTLIN_INLINE_BODY_CHANGED,
            compile = compileLevel(scope),
            binary = if (scope == Scope.PUBLIC) RUNTIME else NONE,
            reasons = listOf(
                Reason(ReasonCode.KOTLIN_INLINE_BODY_CHANGED, "$message; the body is copied into callers, which keep the old copy until recompiled"),
            ) + scopeReasons(scope),
        )

        private fun groups(snapshot: SourceSetSnapshot): Map<Pair<String, String?>, List<String>> {
            val locals = snapshot.classes.values.filter { it.isBodyDependent }
            return locals
                .groupBy { rootKey(it, snapshot) }
                .mapValues { (_, list) -> list.sortedWith(NATURAL_NAME_ORDER).map { it.fingerprints.content } }
        }

        /** local class 안의 local class는 최상위 non-local class의 그룹으로 올린다. */
        private fun rootKey(c: ClassSnapshot, snapshot: SourceSetSnapshot): Pair<String, String?> {
            var current = c
            val seen = HashSet<String>()
            while (seen.add(current.internalName)) {
                val outerName = current.enclosingClassName ?: break
                val outer = snapshot.classes[outerName]
                if (outer == null || !outer.isBodyDependent) return outerName to current.enclosingMethod?.methodKey
                current = outer
            }
            return (current.enclosingClassName ?: current.internalName) to current.enclosingMethod?.methodKey
        }

        private fun resources() {
            for (path in (base.resources.keys + head.resources.keys).toSortedSet()) {
                val b = base.resources[path]
                val h = head.resources[path]
                if (b == h) continue
                val (code, message) = when {
                    b == null -> ReasonCode.RESOURCE_ADDED to "resource added"
                    h == null -> ReasonCode.RESOURCE_REMOVED to "resource removed"
                    else -> ReasonCode.RESOURCE_MODIFIED to "resource content changed"
                }
                sink.add(
                    delta(
                        ResourceId(base.id, path), DeltaKind.RESOURCE_CHANGED,
                        reflection = RUNTIME, framework = RUNTIME,
                        confidence = Confidence.CONSERVATIVE_UNKNOWN,
                        reasons = listOf(
                            Reason(code, message),
                            Reason(ReasonCode.RESOURCE_CONSUMERS_UNKNOWN, "resource readers are not traced in v0.1"),
                        ),
                    ),
                )
            }
        }

        private fun unparseable() {
            for (name in (base.unparseable.keys + head.unparseable.keys).toSortedSet()) {
                if (base.unparseable[name] == head.unparseable[name]) continue
                sink.add(unknown(ClassId(module, name), ReasonCode.UNPARSEABLE_CLASSFILE, "classfile could not be parsed"))
            }
        }

        private inner class ClassDiff(private val b: ClassSnapshot, private val h: ClassSnapshot) {
            private val id = h.id
            private val classScope = ctx.baseExposure.classScope(b) wider ctx.headExposure.classScope(h)

            fun run() {
                if (b.fingerprints == h.fingerprints) return
                val before = sink.count(id)
                classLevel()
                enumOrder()
                fields()
                methods()
                if (sink.count(id) == before) {
                    sink.add(unknown(id, ReasonCode.UNEXPLAINED_FINGERPRINT_CHANGE, "fingerprint changed but no semantic delta explains it"))
                }
            }

            private fun classLevel() {
                val bAccess = AbiFingerprints.effectiveClassAccess(b) and AbiFingerprints.CLASS_COMPILE_MASK
                val hAccess = AbiFingerprints.effectiveClassAccess(h) and AbiFingerprints.CLASS_COMPILE_MASK
                if (bAccess != hAccess) {
                    val breaking = isLinkageBreaking(bAccess, hAccess, Opcodes.ACC_INTERFACE or Opcodes.ACC_STATIC or Opcodes.ACC_ENUM or Opcodes.ACC_ANNOTATION)
                    sink.add(
                        delta(
                            id, DeltaKind.CLASS_ACCESS_CHANGED,
                            compile = compileLevel(classScope),
                            binary = if (breaking) compileLevel(classScope) else NONE,
                            reflection = LOCAL,
                            reasons = accessReasons(bAccess, hAccess, breaking),
                        ),
                    )
                }
                if (b.superName != h.superName || b.interfaces != h.interfaces) {
                    sink.add(
                        delta(
                            id, DeltaKind.CLASS_HIERARCHY_CHANGED,
                            compile = compileLevel(classScope), binary = compileLevel(classScope), reflection = LOCAL,
                            reasons = listOf(
                                Reason(ReasonCode.HIERARCHY_CHANGED, "extends ${b.superName} implements ${b.interfaces} -> extends ${h.superName} implements ${h.interfaces}"),
                            ) + scopeReasons(classScope),
                        ),
                    )
                }
                annotationDelta(id, DeltaKind.CLASS_ANNOTATION_CHANGED, b.annotations, h.annotations)
                if (b.signature != h.signature) signatureDelta(id, b.signature, h.signature, classScope)
                if (b.permittedSubclasses != h.permittedSubclasses) {
                    sink.add(
                        delta(
                            id, DeltaKind.PERMITTED_SUBCLASSES_CHANGED,
                            compile = compileLevel(classScope), reflection = LOCAL,
                            reasons = listOf(Reason(ReasonCode.SEALED_HIERARCHY_CHANGED, "permits ${b.permittedSubclasses} -> ${h.permittedSubclasses}; exhaustive switches may break")),
                        ),
                    )
                }
                if (b.recordComponents != h.recordComponents) {
                    sink.add(
                        delta(
                            id, DeltaKind.RECORD_COMPONENTS_CHANGED,
                            compile = compileLevel(classScope), reflection = LOCAL,
                            reasons = listOf(Reason(ReasonCode.RECORD_SHAPE_CHANGED, "record components ${b.recordComponents.map { it.name }} -> ${h.recordComponents.map { it.name }}")),
                        ),
                    )
                }
                kotlinMetadata()
            }

            /**
             * Kotlin metadata 비교 (§3.6 규칙 5). JVM descriptor로 드러나지 않는 Kotlin 선언 변경(nullability, 기본값,
             * parameter 이름, modifier 등)만 보고한다. JVM member 추가/삭제는 Java 규칙이 이미 설명한다.
             */
            private fun kotlinMetadata() {
                if (b.kotlinViewHash == h.kotlinViewHash) return
                val bk = b.kotlin
                val hk = h.kotlin
                if (bk == null || hk == null) {
                    sink.add(
                        delta(
                            id, DeltaKind.KOTLIN_METADATA_CHANGED,
                            compile = compileLevel(classScope), reflection = LOCAL,
                            confidence = Confidence.CONSERVATIVE_UNKNOWN,
                            reasons = listOf(
                                Reason(ReasonCode.KOTLIN_METADATA_CHANGED, "kotlin.Metadata changed"),
                                Reason(ReasonCode.KOTLIN_METADATA_NOT_ANALYZED, "kotlin.Metadata could not be read; Kotlin callers are assumed affected"),
                            ),
                        ),
                    )
                    return
                }
                if (bk.kind != hk.kind || bk.header != hk.header) {
                    sink.add(kotlinDelta(id, classScope, "class ${bk.header} -> ${hk.header}"))
                }
                for (key in (bk.declarations.keys + hk.declarations.keys).toSortedSet()) {
                    val old = bk.declarations[key]
                    val new = hk.declarations[key]
                    when {
                        old != null && new != null -> if (old.canonical != new.canonical) {
                            val scope = ctx.baseExposure.declarationScope(b, old) wider ctx.headExposure.declarationScope(h, new)
                            sink.add(kotlinDelta(subjectOf(new, h), scope, "${old.canonical} -> ${new.canonical}"))
                        }
                        new != null -> if (!jvmMemberChanged(new, b)) {
                            sink.add(kotlinDelta(subjectOf(new, h), ctx.headExposure.declarationScope(h, new), "added ${new.canonical}"))
                        }
                        old != null -> if (!jvmMemberChanged(old, h)) {
                            sink.add(kotlinDelta(subjectOf(old, b), ctx.baseExposure.declarationScope(b, old), "removed ${old.canonical}"))
                        }
                    }
                }
            }

            /** 선언이 만든 JVM member 중 [other] 쪽에 없는 것이 있으면 Java 규칙(METHOD_ADDED 등)이 이미 보고했다. */
            private fun jvmMemberChanged(d: KotlinDeclaration, other: ClassSnapshot): Boolean =
                d.jvmMethods.any { key -> other.methods.none { it.key == key } } ||
                    (d.jvmField != null && other.fields.none { it.name == d.jvmField })

            private fun subjectOf(d: KotlinDeclaration, c: ClassSnapshot): NodeId {
                d.jvmMethods.firstOrNull()?.let { return id.method(it.substringBefore('('), "(" + it.substringAfter('(')) }
                d.jvmField?.let { name -> c.fields.firstOrNull { it.name == name }?.let { return id.field(it.name, it.descriptor) } }
                return id
            }

            private fun kotlinDelta(subject: NodeId, scope: Scope, message: String) = delta(
                subject, DeltaKind.KOTLIN_METADATA_CHANGED,
                compile = compileLevel(scope), reflection = LOCAL,
                confidence = Confidence.INFERRED,
                reasons = listOf(
                    Reason(ReasonCode.KOTLIN_METADATA_CHANGED, message),
                    Reason(ReasonCode.KOTLIN_CALLERS_ONLY, "only Kotlin callers read kotlin.Metadata; Java callers are unaffected"),
                ) + scopeReasons(scope),
            )

            /** ordinal이 바뀐 enum constant가 있으면 JPA ORDINAL, EnumSet 직렬화 등이 깨질 수 있다. */
            private fun enumOrder() {
                val bOrder = b.fields.filter { it.access.has(Opcodes.ACC_ENUM) }.map { it.name }
                val hOrder = h.fields.filter { it.access.has(Opcodes.ACC_ENUM) }.map { it.name }
                val moved = bOrder.withIndex().filter { (i, name) -> name in hOrder && hOrder.indexOf(name) != i }.map { it.value }
                if (moved.isEmpty()) return
                sink.add(
                    delta(
                        id, DeltaKind.ENUM_CONSTANT_ORDER_CHANGED,
                        reflection = RUNTIME, framework = RUNTIME,
                        confidence = Confidence.INFERRED,
                        reasons = listOf(Reason(ReasonCode.ENUM_ORDINAL_CHANGED, "ordinal changed for $moved")),
                    ),
                )
            }

            private fun fields() {
                val bf = b.fields.associateBy { it.key }
                val hf = h.fields.associateBy { it.key }
                pairMembers(
                    removed = (bf.keys - hf.keys).map { bf.getValue(it) },
                    added = (hf.keys - bf.keys).map { hf.getValue(it) },
                    name = { it.name },
                    onChanged = { old, new ->
                        val scope = memberScope(old, new)
                        sink.add(
                            delta(
                                id.field(new.name, new.descriptor), DeltaKind.FIELD_DESCRIPTOR_CHANGED,
                                compile = compileLevel(scope), binary = compileLevel(scope), reflection = LOCAL,
                                reasons = listOf(Reason(ReasonCode.DESCRIPTOR_CHANGED, "type ${old.descriptor} -> ${new.descriptor}", listOf(id.field(old.name, old.descriptor)))) + scopeReasons(scope),
                            ),
                        )
                    },
                    onRemoved = { f ->
                        val scope = ctx.baseExposure.memberScope(b, f)
                        sink.add(
                            delta(
                                id.field(f.name, f.descriptor), DeltaKind.FIELD_REMOVED,
                                compile = compileLevel(scope), binary = compileLevel(scope), reflection = LOCAL,
                                reasons = listOf(Reason(ReasonCode.MEMBER_REMOVED, "field removed")) + scopeReasons(scope),
                            ),
                        )
                    },
                    onAdded = { f ->
                        val scope = ctx.headExposure.memberScope(h, f)
                        sink.add(
                            delta(
                                id.field(f.name, f.descriptor), DeltaKind.FIELD_ADDED,
                                compile = compileLevel(scope), reflection = LOCAL,
                                confidence = if (scope == Scope.PRIVATE) Confidence.EXACT else Confidence.INFERRED,
                                reasons = listOf(Reason(ReasonCode.MEMBER_ADDED, "field added")) +
                                    if (scope == Scope.PRIVATE) scopeReasons(scope) else listOf(Reason(ReasonCode.NAME_RESOLUTION_MAY_CHANGE, "a new field can hide an inherited field")),
                            ),
                        )
                    },
                )
                for (key in bf.keys intersect hf.keys) fieldChanges(bf.getValue(key), hf.getValue(key))
            }

            private fun fieldChanges(old: FieldSnapshot, new: FieldSnapshot) {
                val subject = id.field(new.name, new.descriptor)
                val scope = memberScope(old, new)
                accessDelta(subject, DeltaKind.FIELD_ACCESS_CHANGED, old.access, new.access, AbiFingerprints.FIELD_COMPILE_MASK, scope)
                annotationDelta(subject, DeltaKind.FIELD_ANNOTATION_CHANGED, old.annotations, new.annotations)
                if (old.signature != new.signature) signatureDelta(subject, old.signature, new.signature, scope)
                if (old.constantValue != new.constantValue) {
                    // javac는 constant를 caller에 inline한다. caller classfile에는 field 참조가 남지 않는다 (§6.3).
                    sink.add(
                        delta(
                            subject, DeltaKind.CONSTANT_VALUE_CHANGED,
                            compile = compileLevel(scope),
                            binary = if (scope == Scope.PUBLIC) RUNTIME else NONE,
                            reasons = listOf(
                                Reason(ReasonCode.CONSTANT_INLINED_AT_CALLERS, "constant ${display(old.constantValue)} -> ${display(new.constantValue)}; compiled callers keep the old inlined value until recompiled"),
                            ) + scopeReasons(scope),
                        ),
                    )
                }
            }

            private fun methods() {
                val bm = b.methods.filter { it.role == MethodRole.NORMAL }.associateBy { it.key }
                val hm = h.methods.filter { it.role == MethodRole.NORMAL }.associateBy { it.key }
                pairMembers(
                    removed = (bm.keys - hm.keys).map { bm.getValue(it) },
                    added = (hm.keys - bm.keys).map { hm.getValue(it) },
                    name = { it.name },
                    onChanged = { old, new ->
                        val scope = memberScope(old, new)
                        sink.add(
                            delta(
                                id.method(new.name, new.descriptor), DeltaKind.METHOD_DESCRIPTOR_CHANGED,
                                compile = compileLevel(scope), binary = compileLevel(scope), reflection = LOCAL,
                                reasons = listOf(Reason(ReasonCode.DESCRIPTOR_CHANGED, "descriptor ${old.descriptor} -> ${new.descriptor}", listOf(id.method(old.name, old.descriptor)))) + scopeReasons(scope),
                            ),
                        )
                    },
                    onRemoved = { m ->
                        val scope = ctx.baseExposure.memberScope(b, m)
                        sink.add(
                            delta(
                                id.method(m.name, m.descriptor), DeltaKind.METHOD_REMOVED,
                                compile = if (m.isSynthetic) NONE else compileLevel(scope), binary = compileLevel(scope), reflection = LOCAL,
                                reasons = listOf(Reason(ReasonCode.MEMBER_REMOVED, "method removed")) + syntheticReasons(m) + scopeReasons(scope),
                            ),
                        )
                    },
                    onAdded = { m -> methodAdded(m) },
                )
                for (key in bm.keys intersect hm.keys) methodChanges(bm.getValue(key), hm.getValue(key))
                bridges()
            }

            /**
             * bridge method는 compiler가 만들지만 이미 compile된 caller가 실제로 link하는 대상이다.
             * 예: package-private class의 public method를 public subclass에 노출하는 visibility bridge.
             * 원인이 된 선언 변경은 다른 delta가 설명하므로 여기서는 binary 영향만 기록한다.
             */
            private fun bridges() {
                val bb = b.methods.filter { it.role == MethodRole.BRIDGE }.associateBy { it.key }
                val hb = h.methods.filter { it.role == MethodRole.BRIDGE }.associateBy { it.key }
                for (key in (bb.keys - hb.keys)) {
                    val m = bb.getValue(key)
                    val scope = ctx.baseExposure.memberScope(b, m.access)
                    sink.add(
                        delta(
                            id.method(m.name, m.descriptor), DeltaKind.METHOD_REMOVED,
                            binary = compileLevel(scope),
                            reasons = listOf(Reason(ReasonCode.BRIDGE_CHANGED, "compiler-generated bridge removed; callers compiled against it fail to link")),
                        ),
                    )
                }
                for (key in (hb.keys - bb.keys)) {
                    val m = hb.getValue(key)
                    sink.add(
                        delta(
                            id.method(m.name, m.descriptor), DeltaKind.METHOD_ADDED,
                            reasons = listOf(Reason(ReasonCode.BRIDGE_CHANGED, "compiler-generated bridge added")),
                        ),
                    )
                }
            }

            private fun methodAdded(m: MethodSnapshot) {
                val scope = ctx.headExposure.memberScope(h, m)
                val subject = id.method(m.name, m.descriptor)
                val abstractAdded = m.access.has(Opcodes.ACC_ABSTRACT)
                val delta = when {
                    // interface/abstract class에 abstract method 추가: 기존 구현체가 compile도 link도 깨진다
                    abstractAdded -> delta(
                        subject, DeltaKind.METHOD_ADDED,
                        compile = compileLevel(scope), binary = compileLevel(scope), reflection = LOCAL,
                        reasons = listOf(Reason(ReasonCode.ABSTRACT_METHOD_ADDED, "abstract method added; existing implementations must change")) + scopeReasons(scope),
                    )
                    scope == Scope.PRIVATE || m.isSynthetic -> delta(
                        subject, DeltaKind.METHOD_ADDED, reflection = LOCAL,
                        reasons = listOf(Reason(ReasonCode.MEMBER_ADDED, "method added")) + syntheticReasons(m) + scopeReasons(scope),
                    )
                    else -> delta(
                        subject, DeltaKind.METHOD_ADDED,
                        compile = compileLevel(scope), reflection = LOCAL,
                        confidence = Confidence.INFERRED,
                        reasons = listOf(
                            Reason(ReasonCode.MEMBER_ADDED, "method added"),
                            Reason(ReasonCode.OVERLOAD_RESOLUTION_MAY_CHANGE, "a new overload or override can change call resolution in dependents"),
                        ) + scopeReasons(scope),
                    )
                }
                sink.add(delta)
            }

            private fun methodChanges(old: MethodSnapshot, new: MethodSnapshot) {
                val subject = id.method(new.name, new.descriptor)
                val scope = memberScope(old, new)
                val before = sink.count(subject)

                accessDelta(subject, DeltaKind.METHOD_ACCESS_CHANGED, old.access, new.access, AbiFingerprints.METHOD_COMPILE_MASK, scope)
                annotationDelta(
                    subject, DeltaKind.METHOD_ANNOTATION_CHANGED,
                    old.annotations + old.parameterAnnotations.flatten(),
                    new.annotations + new.parameterAnnotations.flatten(),
                    parameterAnnotationsChanged = old.parameterAnnotations != new.parameterAnnotations,
                )
                if (old.annotationDefault != new.annotationDefault) {
                    sink.add(
                        delta(
                            subject, DeltaKind.METHOD_ANNOTATION_CHANGED,
                            compile = compileLevel(scope), reflection = LOCAL,
                            reasons = listOf(Reason(ReasonCode.ANNOTATION_DEFAULT_CHANGED, "annotation element default ${old.annotationDefault} -> ${new.annotationDefault}")),
                        ),
                    )
                }
                if (old.signature != new.signature) signatureDelta(subject, old.signature, new.signature, scope)
                if (old.exceptions != new.exceptions) {
                    sink.add(
                        delta(
                            subject, DeltaKind.METHOD_EXCEPTIONS_CHANGED,
                            compile = compileLevel(scope), test = NONE,
                            reasons = listOf(Reason(ReasonCode.THROWS_CHANGED, "throws ${old.exceptions} -> ${new.exceptions}")) + scopeReasons(scope),
                        ),
                    )
                }
                if (old.parameterNames != new.parameterNames) {
                    sink.add(
                        delta(
                            subject, DeltaKind.METHOD_PARAMETERS_CHANGED,
                            reflection = LOCAL, framework = RUNTIME,
                            confidence = Confidence.INFERRED,
                            reasons = listOf(Reason(ReasonCode.PARAMETER_NAMES_CHANGED, "parameter names ${old.parameterNames} -> ${new.parameterNames}; frameworks binding by name may behave differently")),
                        ),
                    )
                }
                val inline = b.kotlin?.isInlineMethod(old.key) == true || h.kotlin?.isInlineMethod(new.key) == true
                if (old.bodyHash != new.bodyHash && inline && scope != Scope.PRIVATE) {
                    sink.add(inlineBodyDelta(subject, scope, "inline function body changed"))
                } else if (old.bodyHash != new.bodyHash) {
                    val only = sink.count(subject) == before
                    sink.add(
                        delta(
                            subject, DeltaKind.METHOD_BODY_CHANGED,
                            reasons = listOf(
                                if (only) {
                                    Reason(ReasonCode.BODY_ONLY, "body changed; descriptor, access, annotations, signature and throws unchanged")
                                } else {
                                    Reason(ReasonCode.BODY_CHANGED, "body changed together with the declaration")
                                },
                            ),
                        ),
                    )
                }
            }

            /** synthetic method(Kotlin `foo$default` 등)는 source compile에서 보이지 않지만 compile된 caller가 link한다. */
            private fun syntheticReasons(m: MethodSnapshot): List<Reason> =
                if (m.isSynthetic) listOf(Reason(ReasonCode.SYNTHETIC_MEMBER, "compiler-generated synthetic method; invisible to source compilation but linked by compiled callers")) else emptyList()

            private fun accessDelta(subject: NodeId, kind: DeltaKind, oldAccess: Int, newAccess: Int, compileMask: Int, scope: Scope) {
                val oldCompile = oldAccess and compileMask
                val newCompile = newAccess and compileMask
                if (oldCompile != newCompile) {
                    val breaking = isLinkageBreaking(oldCompile, newCompile, Opcodes.ACC_STATIC)
                    sink.add(
                        delta(
                            subject, kind,
                            compile = compileLevel(scope),
                            binary = if (breaking) compileLevel(scope) else NONE,
                            reflection = LOCAL,
                            reasons = accessReasons(oldCompile, newCompile, breaking) + scopeReasons(scope),
                        ),
                    )
                } else if ((oldAccess and AbiFingerprints.REFLECTION_MASK) != (newAccess and AbiFingerprints.REFLECTION_MASK)) {
                    // transient, volatile, synchronized 등: compile ABI는 같고 reflection/실행 의미만 바뀐다
                    sink.add(
                        delta(
                            subject, kind, reflection = LOCAL,
                            reasons = listOf(Reason(ReasonCode.ACCESS_CHANGED, "modifiers ${modifiers(oldAccess)} -> ${modifiers(newAccess)}")),
                        ),
                    )
                }
            }

            private fun annotationDelta(
                subject: NodeId,
                kind: DeltaKind,
                old: List<AnnotationInfo>,
                new: List<AnnotationInfo>,
                parameterAnnotationsChanged: Boolean = false,
            ) {
                if (old == new && !parameterAnnotationsChanged) return
                val visibleChanged = old.filter { it.visible } != new.filter { it.visible } || parameterAnnotationsChanged
                val reasons = mutableListOf(Reason(ReasonCode.ANNOTATION_CHANGED, describeAnnotationChange(old, new)))
                if (visibleChanged) {
                    reasons += Reason(ReasonCode.RUNTIME_VISIBLE_ANNOTATION, "runtime-visible annotations are read by frameworks via reflection")
                }
                sink.add(
                    delta(
                        subject, kind,
                        compile = LOCAL,
                        reflection = if (visibleChanged) LOCAL else NONE,
                        framework = if (visibleChanged) RUNTIME else NONE,
                        confidence = Confidence.INFERRED,
                        reasons = reasons,
                    ),
                )
            }

            private fun signatureDelta(subject: NodeId, old: String?, new: String?, scope: Scope) {
                sink.add(
                    delta(
                        subject, DeltaKind.GENERIC_SIGNATURE_CHANGED,
                        compile = compileLevel(scope), reflection = LOCAL,
                        reasons = listOf(Reason(ReasonCode.GENERIC_SIGNATURE_CHANGED, "generic signature ${old ?: "(none)"} -> ${new ?: "(none)"}")) + scopeReasons(scope),
                    ),
                )
            }

            private fun memberScope(old: MethodSnapshot, new: MethodSnapshot): Scope =
                ctx.baseExposure.memberScope(b, old) wider ctx.headExposure.memberScope(h, new)

            private fun memberScope(old: FieldSnapshot, new: FieldSnapshot): Scope =
                ctx.baseExposure.memberScope(b, old) wider ctx.headExposure.memberScope(h, new)

            private fun scopeReasons(scope: Scope): List<Reason> {
                val reasons = this@SourceSetDiff.scopeReasons(scope)
                if (scope == Scope.PUBLIC && (ctx.headExposure.isExposedOnlyViaSubclass(h) || ctx.baseExposure.isExposedOnlyViaSubclass(b))) {
                    return reasons + Reason(ReasonCode.EXPOSED_VIA_PUBLIC_SUBCLASS, "class is not public but its members are inherited by a public subclass")
                }
                return reasons
            }
        }

        private fun scopeReasons(scope: Scope): List<Reason> = when (scope) {
            Scope.PUBLIC -> emptyList()
            Scope.PACKAGE -> listOf(Reason(ReasonCode.PACKAGE_PRIVATE_SCOPE, "not visible outside module $module"))
            Scope.PRIVATE -> listOf(Reason(ReasonCode.PRIVATE_SCOPE, "private; not visible to other classes"))
        }
    }

    private companion object {
        /** `Outer$2`가 `Outer$10`보다 앞에 오도록 길이 우선 정렬 */
        val NATURAL_NAME_ORDER: Comparator<ClassSnapshot> =
            compareBy<ClassSnapshot> { it.internalName.length }.thenBy { it.internalName }

        fun compileLevel(scope: Scope): ImpactLevel = when (scope) {
            Scope.PUBLIC -> DOWNSTREAM
            Scope.PACKAGE -> LOCAL
            Scope.PRIVATE -> NONE
        }

        fun delta(
            subject: NodeId,
            kind: DeltaKind,
            compile: ImpactLevel = NONE,
            binary: ImpactLevel = NONE,
            reflection: ImpactLevel = NONE,
            framework: ImpactLevel = NONE,
            test: ImpactLevel = DOWNSTREAM,
            confidence: Confidence = Confidence.EXACT,
            reasons: List<Reason>,
        ) = SemanticDelta(subject, kind, compile, binary, reflection, framework, test, confidence, reasons)

        fun unknown(subject: NodeId, code: ReasonCode, message: String) = SemanticDelta(
            subject, DeltaKind.UNKNOWN,
            ImpactLevel.UNKNOWN, ImpactLevel.UNKNOWN, ImpactLevel.UNKNOWN, ImpactLevel.UNKNOWN, ImpactLevel.UNKNOWN,
            Confidence.CONSERVATIVE_UNKNOWN, listOf(Reason(code, message)),
        )

        /** 같은 이름에서 하나가 사라지고 하나가 생겼으면 descriptor 변경으로 묶는다 (§5.1). */
        fun <T> pairMembers(
            removed: List<T>,
            added: List<T>,
            name: (T) -> String,
            onChanged: (T, T) -> Unit,
            onRemoved: (T) -> Unit,
            onAdded: (T) -> Unit,
        ) {
            val removedByName = removed.groupBy(name)
            val addedByName = added.groupBy(name)
            val paired = removedByName.keys.filter { removedByName[it]!!.size == 1 && addedByName[it]?.size == 1 }.toSet()
            for (n in paired) onChanged(removedByName.getValue(n).single(), addedByName.getValue(n).single())
            removed.filter { name(it) !in paired }.forEach(onRemoved)
            added.filter { name(it) !in paired }.forEach(onAdded)
        }

        /** 접근 범위 축소, static/interface 전환, final/abstract 추가는 이미 compile된 caller의 link를 깨뜨린다. */
        fun isLinkageBreaking(old: Int, new: Int, kindFlags: Int): Boolean =
            Exposure.visibilityRank(new) < Exposure.visibilityRank(old) ||
                (old and kindFlags) != (new and kindFlags) ||
                (!old.has(Opcodes.ACC_FINAL) && new.has(Opcodes.ACC_FINAL)) ||
                (!old.has(Opcodes.ACC_ABSTRACT) && new.has(Opcodes.ACC_ABSTRACT))

        fun accessReasons(old: Int, new: Int, breaking: Boolean): List<Reason> {
            val message = "modifiers ${modifiers(old)} -> ${modifiers(new)}"
            return listOf(
                if (breaking) Reason(ReasonCode.LINKAGE_BREAKING_ACCESS_CHANGE, "$message; already compiled callers can fail to link") else Reason(ReasonCode.ACCESS_CHANGED, message),
            )
        }

        fun modifiers(access: Int): String {
            val names = listOf(
                Opcodes.ACC_PUBLIC to "public", Opcodes.ACC_PROTECTED to "protected", Opcodes.ACC_PRIVATE to "private",
                Opcodes.ACC_STATIC to "static", Opcodes.ACC_FINAL to "final", Opcodes.ACC_ABSTRACT to "abstract",
                Opcodes.ACC_INTERFACE to "interface", Opcodes.ACC_ENUM to "enum", Opcodes.ACC_ANNOTATION to "annotation",
                Opcodes.ACC_RECORD to "record", Opcodes.ACC_VARARGS to "varargs", Opcodes.ACC_SYNCHRONIZED to "synchronized",
            )
            // ACC_VARARGS == ACC_TRANSIENT, ACC_SYNCHRONIZED == ACC_SUPER 이지만 비교 대상 mask가 같으므로 표시용으로 충분하다
            return names.filter { access.has(it.first) }.joinToString(" ", "[", "]") { it.second }
        }

        fun describeAnnotationChange(old: List<AnnotationInfo>, new: List<AnnotationInfo>): String {
            val oldByDesc = old.groupBy { it.descriptor }
            val newByDesc = new.groupBy { it.descriptor }
            val parts = mutableListOf<String>()
            (newByDesc.keys - oldByDesc.keys).forEach { parts += "added ${simpleAnnotation(it)}" }
            (oldByDesc.keys - newByDesc.keys).forEach { parts += "removed ${simpleAnnotation(it)}" }
            (oldByDesc.keys intersect newByDesc.keys).filter { oldByDesc[it] != newByDesc[it] }.forEach {
                parts += "changed ${simpleAnnotation(it)} ${oldByDesc[it]!!.joinToString { a -> "(${a.values})" }} -> ${newByDesc[it]!!.joinToString { a -> "(${a.values})" }}"
            }
            return parts.joinToString("; ").ifEmpty { "annotations changed" }
        }

        fun simpleAnnotation(descriptor: String) = "@" + descriptor.removePrefix("L").removeSuffix(";").substringAfterLast('/')

        fun display(constant: String?): String = constant?.substringAfter(':')?.let { if (constant.startsWith("S:")) "\"$it\"" else it } ?: "(none)"
    }
}

/** (subject, kind)가 같은 delta는 하나로 합친다. 출력 순서는 결정적이다. */
private class DeltaSink {
    private val byKey = LinkedHashMap<Pair<NodeId, DeltaKind>, SemanticDelta>()
    private val countBySubjectOrOwner = HashMap<NodeId, Int>()

    val deltas: Collection<SemanticDelta> get() = byKey.values

    fun add(delta: SemanticDelta) {
        val key = delta.subject to delta.kind
        val existing = byKey[key]
        byKey[key] = if (existing == null) delta else merge(existing, delta)
        for (id in countKeys(delta.subject)) countBySubjectOrOwner.merge(id, 1, Int::plus)
    }

    fun amend(subject: NodeId, kind: DeltaKind, reason: Reason) {
        val key = subject to kind
        byKey[key]?.let { byKey[key] = it.copy(reasons = it.reasons + reason) }
    }

    /** subject 자신 또는 그 member에 대해 지금까지 추가된 delta 수 */
    fun count(id: NodeId): Int = countBySubjectOrOwner[id] ?: 0

    fun result(): List<SemanticDelta> =
        byKey.values.sortedWith(compareBy<SemanticDelta> { it.subject.canonical }.thenBy { it.kind.ordinal })

    private fun countKeys(subject: NodeId): List<NodeId> = when (subject) {
        is jdelta.core.MethodId -> listOf(subject, subject.owner)
        is jdelta.core.FieldId -> listOf(subject, subject.owner)
        else -> listOf(subject)
    }

    private fun merge(a: SemanticDelta, b: SemanticDelta) = a.copy(
        compileImpact = a.compileImpact max b.compileImpact,
        binaryImpact = a.binaryImpact max b.binaryImpact,
        reflectionImpact = a.reflectionImpact max b.reflectionImpact,
        frameworkImpact = a.frameworkImpact max b.frameworkImpact,
        testImpact = a.testImpact max b.testImpact,
        confidence = a.confidence weakest b.confidence,
        reasons = (a.reasons + b.reasons).distinct(),
    )
}
