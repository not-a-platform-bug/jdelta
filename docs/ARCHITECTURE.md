# jdelta 아키텍처 설계

이 문서는 [JDELTA_DESIGN.md](JDELTA_DESIGN.md)(무엇을, 왜)를 구현 가능한 수준(어떻게)으로 내린 기술 설계다. v0.1 범위를 구체적으로 정의하고, 이후 확장(Kotlin semantic ABI, ap-trace, Spring)이 들어올 자리를 미리 열어둔다.

용어와 철학은 JDELTA_DESIGN.md를 따른다. 이 문서에서 JDELTA_DESIGN.md와 다르게 결정한 부분은 **[변경]**, 새로 추가한 부분은 **[추가]**로 표시한다.

---

## 0. 설계 결정 요약

| # | 결정 | 근거 |
|---|------|------|
| D1 | core graph는 **immutable in-memory graph**를 실행마다 다시 구성한다. 저장하는 것은 graph가 아니라 graph의 입력(snapshot, trace, project model)이다. | persistent graph는 schema migration 비용이 크다. 10k class 규모에서 재구성은 수백 ms 수준으로 예상되며, `SemanticGraph` interface 뒤에 숨겨 나중에 교체할 수 있다. |
| D2 | method body fingerprint는 **처음부터 debug info를 제거**한다(line number, local variable table, frame). | 주석 한 줄 추가로 아래쪽 모든 method의 line number가 바뀐다. 이걸 body change로 보면 impacted test가 의미 없어진다. |
| D3 | Kotlin Metadata는 v0.1에서 `kotlin-metadata-jvm`(public library)으로 parse해서 선언별 Kotlin view(visibility, inline, JVM signature mapping, nullability·기본값·parameter 이름 등)를 만든다. JVM으로 드러나지 않는 선언 차이는 `KOTLIN_METADATA_CHANGED`(`INFERRED`), metadata를 못 읽으면 `CONSERVATIVE_UNKNOWN` (§3.6). | Kotlin compiler internal에 의존하지 않으면서 inline/const/internal이라는 가장 위험한 세 가지는 정확히 잡는다. |
| D4 | CLI binary 이름은 처음부터 `jdelta`. | 문서, 출력, `.jdelta/` 디렉터리와 일관된다. |
| D5 | Java agent는 **method-level entry probe**로 시작한다. report에서는 class-level 집계를 함께 보여준다. | entry probe 하나의 비용은 class-level과 거의 같다. 정밀도는 method-level이 훨씬 높다. |
| D6 | report JSON은 `schemaVersion`을 붙이되 **1.0 전까지 unstable**로 명시한다. 사람용 계약은 Markdown이다. | v0.x에서 schema를 고정하면 모델 개선이 막힌다. |
| D7 | **[변경]** v0.1 module에 `jdelta-impact`, `jdelta-engine`, `jdelta-agent`를 추가한다. | solver 구현을 core 밖으로 빼서 core를 contract만 남긴다. CLI와 Gradle plugin이 orchestration을 중복하지 않게 한다. trace는 agent 없이 불가능하다. |
| D8 | test JVM 안에서 도는 코드(`jdelta-agent`, `jdelta-junit`)는 **plain Java, runtime dependency 0개**(ASM은 shading)로 작성한다. | 사용자 test classpath에 Kotlin stdlib나 kotlinx.serialization을 끼워 넣으면 사용자의 Kotlin 버전과 충돌한다. |
| D9 | baseline snapshot은 **commit SHA 단위 캐시**에서 찾고, 없으면 명시적 opt-in(`--build-baseline`)으로 임시 git worktree에서 build한다. | 조용히 baseline을 build하면 느리고 놀랍다. 조용히 틀린 baseline을 쓰는 것은 더 나쁘다. |
| D10 | Gradle 연동은 **init script로 주입한 plugin**이 project model을 export하는 방식을 기본으로 한다. 사용자가 build script를 수정하지 않아도 `jdelta diff`가 동작한다. | Stage 1 adoption(report만 생성)의 비용을 0에 가깝게 만든다. Tooling API는 dependency가 무겁다. |
| D11 | Git 연동은 `git` CLI process 호출로 한다(JGit 미사용). | 최소 dependency. worktree, merge-base 동작이 사용자의 git과 완전히 같다. |
| D12 | hash는 JDK `MessageDigest` SHA-256의 앞 128bit(hex 32자). | 외부 dependency가 없고, 충돌 확률은 무시할 수 있다. |
| D13 | **[추가]** `jdelta-gradle-plugin`은 **plain Java, runtime dependency 0개**로 작성한다. engine이 필요한 task(impacted-first 등, 15단계)는 Worker API의 classloader/process isolation으로 engine classpath를 따로 띄운다. | Gradle은 자신이 내장한 Kotlin stdlib를 plugin에 노출한다. jdelta가 쓰는 더 새로운 Kotlin stdlib를 plugin classpath에 섞으면 Gradle version에 따라 깨진다. |

---

## 1. Module 구조

### 1.1 Module 목록 (v0.1)

```text
jdelta-core            model + contract. 외부 dependency 없음 (Kotlin stdlib만)
jdelta-classfile       ASM 기반 snapshot, ABI extraction, fingerprint, delta classifier
jdelta-trace           trace model, trace store (Kotlin; agent가 쓴 raw trace를 읽음)
jdelta-impact   [추가]  graph 구성, typed traversal, rule table, framework profile, work plan
jdelta-report          Markdown / JSON renderer
jdelta-engine   [추가]  orchestration facade: snapshot 로딩 -> diff -> solve -> report
jdelta-agent    [이동]  Java agent + bootstrap runtime (plain Java, ASM shaded)
jdelta-junit           JUnit Platform TestExecutionListener (plain Java, JUnit compileOnly)
jdelta-gradle-plugin   project model export, record/diff/impacted-first task
jdelta-cli             CLI UX, git 연동, Gradle init script 주입
```

확장 module(v0.1 이후): `jdelta-kotlin`(full Kotlin semantic ABI), `jdelta-aptrace`, `jdelta-spring`, `jdelta-maven`.

### 1.2 의존 방향

```text
                        jdelta-core
          ┌──────────┬──────┴──────┬────────────┐
   jdelta-classfile  jdelta-trace  jdelta-impact  jdelta-report
          └──────────┴──────┬──────┴────────────┘
                       jdelta-engine
                     ┌──────┴──────┐
              jdelta-cli    jdelta-gradle-plugin

   (test JVM 안에서 실행, core와 무관)
   jdelta-agent   <- raw trace 파일 ->   jdelta-trace
   jdelta-junit   -- bootstrap runtime API --> jdelta-agent
```

규칙:

- `jdelta-core`는 ASM, Gradle, JUnit, Spring, Kotlin compiler API 어느 것에도 의존하지 않는다.
- `jdelta-impact`는 core에만 의존한다. classfile이나 trace의 구현 타입을 모른다. 이들은 core의 `Node`/`Edge`/`SemanticDelta`로 번역되어 들어온다.
- `jdelta-agent`, `jdelta-junit`은 jdelta의 다른 module에 compile 의존하지 않는다. raw trace 파일 형식(§8.5)이 유일한 계약이다.
- framework 지식(Spring, Jackson 등)은 v0.1에서 `jdelta-impact`의 **데이터**(annotation descriptor 문자열 → rule)로만 존재하고, framework library에 의존하지 않는다.

### 1.3 Build 설정

- Gradle Kotlin DSL, version catalog(`gradle/libs.versions.toml`), convention plugin은 `build-logic/` included build.
- Kotlin 2.x. 산출물은 Java 17 bytecode다(`--release 17`, Kotlin `-Xjdk-release=17`). 빌드 JDK는 17 이상이면 무엇이든 되고, toolchain 자동 다운로드는 강제하지 않는다. Kotlin module은 `explicitApi()`로 public API를 명시한다.
- dependency: ASM 9.x 최신(Java 25 classfile 지원 버전), `kotlin-metadata-jvm`, `kotlinx-serialization-json`, CLI는 `clikt-core`(mordant 없는 variant). test는 JUnit 5 + AssertJ 또는 kotest-assertions 중 하나.
- 패키지 루트는 `jdelta.*`(예: `jdelta.core`, `jdelta.classfile`)로 잠정한다. Maven group/plugin ID는 첫 publish 전에 확정한다.

---

## 2. Core 모델 (`jdelta-core`)

### 2.1 Stable ID

ID는 sealed class이고, 저장/비교용 canonical string을 가진다. canonical string은 JSON, trace, report에 그대로 나타나므로 사람이 읽을 수 있어야 한다.

```kotlin
public sealed interface NodeId { public val canonical: String }

public data class ModuleId(val path: String) : NodeId          // Gradle path, ":app"
public data class SourceSetId(val module: ModuleId, val name: String) : NodeId
public data class ClassId(val module: ModuleId, val internalName: String) : NodeId
public data class MethodId(val owner: ClassId, val name: String, val descriptor: String) : NodeId
public data class FieldId(val owner: ClassId, val name: String, val descriptor: String) : NodeId
public data class ResourceId(val sourceSet: SourceSetId, val path: String) : NodeId
public data class TestId(val engine: String, val className: String, val method: String?, val parameterTypes: String?) : NodeId
public data class TaskId(val module: ModuleId, val name: String) : NodeId
public data class FileId(val path: String) : NodeId             // repository root 기준 파일
```

canonical 형식:

```text
module      :app
sourceSet   :app@main
class       :app|com/acme/PriceCalculator
method      :app|com/acme/PriceCalculator#calculate(Lcom/acme/Order;)J
constructor :app|com/acme/PriceCalculator#<init>(Lcom/acme/Rules;)V
field       :app|com/acme/Timeouts.DEFAULT_MS:J
resource    :app@main/application.yml
test        junit-jupiter:com.acme.PriceCalculatorTest#calculatesDiscount()
file        //app/src/main/java/com/acme/PriceCalculator.java
```

결정 사항:

- **[변경]** constructor는 별도 ID 타입 없이 `MethodId(name = "<init>")`로 표현한다. node 타입(`ConstructorNode`)으로만 구분한다. `<clinit>`도 같은 방식이다.
- class ID에 module을 포함하므로 같은 FQCN이 두 module에 있어도(split package, 중복 class) 구분된다. agent는 `ProtectionDomain.getCodeSource()`의 location을 output dir → module 표로 매핑해 module을 붙인다(§8.2).
- test ID는 JUnit `MethodSource`의 class/method/parameter type에서 만든다. `@Nested`는 binary name(`Outer$Inner`), `@TestFactory`/`@ParameterizedTest`는 factory/template method 단위다. 개별 invocation은 구분하지 않는다.
- Kotlin top-level function은 file facade class(`FooKt`)의 static method로, companion 멤버는 `Foo$Companion`의 method로 ID를 받는다. Kotlin 이름은 node의 attribute(`kotlinName`)로 붙이고 ID에는 넣지 않는다. ID는 언제나 JVM 기준이다.

### 2.2 Node / Edge

```kotlin
public enum class NodeKind {
    PROJECT, MODULE, SOURCE_SET, SOURCE_FILE, CLASS, METHOD, CONSTRUCTOR, FIELD,
    ANNOTATION, RESOURCE, TEST_CLASS, TEST_METHOD, GRADLE_TASK,
    // 확장
    GENERATED_SOURCE, ANNOTATION_PROCESSOR, PROCESSOR_OPTION, SPRING_BEAN,
    SPRING_ENDPOINT, CONFIGURATION_PROPERTY, DEPENDENCY, RUNTIME_TRACE,
}

public data class Node(val id: NodeId, val kind: NodeKind, val attributes: Map<String, String> = emptyMap())

public data class Edge(
    val from: NodeId,
    val to: NodeId,
    val kind: EdgeKind,
    val confidence: Confidence,   // classfile에서 온 edge는 EXACT, trace는 OBSERVED, rule은 INFERRED
)
```

**Edge 방향 규약.** 모든 edge는 문서상 이름 그대로의 방향(`A CALLS B`, `M EXECUTED_BY_TEST T`)으로 저장하고, impact가 어느 방향으로 흐르는지는 `EdgeKind`가 스스로 선언한다.

```kotlin
public enum class Propagation { FORWARD, BACKWARD, NONE }

public enum class EdgeKind(public val impactFlow: Propagation) {
    BELONGS_TO(BACKWARD),         // member BELONGS_TO class: class 변경 -> member
    DEFINES(FORWARD),             // source file DEFINES class
    DECLARES(FORWARD),            // class DECLARES member
    REFERENCES(BACKWARD),         // A REFERENCES B: B 변경 -> A
    CALLS(BACKWARD),
    READS_FIELD(BACKWARD),
    WRITES_FIELD(BACKWARD),
    EXTENDS(BACKWARD),
    IMPLEMENTS(BACKWARD),
    OVERRIDES(BACKWARD),          // sub#m OVERRIDES super#m: dispatch 변화는 양방향, rule에서 처리
    ANNOTATED_BY(NONE),           // framework rule만 사용
    COMPILED_IN_MODULE(FORWARD),
    EXECUTED_BY_TEST(FORWARD),    // method EXECUTED_BY_TEST test
    DEPENDS_ON_MODULE(BACKWARD),  // [추가] :web DEPENDS_ON_MODULE :core (attribute: api|implementation|runtime)
    INLINED_INTO(FORWARD),        // [추가] Kotlin inline fun INLINED_INTO caller method
    TASK_INPUT(FORWARD),
    TASK_OUTPUT(BACKWARD),
    // 확장 edge는 JDELTA_DESIGN.md §6.4
}
```

`impactFlow`는 기본값일 뿐이다. 실제로 어떤 edge를 몇 단계까지 따라갈지는 delta kind별 traversal spec(§6.2)이 정한다.

### 2.3 Confidence lattice

```kotlin
public enum class Confidence { EXACT, OBSERVED, INFERRED, CONSERVATIVE_UNKNOWN }
```

선언 순서가 강한 순서다. 조합 규칙:

- **path confidence = path 위 edge와 시작 delta confidence 중 가장 약한 것**(min).
- **결론 confidence = 그 결론에 도달한 모든 path 중 가장 강한 것**(max).
- 같은 대상에 대해 서로 다른 reason이 있으면 reason은 모두 보존한다. confidence만 max로 합친다.

예: `PriceCalculator#calculate` body 변경(EXACT) → `EXECUTED_BY_TEST`(OBSERVED) → test. 결론은 OBSERVED.

### 2.4 Impact level

```kotlin
public enum class ImpactLevel { NONE, LOCAL, DOWNSTREAM, RUNTIME, UNKNOWN }
```

의미를 명확히 고정한다.

| level | 의미 |
|-------|------|
| `NONE` | 이 layer에서 영향 없음 |
| `LOCAL` | 소유 module(과 그 test source set) 안에서만 영향 |
| `DOWNSTREAM` | 소유 module에 의존하는 module까지 영향 |
| `RUNTIME` | recompile로는 드러나지 않고 실행 시점에만 드러남(reflection, framework, stale inlined constant) |
| `UNKNOWN` | 판단 불가. 해당 layer에 대해 conservative fallback 적용 |

여러 delta를 합칠 때는 `UNKNOWN > DOWNSTREAM > RUNTIME > LOCAL > NONE` 순서로 최대값을 취한다.

### 2.5 Delta kind

JDELTA_DESIGN.md §8의 목록을 유지하고 다음을 **[추가]** 제안한다. 모두 classfile에서 deterministic하게 감지할 수 있고, 기존 kind로 뭉개면 report가 부정확해지는 경우다.

```kotlin
CLASS_ANNOTATION_CHANGED,       // @Component, @Entity 등 class-level. 기존 목록에 없음
METHOD_EXCEPTIONS_CHANGED,      // throws 절. compile ABI만 영향
METHOD_PARAMETERS_CHANGED,      // MethodParameters attribute(이름). reflection ABI만 영향
ENUM_CONSTANT_ORDER_CHANGED,    // ordinal 변경. JPA ORDINAL, EnumSet 직렬화
RECORD_COMPONENTS_CHANGED,
PERMITTED_SUBCLASSES_CHANGED,   // sealed. exhaustive switch가 downstream에서 깨짐
SOURCE_ONLY_CHANGED,            // source는 바뀌었는데 classfile 의미는 같음(주석, 포맷, line 이동)
DEPENDENCY_CHANGED,             // 외부 dependency 좌표 변경
BUILD_CONFIGURATION_CHANGED,    // build script, toolchain, compiler option 변경
```

### 2.6 SemanticDelta와 Reason

```kotlin
public data class SemanticDelta(
    val subject: NodeId,
    val kind: DeltaKind,
    val compileImpact: ImpactLevel,
    val binaryImpact: ImpactLevel,
    val reflectionImpact: ImpactLevel,
    val frameworkImpact: ImpactLevel,
    val testImpact: ImpactLevel,
    val confidence: Confidence,
    val reasons: List<Reason>,
)

public data class Reason(
    val code: ReasonCode,              // JSON에 나타나는 안정적 식별자
    val message: String,               // 사람용 설명
    val evidence: List<NodeId> = emptyList(),   // 근거가 된 node 경로
)
```

`ReasonCode`는 enum이고 report JSON의 key가 된다(`BODY_ONLY`, `DESCRIPTOR_CHANGED`, `CONSTANT_INLINED_AT_CALLERS`, `NO_TRACE_FOR_TEST`, `TRACE_STALE`, `FRAMEWORK_PROFILE_MATCH`, `PROCESSOR_SENSITIVE_ANNOTATION`, `UNPARSEABLE_CLASSFILE`, `ONE_TIME_INITIALIZATION` 등). "모른다"는 문자열이 아니라 code로 남아야 metric으로 셀 수 있다.

### 2.7 Work plan

```kotlin
public data class WorkPlan(val items: List<WorkItem>, val fallbacks: List<Fallback>)

public sealed interface WorkItem {
    val confidence: Confidence
    val reasons: List<Reason>

    public data class RecompileModule(val module: ModuleId, val scope: Scope, ...) : WorkItem   // Scope: LOCAL | DOWNSTREAM
    public data class RunTestsFirst(val tests: List<RankedTest>, ...) : WorkItem
    public data class RunFullSuite(val modules: Set<ModuleId>?, ...) : WorkItem               // null = 전체
    public data class ReviewManually(val subject: NodeId, ...) : WorkItem                     // 자동 판단 불가 영역
    // 확장: RerunAnnotationProcessor, InvalidateGeneratedSource, ReloadBeanSubgraph, RefreshContext, Restart
}

public data class Fallback(val trigger: NodeId, val code: ReasonCode, val action: WorkItem)
```

v0.1에서 `RecompileModule`은 **권고(report)일 뿐**이다. 실제 recompile 여부는 Gradle incremental compilation이 정한다. jdelta는 Gradle의 결정을 바꾸지 않고, Gradle보다 더 정확한 설명을 제공한다.

### 2.8 Graph API

```kotlin
public interface SemanticGraph {
    public fun node(id: NodeId): Node?
    public fun outgoing(id: NodeId, kinds: Set<EdgeKind> = ALL): Sequence<Edge>
    public fun incoming(id: NodeId, kinds: Set<EdgeKind> = ALL): Sequence<Edge>
    public fun nodes(kind: NodeKind): Sequence<Node>
}

public class GraphBuilder { fun add(node: Node); fun add(edge: Edge); fun build(): SemanticGraph }
```

구현은 `IndexedGraph`: node map + edge kind별 forward/backward adjacency(`Map<NodeId, List<Edge>>`). 빌드 후 불변.

### 2.9 Impact solver contract

```kotlin
public interface ImpactSolver {
    public fun solve(input: ImpactInput): ImpactResult
}

public data class ImpactInput(
    val graph: SemanticGraph,
    val deltas: List<SemanticDelta>,
    val project: ProjectModel,
    val policy: ImpactPolicy,           // mode(IMPACTED_FIRST | REPORT_ONLY), traversal 깊이 제한 등
)

public data class ImpactResult(
    val impacted: List<ImpactedNode>,   // node + confidence + reason path
    val plan: WorkPlan,
)
```

`ProjectModel`(module, source set, module dependency, 언어, annotation processor 목록)도 core에 둔다. Gradle 타입이 아니라 순수 data다(§4.1).

---

## 3. Classfile snapshot (`jdelta-classfile`)

### 3.1 Snapshot 모델

```kotlin
data class ClassSnapshot(
    val id: ClassId,
    val access: Int,
    val superName: String?,
    val interfaces: List<String>,
    val signature: String?,
    val sourceFile: String?,
    val classfileVersion: Int,
    val annotations: List<AnnotationValue>,     // visible / invisible 구분 포함
    val fields: List<FieldSnapshot>,
    val methods: List<MethodSnapshot>,
    val recordComponents: List<RecordComponentSnapshot>,
    val permittedSubclasses: List<String>,
    val innerClasses: List<InnerClassEntry>,
    val enclosingMethod: EnclosingMethod?,
    val kotlin: KotlinClassInfo?,               // §3.6
    val fingerprints: ClassFingerprints,        // compileAbi, packageAbi, binaryAbi, reflectionAbi
    val references: ClassReferences,            // §5.2 graph edge 재료
)

data class MethodSnapshot(
    val name: String, val descriptor: String, val access: Int,
    val signature: String?, val exceptions: List<String>,
    val annotations: List<AnnotationValue>, val parameterAnnotations: List<List<AnnotationValue>>,
    val parameterNames: List<String>?,          // MethodParameters attribute(-parameters)
    val annotationDefault: AnnotationValue?,
    val bodyHash: String?,                      // abstract/native는 null
    val folded: FoldInfo?,                      // §3.4 synthetic folding
)
```

snapshot은 class별로 독립적이고 파일 hash(SHA-256 of classfile bytes)를 key로 `.jdelta/cache/classes/`에 캐시한다. classfile byte가 같으면 parse를 건너뛴다.

ASM 읽기 옵션: `ClassReader.SKIP_FRAMES`만 쓴다. debug 정보는 읽되 fingerprint에서는 제외한다. Kotlin inline caller 탐지(§3.6)에 `LocalVariableTable`과 `SourceDebugExtension`(SMAP)이 필요하기 때문이다.

### 3.2 ABI layer별 포함 항목

각 layer의 fingerprint는 해당 항목들을 **정렬된 canonical byte stream**으로 직렬화해 hash한다. member는 `name + descriptor`로 정렬한다. 선언 순서 자체가 의미 있는 항목(enum constant 순서, record component 순서, interface 순서)은 순서를 보존한다.

| 항목 | compile (public) | compile (package) | binary | reflection |
|------|:---:|:---:|:---:|:---:|
| class access flag (`ACC_SUPER` 제외) | ● | ● | ● | ● |
| superclass / interface | ● | ● | ● | ● |
| generic `Signature` (class, member) | ● | ● | | ● |
| public/protected member name + descriptor + access | ● | | ● | ● |
| package-private member | | ● | ● | ● |
| private member | | | | ● |
| `throws` (`Exceptions`) | ● | ● | | |
| `ConstantValue` | ● | ● | | |
| `AnnotationDefault` (annotation type) | ● | ● | | ● |
| RuntimeVisible annotation (class/member/parameter/type) | ● | ● | | ● |
| RuntimeInvisible(CLASS retention) annotation | ● | ● | | |
| `MethodParameters` (parameter name) | | | | ● |
| `Record` component | ● | ● | | ● |
| `PermittedSubclasses` | ● | ● | | ● |
| `InnerClasses` entry(이 class 자신에 대한 것) | ● | ● | ● | |
| enum constant 선언 순서 | | | | ● |
| Kotlin Metadata(§3.6에서 정한 subset) | ● | ● | | ● |

주의할 점:

- **compile ABI를 public/package로 나눈다.** package-private 변경은 같은 package, 즉 같은 module 안에서만 compile 영향이 있으므로 `LOCAL`이다. split package는 module model에서 감지해서 그때만 `DOWNSTREAM`으로 올린다.
- **non-public class의 public member.** package-private class `Base`의 public method는 public subclass를 통해 downstream에서 보인다. 같은 module 안에 `Base`를 상속하는 public class가 있으면 `Base`의 public/protected member는 public compile ABI로 취급한다.
- **private member는 binary ABI에서 뺀다.** Java 11+ nestmate private access는 같은 compilation unit 안에서만 생기고, 그 unit은 항상 함께 recompile된다.
- **source retention annotation은 classfile에 없다.** 따라서 jdelta는 이를 볼 수 없다. Lombok 같은 source annotation의 효과는 생성된 bytecode로만 드러난다. 이 한계는 report에 명시한다(§6.4의 processor-sensitive fallback).
- `module-info.class` 변경은 v0.1에서 `UNKNOWN`, `package-info.class`는 일반 class처럼 annotation을 비교한다(Spring `@NonNullApi`, JPA package-level annotation 때문).

### 3.3 Method body fingerprint 정규화

ASM tree API(`MethodNode`)로 읽어서 instruction을 다음 규칙으로 canonical stream에 쓴다.

- `LabelNode`, `LineNumberNode`, `FrameNode`는 건너뛴다.
- jump/switch target label은 **target instruction의 index**로 기록한다(label identity는 버린다).
- constant pool 참조는 ASM이 이미 symbol로 풀어주므로 symbol 값(`owner.name:desc`, ldc 값 + type tag)을 그대로 쓴다. constant pool 순서 차이는 영향이 없다.
- try-catch block은 `(start index, end index, handler index, exception type)`.
- `maxStack`, `maxLocals`, local variable table, parameter name은 제외한다.
- `invokedynamic`은 name, descriptor, bootstrap method handle, bootstrap argument를 기록한다. 단 §3.4의 folding 대상 handle은 이름 대신 **대상 method의 body fingerprint**로 치환한다.

결과: 주석 추가, 공백 변경, line 이동, 다른 method 순서 변경은 body hash를 바꾸지 않는다. git diff로는 source가 바뀌었는데 모든 class의 모든 layer hash가 같으면 `SOURCE_ONLY_CHANGED`(impact 없음, `EXACT`)를 낸다. 이 경우를 데모에서 보여줄 가치가 있다.

**toolchain 변경 감지.** 같은 source라도 javac/kotlinc 버전이 다르면 bytecode가 달라진다. snapshot에 toolchain fingerprint(classfile major version 분포, Kotlin metadata version, project model의 toolchain/compiler 버전)를 기록하고, baseline과 다르면 body diff 전체에 `TOOLCHAIN_CHANGED` reason과 함께 `BUILD_CONFIGURATION_CHANGED`를 낸다. 이 경우 body-only 판단을 신뢰하지 않는다.

### 3.4 Synthetic member folding

javac와 kotlinc가 만드는 synthetic member는 이름이 불안정하다. 그대로 비교하면 lambda 하나를 추가했을 때 뒤쪽 lambda 이름이 전부 밀려서 거짓 delta가 쏟아진다. 다음 규칙으로 synthetic member를 "소유 method"에 접는다.

| synthetic | 식별 | 처리 |
|-----------|------|------|
| Java lambda `lambda$foo$0` | `ACC_SYNTHETIC` + `LambdaMetafactory` bootstrap의 impl handle | `foo` body fingerprint에 lambda body hash를 포함. lambda method는 member diff에서 제외 |
| Kotlin indy lambda (2.0 기본) | 위와 동일 | 위와 동일 |
| anonymous/local class `Outer$1`, Kotlin `Foo$bar$1` | `EnclosingMethod` attribute | enclosing method 단위로 그룹화. 그룹 안에서는 첫 등장 순서로 매칭. 그룹 구성이 바뀌면 enclosing method `METHOD_BODY_CHANGED` |
| bridge method | `ACC_BRIDGE` | binary ABI에는 포함(link 대상), compile ABI와 body diff에서는 제외. 추가/삭제는 binary 영향만 가진 `METHOD_ADDED`/`METHOD_REMOVED`(`BRIDGE_CHANGED`)로 낸다. package-private class의 public method를 public subclass가 노출할 때 javac가 만드는 visibility bridge가 대표적이다 |
| Kotlin `foo$default` | name suffix + Kotlin metadata의 default argument flag | `foo`에 접는다. 단 descriptor 변경은 binary ABI로 감지 |
| `access$000`, Kotlin `access$getX$p` | `ACC_SYNTHETIC` + `access$` prefix | 대상 member에 접는다 |
| `$values()`(enum), `$deserializeLambda$` | 이름 | body diff 제외 |

folding 결과는 `MethodSnapshot.folded`에 남겨서 trace 매핑(agent가 `lambda$foo$0` 실행을 기록하면 `foo`로 귀속)에 재사용한다.

### 3.5 Unsupported 입력

- ASM이 모르는 classfile version → 해당 class는 `UNKNOWN` delta, `UNPARSEABLE_CLASSFILE`.
- Kotlin metadata version을 `kotlin-metadata-jvm`이 읽지 못함 → Kotlin 정보 없이 Java 규칙만 적용하고 `KOTLIN_METADATA_CHANGED` 판단을 `CONSERVATIVE_UNKNOWN`으로.
- 이런 경우 분석을 중단하지 않는다. 판단을 약하게 표시하고 계속한다.

### 3.6 Kotlin v0.1 범위

`kotlin.Metadata` annotation을 `kotlin-metadata-jvm`(`readLenient`)으로 읽어서 `KotlinClassInfo`를 만든다.

```kotlin
data class KotlinClassInfo(
    val kind: KotlinClassKind,                  // CLASS, FILE_FACADE, MULTI_FILE_FACADE, MULTI_FILE_PART, SYNTHETIC
    val metadataVersion: String,
    val visibility: KotlinVisibility?,          // class 자신(INTERNAL 포함)
    val header: String,                         // kind, modifier, type parameter, supertype, companion, sealed, enum entry
    val declarations: Map<String, KotlinDeclaration>,
)

data class KotlinDeclaration(
    val key: String,                            // "fun <jvm name+desc>", "property <receiver.>name", "constructor ...", "typealias ..."
    val visibility: KotlinVisibility,
    val isInline: Boolean,
    val jvmMethods: List<String>,               // 이 선언이 만든 JVM method
    val jvmField: String?,                      // backing field
    val canonical: String,                      // "public final fun note(): kotlin/String?" 처럼 읽을 수 있는 Kotlin 선언
)
```

`canonical`에는 JVM descriptor로 드러나지 않는 Kotlin 수준 정보(nullability, parameter 이름, 기본값 유무, `operator`/`infix`/`suspend` 등 modifier, type parameter의 variance/reified, property의 `var`/`const`/`lateinit`/accessor visibility)를 담는다. metadata version, module name, string table 순서, type alias 약어 표기는 넣지 않는다. fingerprint는 원본 metadata hash 대신 이 선언 view의 hash를 쓴다. 따라서 compiler version만 올라가서 metadata byte가 바뀌는 경우는 delta가 아니다.

규칙:

1. **`internal`은 JVM에서 public이지만 compile ABI는 module-local이다.** internal member/class 변경은 compile impact `LOCAL`(같은 module + friend인 test source set). 구현에서는 binary impact도 `LOCAL`로 둔다. Java caller가 mangled name(`foo$app`)으로 다른 module에서 부르는 경우는 지원하지 않는 사용으로 본다. companion object의 `const val`/`@JvmField`는 field가 바깥 class에 생기므로 companion metadata에서 visibility를 찾는다.
2. **public/protected/internal inline function의 body hash가 바뀌면** `METHOD_BODY_CHANGED`가 아니라 `KOTLIN_INLINE_BODY_CHANGED`로 승격한다. compile impact는 public이면 `DOWNSTREAM`, internal이면 `LOCAL`. binary impact는 constant(§6.3)와 같은 이유로 public이면 `RUNTIME`이다(이미 compile된 caller는 옛 body 사본을 실행한다). `EXACT`. inline function 안의 anonymous object가 바뀐 경우(§3.4 그룹 비교)도 같은 kind로 승격한다. inline property accessor도 포함한다.
3. **inline call site 탐지.** inline 된 code는 caller bytecode에 invoke 명령을 남기지 않는다. 따라서 trace에도, static call graph에도 나타나지 않는다. kotlinc가 남기는 두 흔적을 이용한다.
   - local variable `$i$f$<functionName>` marker(LocalVariableTable)
   - SMAP(`SourceDebugExtension`)의 inline function source file 매핑

   두 정보로 `inlineFun INLINED_INTO callerMethod` edge를 `INFERRED`로 만든다. 탐지 실패 시 owner module에 의존하는 Kotlin module의 test 전체를 `CONSERVATIVE_UNKNOWN`으로 포함한다.
   classfile 단계는 `MethodReferences.inlinedFunctions`에 `owner.name`(owner를 모르면 `?.name`)을 기록한다. marker에는 descriptor가 없으므로 impact solver가 같은 이름의 inline method 전체에 연결한다. inline function 자신의 body에 있는 자기 이름 marker는 제외한다.
4. **`const val`**은 `ConstantValue` attribute로 나타나므로 Java constant 규칙(§6.3)을 그대로 탄다.
5. 양쪽에 있는 선언의 `canonical`이 다르거나 class `header`가 다르면 `KOTLIN_METADATA_CHANGED`. subject는 선언의 JVM method(없으면 field, 그것도 없으면 class)이다. 선언 추가/삭제는 그 JVM member 추가/삭제를 Java 규칙이 이미 보고하므로, JVM member가 그대로인 경우(type alias, getter를 유지한 `fun getX()` → `val x` 전환 등)만 보고한다. compile impact는 **downstream 중 Kotlin을 쓰는 module에만** `DOWNSTREAM`(Java는 metadata를 읽지 않는다)이고 reason `KOTLIN_CALLERS_ONLY`를 남긴다. 무엇이 바뀌었는지 알기 때문에 confidence는 `INFERRED`다. metadata를 읽지 못한 경우에만 `CONSERVATIVE_UNKNOWN` + `KOTLIN_METADATA_NOT_ANALYZED`.
6. **compiler 생성 class와 synthetic method.** `$WhenMappings` 같은 synthetic class는 local/anonymous class처럼 enclosing class 그룹으로 비교한다(§3.4). `foo$default` 같은 synthetic method는 source compile에서 보이지 않으므로 추가/삭제의 compile impact는 `NONE`, binary impact는 범위대로다(`SYNTHETIC_MEMBER`). 기본값 식이 바뀌면 `foo$default`의 body가 바뀐다.

suspend lowering, value class mangling, data class `componentN`/`copy` 의미 분석은 `jdelta-kotlin`(v0.2+)로 미룬다. v0.1에서는 이들이 JVM descriptor 변경으로 드러나는 만큼만 Java 규칙으로 잡힌다.

---

## 4. Project model과 snapshot 수집

### 4.1 ProjectModel

```kotlin
data class ProjectModel(
    val rootDir: Path,
    val modules: List<ModuleModel>,
    val toolchain: ToolchainInfo,
)

data class ModuleModel(
    val id: ModuleId,
    val projectDir: Path,
    val languages: Set<Language>,                 // JAVA, KOTLIN
    val sourceSets: List<SourceSetModel>,         // main, test, testFixtures, custom
    val dependencies: List<ModuleDependency>,     // target module + kind(API, IMPLEMENTATION, COMPILE_ONLY, RUNTIME_ONLY, TEST)
    val annotationProcessors: List<String>,       // annotationProcessor / kapt / ksp configuration의 좌표
    val externalDependencies: List<String>?,      // 선택적(--with-dependencies), resolved 좌표
)

data class SourceSetModel(
    val id: SourceSetId,
    val sourceDirs: List<Path>,
    val classesDirs: List<Path>,                  // java, kotlin output이 분리됨
    val resourcesDir: Path?,
    val isTest: Boolean,
)
```

**api/implementation 구분이 중요하다.** `:web → implementation(:service) → api(:core)`일 때 `:core`의 compile ABI 변경은 `:service`와 (api로 노출되었으므로) `:web`의 compile classpath에 닿는다. `:service`가 `:core`를 `implementation`으로 쓰면 `:web`의 compile에는 닿지 않는다. 반면 binary/test 영향은 runtime classpath 기준이므로 transitive 전체다.

### 4.2 Gradle model export

`jdelta-gradle-plugin`의 `jdeltaExportModel` task가 위 model을 `.jdelta/model/project.json`으로 쓴다. 사용자가 plugin을 적용하지 않았으면 CLI가 init script를 주입한다.

```bash
./gradlew -q --init-script <jdelta-dist>/init/jdelta.init.gradle.kts jdeltaExportModel
```

init script는 CLI 배포본 안의 plugin jar를 `initscript { dependencies { classpath(files(...)) } }`로 걸고 모든 project에 plugin을 적용한다. task는 configuration cache 호환으로 작성한다(provider만 사용, execution time에 `Project` 접근 금지).

구현(12단계):

- 모든 project에 `jdeltaModuleModel`(project 하나의 JSON을 `build/jdelta/module.json`에 씀), root에 `jdeltaExportModel`(모아서 `.jdelta/model/project.json`). module JSON은 task가 실현될 때(configuration 단계) 계산해 `@Input` 문자열로 넘긴다.
- dependency는 **resolve하지 않고 선언만 읽는다.** source set별 `api`/`compileOnlyApi`→API, `implementation`→IMPLEMENTATION, `compileOnly`→COMPILE_ONLY, `runtimeOnly`→RUNTIME_ONLY, test source set의 선언은 TEST. annotation processor는 `annotationProcessor`/`kapt`/`ksp` 선언 좌표.
- test source set은 `Test` task의 `testClassesDirs`에 들어가는 source set이다(이름에 `test`가 들어가도 test로 본다).
- Kotlin source directory는 source set의 `kotlin` extension에서 읽는다.
- `ProjectDependency.getPath()`(Gradle 8.11+)를 쓰고, 없으면 reflection으로 `getDependencyProject()`를 쓴다.
- CLI는 `.jdelta/init/jdelta.init.gradle`을 생성해 `gradlew -q --init-script ... [classes testClasses] jdeltaExportModel`을 실행한다. `--build`와 `--build-baseline`은 build와 export를 한 번의 Gradle 호출로 묶는다. export된 model이 모든 build 설정 파일(`settings`/`build` script, `gradle.properties`, `libs.versions.toml`)보다 새로우면 다시 export하지 않는다.
- `--model auto|gradle|layout`. auto는 Gradle wrapper와 plugin jar가 있으면 export, 없으면 directory 관례(§4.3)를 쓴다. report note에 어느 쪽을 썼는지 남긴다.
- 배포본은 plugin jar를 `plugin/jdelta-gradle-plugin.jar`에 둔다(CLI classpath에는 넣지 않는다).

### 4.3 Snapshot 수집

```text
ProjectSnapshot
  commit: <sha> | WORKTREE(<dirty-hash>)
  toolchain: ToolchainInfo
  modules[]:
    sourceSets[]:
      classes: Map<internalName, ClassSnapshot>
      resources: Map<path, sha256>
```

수집은 source set의 `classesDirs`를 순회하며 `.class`를 읽는다. module/source set 단위로 병렬 처리한다. 결과는 `.jdelta/snapshots/<sha>/`에 저장한다(working tree가 dirty면 저장하지 않는다). 저장 형식은 §12.

**구현 상태(11단계).** Gradle model export(12단계) 전까지 CLI는 Gradle 관례 directory 구조에서 model을 추정한다(`GradleLayout`). build script가 있는 directory가 module이고(`settings.gradle*`가 있는 하위 directory는 included build로 보고 건너뛴다), `build/classes/<language>/<sourceSet>`, `build/resources/<sourceSet>`, `src/<sourceSet>/<language>`에서 source set을 찾는다. module 사이 dependency는 이 방식으로 알 수 없다.

### 4.4 Baseline 해석

`jdelta diff main..HEAD`의 해석:

1. base는 기본적으로 **`git merge-base main HEAD`**다. 사용자가 보는 PR diff와 같다. `--exact-base`를 주면 `main` 그대로 쓴다. report에 실제 사용한 SHA를 항상 출력한다.
2. head가 `HEAD`(또는 생략)면 current snapshot은 **현재 build output**에서 만든다. working tree 변경도 포함된다.
3. base snapshot을 찾는 순서:
   1. `--baseline-dir <path>`(CI artifact 등에서 받은 snapshot). 명시적으로 준 것이 우선한다. snapshot의 SHA가 base와 다르면 report note로 알린다.
   2. `.jdelta/snapshots/<base-sha>/`
   3. `--build-baseline`: `git worktree add --detach` 임시 디렉터리 → build(`--build-command`, 기본 `./gradlew -q classes testClasses`) → snapshot 저장 → worktree 제거
   4. 모두 없으면 exit code 3과 함께 위 세 가지 방법을 안내한다.

head가 working tree이고 clean하며 staleness 검사를 통과했으면 head snapshot도 저장해 둔다. 다음 PR의 baseline이 될 수 있기 때문이다.

변경 파일 목록은 `git diff --name-status --no-renames <base-sha>`(working tree까지)와 `git ls-files --others --exclude-standard`(untracked)를 합친다. `.jdelta/`는 제외한다.

권장 CI 흐름: main build마다 `jdelta snapshot`을 실행해서 snapshot을 artifact/cache로 보관하고, PR build는 그것을 `--baseline-dir`로 받는다.

### 4.5 Staleness 검사

current snapshot이 실제 source와 맞는지 확인한다. `git diff --name-only base` + working tree 변경 목록에서 `.java`/`.kt` 파일이 있는데, 해당 source에 대응하는 classfile(`SourceFile` attribute + package 경로로 역매핑)의 mtime이 source보다 오래되었으면 **stale**이다. 기본은 에러로 중단하고 `--build`(분석 전에 `classes testClasses` 실행) 사용을 안내한다. 낡은 output으로 "변경 없음"이라고 말하는 것이 가장 나쁜 실패이기 때문이다.

같은 매핑으로 "git에서는 바뀌었지만 classfile은 같은" source를 찾아 `SOURCE_ONLY_CHANGED`를 만든다. build script(`*.gradle.kts`, `gradle.properties`, `libs.versions.toml`, `buildSrc/`, `build-logic/`) 변경은 `BUILD_CONFIGURATION_CHANGED`로 보고한다.

---

## 5. Delta classification

### 5.1 Matching

- class: `ClassId`(module + internal name)로 매칭한다. 같은 internal name이 다른 module로 옮겨졌으면 `CLASS_REMOVED` + `CLASS_ADDED`로 내되 report에서 "moved"로 묶어 보여준다.
- member: `name + descriptor`로 매칭한다.
- **descriptor 변경 감지.** 같은 이름의 member가 base에서 정확히 하나 사라지고 head에서 정확히 하나 생겼으면 `METHOD_DESCRIPTOR_CHANGED`(또는 `FIELD_DESCRIPTOR_CHANGED`)로 묶는다. 그 외(overload가 여러 개 바뀜)는 `REMOVED`/`ADDED`로 둔다. 둘 다 compile/binary impact는 같으므로 안전성에는 차이가 없고 설명만 달라진다.
- fingerprint 단축 경로: class의 네 ABI hash와 모든 body hash가 같으면 member 비교를 건너뛴다.

### 5.2 Graph 재료 추출

snapshot을 읽을 때 class별 reference를 함께 모은다.

- `CALLS`: `MethodInsnNode`, `invokedynamic`의 impl handle(folding 후 실제 소유 method로)
- `READS_FIELD` / `WRITES_FIELD`: `GETFIELD/GETSTATIC` / `PUTFIELD/PUTSTATIC`
- `REFERENCES`: type 명령(`NEW`, `CHECKCAST`, `INSTANCEOF`, `ANEWARRAY`), descriptor/signature에 등장하는 type, annotation type, ldc `Type`
- `EXTENDS` / `IMPLEMENTS` / `OVERRIDES`: hierarchy는 project 안의 class만으로 계산한다. 외부 library supertype은 이름만 기록한다.

project 밖(외부 library) symbol은 node로 만들지 않는다. 대신 `DEPENDENCY_CHANGED`가 있을 때만 conservative하게 다룬다.

### 5.3 분류 순서

class 하나에 대해:

```text
1. 추가/삭제                      -> CLASS_ADDED / CLASS_REMOVED
2. access, super, interface       -> CLASS_ACCESS_CHANGED / CLASS_HIERARCHY_CHANGED
3. class annotation               -> CLASS_ANNOTATION_CHANGED
4. signature, permitted, record   -> GENERIC_SIGNATURE_CHANGED / PERMITTED_SUBCLASSES_CHANGED / RECORD_COMPONENTS_CHANGED
5. enum constant 순서              -> ENUM_CONSTANT_ORDER_CHANGED
6. field 별                        -> ADDED/REMOVED/DESCRIPTOR/ACCESS/ANNOTATION, CONSTANT_VALUE_CHANGED
7. method 별                       -> ADDED/REMOVED/DESCRIPTOR/ACCESS/ANNOTATION/EXCEPTIONS/PARAMETERS
8. method body                    -> METHOD_BODY_CHANGED (또는 KOTLIN_INLINE_BODY_CHANGED로 승격)
9. Kotlin metadata 나머지          -> KOTLIN_METADATA_CHANGED
10. 어떤 hash가 다른데 위에서 설명되지 않음 -> UNKNOWN (classifier 버그 신호, 테스트에서 0건이어야 함)
```

한 member에서 여러 kind가 동시에 나올 수 있다(예: annotation과 body가 함께 변경). 각각 별도의 delta로 내보내고 report에서 subject 단위로 묶는다.

10번은 중요한 안전장치다. layer hash가 다르다는 사실은 EXACT하게 알지만 분류기가 이유를 설명하지 못하는 경우, 조용히 무시하지 않고 `UNKNOWN`으로 올린다.

---

## 6. Impact solver (`jdelta-impact`)

### 6.1 Graph 구성

`jdelta-engine`이 다음을 `GraphBuilder`에 넣는다.

| 출처 | node/edge | confidence |
|------|-----------|-----------|
| ProjectModel | MODULE, SOURCE_SET, `DEPENDS_ON_MODULE`, `COMPILED_IN_MODULE` | EXACT |
| head snapshot(+ base snapshot의 삭제된 member) | CLASS/METHOD/FIELD, `DECLARES`, `CALLS`, `REFERENCES`, hierarchy | EXACT |
| Kotlin inline 탐지 | `INLINED_INTO` | INFERRED |
| trace store | TEST_CLASS/TEST_METHOD, `EXECUTED_BY_TEST` | OBSERVED (stale이면 INFERRED) |

삭제된 member는 head에 없지만 trace와 caller는 그것을 가리킨다. 따라서 base snapshot의 삭제된 node와 그 incoming edge도 graph에 넣는다.

### 6.2 Delta kind별 규칙 표

아래는 public scope 기준이다. package-private/internal이면 compile의 `DOWNSTREAM`이 `LOCAL`로 내려간다. private이면 compile/binary는 `NONE`이다.

| DeltaKind | compile | binary | reflection | framework | test 전파 경로 | conf |
|-----------|---------|--------|-----------|-----------|----------------|------|
| METHOD_BODY_CHANGED | NONE | NONE | NONE | profile | method →`EXECUTED_BY_TEST` | EXACT / OBSERVED |
| METHOD_DESCRIPTOR_CHANGED, METHOD_REMOVED | DOWNSTREAM | DOWNSTREAM | LOCAL | profile | 옛 method의 trace + `CALLS`역방향 1단계 caller의 trace | EXACT |
| METHOD_ADDED | DOWNSTREAM¹ | NONE² | LOCAL | profile | hierarchy에서 override되는 method의 trace, 같은 class를 실행한 test | INFERRED |
| METHOD_ACCESS_CHANGED | DOWNSTREAM³ | DOWNSTREAM³ | LOCAL | profile | caller trace | EXACT |
| METHOD_ANNOTATION_CHANGED | LOCAL⁴ | NONE | LOCAL | profile | method trace + profile 규칙 | INFERRED |
| METHOD_EXCEPTIONS_CHANGED | DOWNSTREAM | NONE | NONE | NONE | 없음(compile만) | EXACT |
| METHOD_PARAMETERS_CHANGED | NONE | NONE | LOCAL | profile | method trace | INFERRED |
| FIELD_* (descriptor/removed/access) | DOWNSTREAM | DOWNSTREAM | LOCAL | profile | `READS/WRITES_FIELD` 역방향 method의 trace | EXACT |
| FIELD_ANNOTATION_CHANGED | LOCAL | NONE | LOCAL | profile | owner class를 실행한 test + profile 규칙 | INFERRED |
| CONSTANT_VALUE_CHANGED | DOWNSTREAM | RUNTIME⁵ | NONE | NONE | §6.3 | EXACT / CONSERVATIVE |
| GENERIC_SIGNATURE_CHANGED | DOWNSTREAM | NONE | LOCAL | profile | class를 실행한 test(INFERRED) | EXACT |
| CLASS_HIERARCHY_CHANGED, CLASS_REMOVED | DOWNSTREAM | DOWNSTREAM | LOCAL | profile | class와 subclass를 실행한 test | EXACT |
| CLASS_ANNOTATION_CHANGED | LOCAL⁴ | NONE | LOCAL | profile | class를 실행한 test + profile 규칙 | INFERRED |
| ENUM_CONSTANT_ORDER_CHANGED | NONE | NONE | RUNTIME | profile | enum을 참조하는 method의 trace | INFERRED |
| PERMITTED_SUBCLASSES_CHANGED | DOWNSTREAM | NONE | LOCAL | NONE | hierarchy test | EXACT |
| RESOURCE_CHANGED | NONE | NONE | RUNTIME | profile | 소유 module + runtime downstream module의 test 전체 | CONSERVATIVE |
| KOTLIN_INLINE_BODY_CHANGED | DOWNSTREAM(Kotlin) | RUNTIME⁵ | NONE | NONE | `INLINED_INTO` caller의 trace, 실패 시 module fallback | INFERRED / CONSERVATIVE |
| KOTLIN_METADATA_CHANGED | DOWNSTREAM(Kotlin) | NONE | LOCAL | NONE | class를 실행한 test | INFERRED (metadata를 못 읽으면 CONSERVATIVE) |
| SOURCE_ONLY_CHANGED | NONE | NONE | NONE | NONE | 없음 | EXACT |
| DEPENDENCY_CHANGED, BUILD_CONFIGURATION_CHANGED, UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | 영향 module 전체 → 사실상 full suite | CONSERVATIVE |

1. 새 overload가 downstream overload resolution을 바꾸거나, downstream subclass의 기존 method와 충돌할 수 있다.
2. interface에 abstract method 추가는 예외로 `DOWNSTREAM`(구현체에서 `AbstractMethodError`).
3. 접근 범위 축소, static↔instance 변경, `final` 추가일 때. 범위 확대는 `NONE`.
4. CLASS-retention annotation이나 Kotlin nullability annotation은 downstream compile(경고, Kotlin 타입 추론)에 영향을 줄 수 있다. profile에 등록된 경우에만 `DOWNSTREAM`.
5. compile된 caller 안에 옛 값이 inline되어 남는다. recompile 전까지 runtime에서 옛 값이 쓰인다.

"profile"은 §6.4의 framework profile이 결정한다는 뜻이다.

### 6.3 Compile-time constant

constant는 caller classfile에 field 참조를 남기지 않는다(javac가 값을 inline한다). 따라서 graph로 caller를 찾을 수 없다.

- compile impact: 소유 module(`LOCAL`) + compile classpath로 닿는 downstream 전체(`DOWNSTREAM`), `EXACT`.
- test 후보 좁히기(`INFERRED`): downstream class 중 옛 값과 같은 `ldc` 상수를 가진 class를 찾아, 그 class를 실행한 test를 앞에 둔다. 작은 정수는 `iconst`/`bipush`로 들어가므로 이 방법은 완전하지 않다.
- 나머지(`CONSERVATIVE_UNKNOWN`): 해당 module들의 test 전체를 그 뒤에 둔다.
- final instance field에 constant initializer가 있는 경우(`final int x = 3`)도 javac는 constant variable로 inline한다. 같은 규칙을 적용한다.

### 6.4 Framework profile

framework 지식은 annotation descriptor를 key로 하는 데이터 표다. library에 의존하지 않는다.

```kotlin
data class FrameworkProfile(
    val framework: String,                     // "spring", "jackson", "jpa", "mapstruct", "lombok"
    val annotations: Set<String>,              // "Lorg/springframework/transaction/annotation/Transactional;"
    val sensitiveTo: Set<DeltaKind>,
    val effect: ProfileEffect,                 // reflection/framework impact level과 test 확장 방식
    val processorSensitive: Boolean,           // annotation processor 입력이 되는가
)
```

v0.1 내장 profile:

| framework | 대표 annotation | 효과 |
|-----------|----------------|------|
| Spring core | `@Component` 계열, `@Configuration`, `@Bean`, `@Conditional*`, `@Profile` | framework `RUNTIME`. 같은 module의 Spring context test(`@SpringBootTest`, `@WebMvcTest` 등)를 conservative 포함 |
| Spring web | `@RequestMapping` 계열, `@RequestBody`, `@PathVariable` | 해당 controller를 실행한 test + web slice test |
| Spring tx/AOP | `@Transactional`, `@Async`, `@Cacheable` | proxy 동작이 바뀐다. method trace + 같은 bean을 실행한 test |
| Spring config | `@ConfigurationProperties`, `@Value` | 해당 class를 실행한 test + `RESOURCE_CHANGED`(`application*.yml/properties`)와 연결 |
| Jackson | `@JsonProperty`, `@JsonCreator`, `@JsonIgnore`, `@JsonFormat` | field/method 변경 → class를 실행한 test, 이름에 `Serializ`/`Json`이 들어간 test를 INFERRED로 앞 순위 |
| JPA | `@Entity`, `@Id`, `@Column`, relationship, `@Enumerated` | field 변경 → entity를 실행한 test + `@DataJpaTest`. `ENUM_CONSTANT_ORDER_CHANGED`와 `@Enumerated(ORDINAL)` 조합은 강조 |
| MapStruct, Lombok | `@Mapper`, `@Mapping`, (Lombok은 생성 결과만 보임) | `processorSensitive`. 생성 class까지 conservative 포함 |

test class가 Spring test인지는 test class의 annotation(meta-annotation 포함)으로 판단한다. meta-annotation은 project 안에 정의된 것만 따라간다.

구현(16단계):

- profile 표는 `jdelta-impact`의 `FrameworkProfiles.BUILT_IN`(spring-core, spring-web, spring-tx-aop, spring-config, jackson, jpa, mapstruct, lombok)이다. profile = annotation descriptor 집합 + 반응하는 delta kind(비면 전부) + test 확장 방식(`CLASS_TRACE`, `CONTEXT_TESTS`, `WEB_SLICE_TESTS`, `JPA_SLICE_TESTS`, `SERIALIZATION_NAMED_TESTS`, `GENERATED_CODE_TESTS`).
- engine의 `FrameworkFacts`가 snapshot(base ∪ head)에서 class/method/field annotation을 모으고 **project 안에 정의된 meta-annotation을 펼친다.** 같은 사실로 processor 입력(processor-sensitive class의 method descriptor/signature가 참조하는 project class), `@Enumerated` ORDINAL field, 생성된 class(processor가 있는 module에서 source directory에 대응하는 source 파일이 없는 class)를 찾는다.
- `FrameworkEnricher`가 분류된 delta에 profile 판단을 더한다: framework impact를 RUNTIME으로 올리고 `FRAMEWORK_PROFILE_MATCH`(어느 profile, 어느 annotation) 또는 `PROCESSOR_SENSITIVE_ANNOTATION`(어느 processor class, 어느 module)을 남긴다. enum 순서 변경 + ORDINAL 저장 field는 "stored rows change meaning"으로 강조한다.
- solver는 graph node의 `annotations`/`generated` attribute로 같은 표를 다시 적용해 test를 넓힌다. context/slice test는 바뀐 module의 runtime dependent 안에서 test class annotation(meta 포함)으로 찾는다.
- Lombok annotation은 SOURCE retention이라 classfile에 없다. 생성된 member의 변화로만 보인다.
- 검증: `FrameworkProfileTest`(실제 package 이름의 stub annotation으로 Spring bean/endpoint, Jackson, JPA ordinal, MapStruct 입력 변경), 데모 scenario 5·6(`samples/acme-shop`의 runtime annotation과 실제 MapStruct mapper).

**processor-sensitive fallback.** `annotationProcessors`가 있는 module에서 processor-sensitive annotation이 붙은 class가 바뀌면, `ap-trace`가 없는 한 그 module의 generated source 전체가 바뀌었을 수 있다고 본다. generated source dir(`build/generated/sources/annotationProcessor`, kapt/ksp 경로)에서 나온 class를 실행한 test를 `CONSERVATIVE_UNKNOWN`으로 포함하고 `PROCESSOR_SENSITIVE_ANNOTATION` reason을 남긴다.

### 6.5 Traversal 알고리즘

```text
input: deltas, graph, rules
queue <- 각 delta에 대해 (delta.subject, delta.confidence, path=[delta])
visited <- map[(node, ruleStep)] -> best confidence

while queue not empty:
  (node, conf, path) <- pop
  for step in rules[delta.kind].stepsFrom(node.kind):
    for edge in graph.edges(node, step.edgeKinds, step.direction):
      next <- edge.other(node)
      c <- min(conf, edge.confidence, step.confidenceCap)
      if visited[(next, step)] >= c: continue        // 더 강한 path로 이미 방문
      visited[(next, step)] <- c
      if next is TEST_METHOD: record(next, c, path + edge)
      else if path.length < step.maxDepth: push (next, c, path + edge)
```

- rule step은 "어떤 edge kind를 어느 방향으로 몇 단계까지"를 선언한다. 예: `METHOD_DESCRIPTOR_CHANGED`는 `CALLS` 역방향 1단계 → `EXECUTED_BY_TEST` 정방향.
- 깊이 제한이 있는 이유: static call graph를 무한히 거슬러 올라가면 거의 모든 test가 영향 대상이 되어 정보가 사라진다. 실행 영향은 trace(`EXECUTED_BY_TEST`)가 이미 전이적으로 담고 있다. test가 caller를 실행했다면 callee도 실행했을 것이기 때문이다. static edge는 **trace가 없는 새 symbol을 기존 trace에 연결하는 다리**로만 쓴다.
- 모든 결과는 reason path를 보존한다. report의 "왜 이 test가 포함되었나"는 이 path를 그대로 출력한 것이다.

구현(14단계, `RuleBasedImpactSolver`):

- rule은 hop의 목록이다. hop = (edge kind 집합, 방향, 최대 깊이, 출발 node 포함 여부, confidence 상한). 예: `METHOD_DESCRIPTOR_CHANGED`는 `[EXECUTED_BY_TEST →]`와 `[CALLS ← 1단계, EXECUTED_BY_TEST →]` 두 경로, class 수준 변경은 `[DECLARES →, EXECUTED_BY_TEST →]`, hierarchy 변경은 `[EXTENDS/IMPLEMENTS ← 최대 5단계(자신 포함), DECLARES →, EXECUTED_BY_TEST →]`.
- seed는 delta subject와 reason의 evidence(descriptor 변경 전의 옛 MethodId 등)다. 그래서 옛 method의 trace도 따라간다.
- **one-time 초기화 edge는 바뀐 member 자신에서 출발한 첫 hop에서만 따른다.** class의 다른 member(`<clinit>`)를 거쳐 가면 class 수준 변경 하나가 fork 전체 test class로 번진다(engine test에서 발견).
- module fallback(constant, resource, UNKNOWN 등)은 test method만 포함한다. class setup node는 그 method들이 이미 덮는다.
- §6.3의 "옛 값과 같은 `ldc`를 가진 class" 좁히기는 아직 하지 않는다. constant 변경은 owner class trace(INFERRED) + compile이 닿는 module 전체(CONSERVATIVE)다. caller가 다시 compile되어 body가 바뀌면 그 body 변경이 OBSERVED test를 앞에 세운다(데모 scenario 4).
- inline function은 `INLINED_INTO` caller의 trace를 INFERRED로 따른다. edge가 하나도 없고 compile impact가 DOWNSTREAM이면 Kotlin을 쓰는 compile dependent module 전체로 물러선다(`KOTLIN_INLINE_CALLERS_UNKNOWN`).

graph 구성(`GraphFactory`, engine):

- code edge(`DECLARES`, `CALLS`, `READS/WRITES_FIELD`, `REFERENCES`, `EXTENDS`, `IMPLEMENTS`, `OVERRIDES`)는 **base와 head snapshot을 합쳐서** 만든다. 삭제·변경된 member의 옛 caller가 base에만 있기 때문이다. `invokevirtual`의 owner가 subclass여도 상위 type을 따라 실제 선언 class로 해석한다.
- `EXECUTED_BY_TEST`는 trace store에서 온다(OBSERVED). 관측 이후 test class 자신이 바뀌었거나 관측이 contaminated면 INFERRED(`TRACE_STALE`). head의 test source set에 class가 없는 test(삭제된 test)는 버린다.
- fork의 one-time 집합 method → 그 fork의 test class 전체로 INFERRED edge(attribute `reason=ONE_TIME_INITIALIZATION`).
- test 목록은 trace와 별도로 head snapshot에서 정적으로 찾는다(`TestDiscovery`: Jupiter `@Test`/`@ParameterizedTest`/`@RepeatedTest`/`@TestFactory`/`@TestTemplate`, JUnit 4 `@Test`, 상속한 test method 포함). trace 없는 test를 빠뜨리지 않기 위해서다. parameter type 표기는 JUnit `MethodSource`와 같은 `Class.getName()` 형식이다.

### 6.6 Test 선택과 정렬

impacted set:

1. traversal로 도달한 test
2. **trace가 없는 test는 항상 포함**(`NO_TRACE_FOR_TEST`, 새로 추가된 test 포함)
3. 변경된 test class 자신(test method body 변경은 그 test, class-level 변경은 class 전체), `EXACT`
4. fallback이 지정한 module 단위 test 집합

정렬 key(앞이 우선):

1. 변경된 test 자신
2. confidence(`EXACT` > `OBSERVED` > `INFERRED` > `CONSERVATIVE_UNKNOWN`)
3. reason path 길이(짧을수록 직접 영향)
4. 최근 실패 이력이 있는 test
5. 과거 실행 시간이 짧은 test(빠른 실패 신호)

`CONSERVATIVE_UNKNOWN`으로만 포함된 test가 전체의 대부분이면, impacted-first는 효용이 없다. report에서 이 비율을 보여주고(`conservative share`), 일정 비율(기본 70%)을 넘으면 "impacted-first 효과 낮음, 원인: …"을 명시한다.

### 6.7 Work plan 생성

```text
compile DOWNSTREAM/LOCAL 집계 -> RecompileModule(module, scope) [권고]
impacted tests                  -> RunTestsFirst(ranked)
mode == IMPACTED_FIRST          -> RunFullSuite(전체)          // v0.1 기본. 항상 포함
UNKNOWN / profile 미지원 영역     -> Fallback(trigger, code, action) + ReviewManually(선택)
```

v0.1에서는 `RunFullSuite`를 빼는 경로가 없다. opt-in skip mode는 Stage 5(§19 of JDELTA_DESIGN.md)까지 구현하지 않는다. `ImpactPolicy`에 skip 관련 필드도 만들지 않는다.

---

## 7. Engine (`jdelta-engine`)

CLI와 Gradle plugin이 공유하는 진입점이다.

```kotlin
class JDeltaEngine(private val workspace: Workspace) {          // Workspace = .jdelta/ 디렉터리 접근
    fun snapshot(model: ProjectModel, revision: Revision): ProjectSnapshot
    fun diff(base: ProjectSnapshot, head: ProjectSnapshot, model: ProjectModel): DiffResult       // deltas
    fun impact(diff: DiffResult, model: ProjectModel, policy: ImpactPolicy): ImpactResult         // traces 로딩 포함
    fun report(diff: DiffResult, impact: ImpactResult?, format: ReportFormat): String
}
```

engine은 git도 Gradle도 모른다. `Revision`과 `ProjectModel`은 호출자(CLI, Gradle plugin)가 만들어서 넘긴다.

---

## 8. Test trace

### 8.1 구성 요소

```text
test JVM
 ┌───────────────────────────────────────────────────────────┐
 │ jdelta-agent.jar (-javaagent)                              │
 │   premain: runtime을 bootstrap classloader에 추가            │
 │   ClassFileTransformer: project output dir class에만 probe  │
 │                                                            │
 │ jdelta.runtime.Recorder (bootstrap)                        │
 │   hit(methodIndex), beginScope(test), endScope() -> 저장    │
 │                                                            │
 │ jdelta-junit (TestExecutionListener, ServiceLoader 등록)    │
 │   executionStarted/Finished -> Recorder.begin/endScope     │
 └───────────────────────────────────────────────────────────┘
          │ 종료 시 raw trace 파일 쓰기
          ▼
 .jdelta/traces/raw/<runId>/<forkId>.json  -> jdelta-trace가 trace store로 병합
```

runtime class를 bootstrap classloader에 두는 이유: instrument된 application class(app classloader)와 JUnit listener(test classloader일 수 있음)가 **같은 class 인스턴스**를 봐야 하기 때문이다. agent jar 안의 ASM은 `jdelta.shaded.asm`으로 relocate해서 사용자의 ASM, ByteBuddy, Spring의 repackaged ASM과 충돌하지 않게 한다.

구현(13단계):

- **bootstrap에는 `jdelta.runtime`만 올린다.** agent jar 전체를 bootstrap search에 더하면 아직 load되지 않은 `jdelta.agent` class가 bootstrap에서 load되어, 같은 package가 두 classloader로 갈라지고 package-private 접근이 `IllegalAccessError`로 깨진다(실제로 겪었다). 그래서 runtime class만 담은 `jdelta-runtime.jar`를 agent jar 안에 중첩하고, premain이 임시 파일로 꺼내 `appendToBootstrapClassLoaderSearch`한다.
- relocation은 shadow plugin 대신 build-logic의 `RelocatedJar` task(asm-commons `ClassRemapper`)로 한다. 재현 가능한 jar를 위해 entry 시각을 고정한다.
- `Recorder.hit`은 고정 크기(4096) `boolean[]` chunk의 배열이다. 늘릴 때 기존 chunk를 복사하지 않으므로 다른 thread의 기록을 잃지 않는다. 처음 실행된 method만 lock을 잡고 `touched` 목록에 넣는다. scope 전환 시 `touched`만 훑어 귀속하고 지우므로 전환 비용이 전체 method 수가 아니라 실행된 method 수에 비례한다.
- 실행 중인 test가 둘 이상이면(병렬) 그 구간의 hit을 모두에 귀속하고 `contaminated=true`.
- listener는 `Class.forName("jdelta.runtime.Recorder", false, null)`(bootstrap)로 agent 유무를 확인하고, 없으면 아무것도 하지 않는다. Recorder 호출은 별도 class(`RecorderBridge`)에 모아 agent가 없을 때 load되지 않게 한다.
- raw trace는 test plan 종료 시 쓰고, 실패하면 shutdown hook이 다시 시도한다. 임시 파일에 쓴 뒤 move한다.
- 검증(`TraceRecordingTest`): javac로 만든 작은 project와 JUnit suite를 agent를 붙인 별도 JVM에서 실행하고 `jdelta-trace`의 `RawTraceReader`로 읽는다. test별 scope, `@BeforeAll`/첫 static 초기화의 CLASS_SETUP 귀속, parameterized invocation 병합, FAILED status, `<clinit>` one-time 집합, lambda body 기록, agent 없는 listener 무동작을 확인한다.

### 8.2 Probe

- agent argument로 `config` 파일 경로를 받는다. 파일에는 output dir → module ID 표, 출력 경로, run ID가 있다.
- transformer는 `ProtectionDomain.getCodeSource().getLocation()`이 project output dir에 속한 class만 instrument한다. 외부 library, JDK, Gradle worker class는 건드리지 않는다(과수집 방지, 안정성).
- 각 method(constructor, `<clinit>` 포함) 시작에 `Recorder.hit(int)`를 삽입한다. index는 transform 시점에 전역 method table에 등록하며 table에는 `(module, internalName, name, descriptor)`가 들어간다. synthetic lambda는 table에 원래 이름으로 넣고, 소유 method로의 folding은 분석 시(`jdelta-trace`)에 한다.
- `hit`의 구현은 `boolean[]`(필요시 확장) 기록뿐이다. 이미 true면 아무것도 하지 않는다. lock 없음. race로 같은 값을 두 번 쓰는 것은 무해하다.
- 비활성 경로: agent arg에 `enabled=false`면 transformer를 등록하지 않는다.

### 8.3 Scope

JUnit listener가 다음 경계를 Recorder에 알린다.

| scope | 시작 | 끝 | 기록 대상 |
|-------|------|----|-----------|
| `TEST` | test method `executionStarted` | `executionFinished` | 그 test method |
| `CLASS_SETUP` | test class container 시작 | 첫 test method 시작 / container 종료 | 그 test class (`@BeforeAll`, Spring context 로딩, instance post-processing) |
| `AMBIENT` | 그 외 모든 구간 | | fork 전체 |

scope가 끝날 때 bitset을 snapshot해서 해당 scope에 귀속하고 지운다.

**한 번만 실행되는 code 문제.** `<clinit>`, singleton 초기화, Spring의 cached ApplicationContext는 fork 안에서 처음 필요로 한 test 하나에서만 실행된다. 이후 test는 그 결과에 의존하지만 trace에는 나타나지 않는다. 처리:

- `AMBIENT`나 `CLASS_SETUP`에서만 관측된 method, 그리고 모든 `<clinit>`은 **one-time initialization 집합**으로 따로 표시한다.
- 이 집합의 method가 바뀌면 같은 fork에서 실행된 모든 test class를 `INFERRED`(reason `ONE_TIME_INITIALIZATION`)로 포함한다.
- Spring context가 캐시되는 test에서 context 초기화 code는 처음 로딩한 test class의 `CLASS_SETUP`에 기록된다. 같은 context를 공유하는 test class를 정확히 알려면 `jdelta-spring`이 context cache key를 기록해야 한다. v0.1은 같은 fork 전체로 근사한다.

### 8.4 Parallel 실행

JUnit Jupiter 병렬 실행(`junit.jupiter.execution.parallel.enabled=true`)에서는 "현재 test"가 여러 개다. thread-local로 귀속하면 test가 만든 다른 thread(executor, coroutine)에서의 실행을 놓친다. 그래서 v0.1은:

- `record` 모드에서 Jupiter 병렬 실행을 끈다(`junit.jupiter.execution.parallel.enabled=false` system property 주입). Gradle의 `maxParallelForks`로 인한 fork 단위 병렬은 fork마다 Recorder가 따로 있으므로 문제없다.
- 사용자가 강제로 병렬을 켜면 겹친 구간의 hit을 겹친 모든 test에 귀속하고 trace에 `contaminated=true`를 표시한다. 이런 trace에서 나온 edge는 `INFERRED`다.

### 8.5 Raw trace 형식 (agent ↔ jdelta-trace 계약)

method table을 intern하고 test마다 index 배열을 저장한다. 사람이 열어볼 수 있는 JSON을 유지한다.

```json
{
  "formatVersion": 1,
  "runId": "2026-10-08T10:15:30Z-7f3a",
  "forkId": "gradle-test-worker-3",
  "commit": "e15cd1a...",
  "classpathFingerprint": "9c1e...",
  "contaminated": false,
  "methods": [
    ":app|com/acme/PriceCalculator#calculate(Lcom/acme/Order;)J",
    ":app|com/acme/Money#<init>(JLjava/lang/String;)V"
  ],
  "scopes": [
    { "kind": "CLASS_SETUP", "test": "junit-jupiter:com.acme.PriceCalculatorTest", "hits": [1] },
    { "kind": "TEST", "test": "junit-jupiter:com.acme.PriceCalculatorTest#calculatesDiscount()",
      "hits": [0, 1], "durationMs": 12, "status": "SUCCESSFUL" }
  ],
  "ambient": [],
  "oneTimeInit": [1]
}
```

agent와 listener는 plain Java라서 이 JSON을 직접 문자열로 쓴다(escape는 class/method 이름에 필요한 최소 범위).

### 8.6 Trace store

`jdelta-trace`가 raw trace를 병합해 `.jdelta/traces/store.json`을 갱신한다.

- key: test ID. value: 최신 관측(`commit`, `recordedAt`, method 집합, duration, status, contaminated).
- 같은 test의 새 관측은 이전 것을 대체한다(합집합이 아니다). 삭제된 code를 계속 가리키면 recall이 아니라 노이즈가 늘어난다.
- **staleness**: 관측 이후 그 test class 자신의 body hash가 바뀌었으면 그 trace는 `TRACE_STALE`이고 edge confidence는 `INFERRED`로 내려간다. 다음 `record`에서 갱신된다.
- folding: `lambda$foo$0` 등은 head snapshot의 `folded` 정보로 소유 method에 귀속한다.
- fork 관측(`runId`, `forkId`, one-time 집합, test class 목록)도 함께 보관한다. 다시 관측된 test class는 옛 fork에서 빠지고, 빈 fork는 버린다.
- 저장 크기가 문제가 되면(수만 test × 수천 method) 그때 gzip 또는 SQLite로 옮긴다. 형식 변경은 `formatVersion` 증가 + 재수집으로 처리한다(migration 하지 않는다. trace는 다시 만들 수 있는 cache다).

### 8.7 Miss detection

impacted-first 실행에서:

```text
phase 1: impacted tests     -> 결과 P1
phase 2: full suite         -> 결과 P2
miss 후보 = P2에서 실패했지만 impacted set에 없던 test
pre-existing 제외 = trace store에서 직전 관측도 실패였던 test
```

miss는 `.jdelta/metrics/misses.jsonl`에 delta 목록, 해당 test, 당시 confidence 분포와 함께 기록한다. run 요약(`runs.jsonl`)에는 impacted 수, 전체 수, phase 1 소요 시간, 첫 실패까지 시간, miss 수를 남긴다. 이 데이터가 Stage 4의 confidence metric이고, Stage 5(opt-in skip)의 진입 조건이 된다.

---

## 9. Gradle 연동 (`jdelta-gradle-plugin`)

### 9.1 Task

| task | 동작 |
|------|------|
| `jdeltaExportModel` | ProjectModel을 `.jdelta/model/project.json`으로 export (root에서 집계) |
| `jdeltaSnapshot` | `classes`/`testClasses`에 의존. 현재 output으로 snapshot 저장 |
| `jdeltaDiff` | `-Pjdelta.base=<rev>`. report 생성 |
| `jdeltaImpactedTests` | impacted test 목록을 `build/jdelta/impacted-tests.json`에 기록 |
| `jdeltaImpactedFirstTest` | module별 `Test` task. 위 목록으로 filter |
| `jdeltaVerify` | phase 1/2 결과 집계, miss 기록, phase 1 실패 시 build 실패 |

`jdeltaRecord`는 별도 task가 아니라 **mode**다. `-Pjdelta.record=true`면(`jdelta record`가 `-Pjdelta.agentJar/junitJar/runId/traceOutput/commit`과 함께 넘긴다) 모든 `Test` task에:

- `-javaagent:<jdelta-agent.jar>=config=<file>` jvm arg
- `jdelta-junit` jar를 test runtime classpath에 추가
- Jupiter 병렬 실행 비활성화 system property
- **record run ID를 task input으로 추가.** 그렇지 않으면 Gradle이 test를 `UP-TO-DATE`/`FROM-CACHE`로 건너뛰어 trace가 남지 않는다.

구현 메모:

- listener jar는 Test task의 classpath를 직접 바꾸지 않고 **main이 아닌 source set의 `runtimeOnly`에 file dependency로** 더한다. configuration 단계 초기에 `setClasspath(getClasspath().plus(...))`를 하면 jvm-test-suite의 lazy classpath convention을 덮어써서 JUnit Platform launcher가 빠진다(실제로 "Failed to load JUnit Platform"으로 실패했다).
- agent 설정 파일(output dir/jar → module 표)은 configuration 단계에 문자열로 만들고, Test task의 `doFirst`(Project를 잡지 않는 `Serializable` action)가 쓴다. 다른 module의 class는 test runtime classpath에 보통 jar로 들어오므로 각 project의 `jar` archive도 표에 넣는다.
- bootstrap search에 jar를 더하면 JVM이 "Sharing is only supported for boot loader classes" 경고를 fork마다 한 번 출력한다(CDS 제한). 기능에는 영향이 없다.

### 9.2 Impacted-first 흐름

`./gradlew test -Pjdelta.impactedFirst=true`:

```text
jdeltaImpactedTests (root)
   └─> :m:jdeltaImpactedFirstTest   (module별, impacted가 없으면 onlyIf로 skip, ignoreFailures=true)
          └─> :m:test               (mustRunAfter, 전체 suite)
                 └─> jdeltaVerify   (finalizedBy, phase 1 실패면 여기서 build 실패)
```

- filter는 configuration time에 알 수 없으므로 `doFirst`에서 `filter.includeTestsMatching("com.acme.FooTest.method")`로 넣는다. 목록은 file provider로 읽어서 configuration cache를 깨지 않는다.
- phase 1이 실패해도 phase 2는 계속 실행한다(JDELTA_DESIGN.md §3.2). `-Pjdelta.failFast=true`면 phase 1 실패 시 즉시 멈춘다.
- phase 2는 v0.1에서 impacted test도 다시 실행한다. 단순하고, 순서 의존 test에 안전하다. `-Pjdelta.skipRerunInFullSuite=true`는 측정 데이터가 쌓인 뒤 검토한다.

구현(15단계):

- engine이 필요한 두 task(`jdeltaImpactedTests`, `jdeltaVerify`)는 **CLI를 `javaexec`로 별도 JVM에서 실행**한다(D13). CLI classpath는 `-Pjdelta.home=<배포본>`(`lib/*.jar`) 또는 `-Pjdelta.cliClasspath=<경로 목록>`. CLI는 `--model-file <.jdelta/model/project.json>`으로 이미 export된 model을 읽으므로 build 안에서 Gradle을 다시 띄우지 않는다.
- `jdeltaImpactedTests`는 `jdeltaExportModel`과 모든 project의 `classes`/`testClasses`에 의존하고 `jdelta impacted-tests [-Pjdelta.base] --format json`의 결과를 `build/jdelta/impacted-tests.json`에 쓴다. baseline이 없거나 output이 낡아 CLI가 실패하면 **경고만 하고 빈 목록**을 쓴다(phase 1 skip, 전체 suite는 그대로).
- module별 `jdeltaImpactedFirstTest`는 `test`의 classpath/testClassesDirs를 그대로 쓰고, `doFirst`에서 목록을 읽어 `includeTestsMatching("<class>.<method>")`을 건다(parameter type은 filter로 표현할 수 없어 method 이름까지). `failOnNoMatchingTests=false`, 목록이 비면 `onlyIf`로 skip, `ignoreFailures=true`(`-Pjdelta.failFast=true`면 false).
- `test`는 phase 1에 `dependsOn`하고 `jdeltaVerify`로 `finalizedBy`된다. `jdelta verify`는 두 phase의 JUnit XML을 읽어 `runs.jsonl`에 요약을, `misses.jsonl`에 "phase 2에서 실패했지만 impacted set에 없던 test"(직전 trace 관측도 실패였던 test 제외)를 남기고, phase 1이 실패했으면 non-zero로 끝나 build를 실패시킨다. XML의 testcase 이름은 display name이므로 method 이름 접두로 맞추고, parameterized invocation(`[1] ...`)은 class 단위로 맞춘다.
- 검증(`ImpactedFirstTest`, TestKit + configuration cache): record → `jdelta merge-traces` → `jdelta snapshot` → 통과하는 변경(phase 1 = 1개) → 깨지는 변경(phase 1 실패, phase 2의 다른 test까지 실행된 뒤 build 실패).
- `-Pjdelta.record=true`로 Gradle을 직접 실행했다면 `jdelta merge-traces <runId>`로 trace를 병합한다(`jdelta record`는 이것을 자동으로 한다).

### 9.3 호환성

- 최소 Gradle 버전: 8.x 중 configuration cache가 stable인 버전 이상으로 잡는다(정확한 하한은 skeleton 단계에서 TestKit matrix로 확정).
- `Project`를 execution time에 참조하지 않는다. 모든 입력은 `Property`/`Provider`/`ConfigurableFileCollection`.
- JaCoCo 등 다른 agent와 함께 쓸 수 있어야 한다. 서로 다른 transformer라 순서와 무관하게 동작해야 하며, e2e test에 JaCoCo 동시 적용 case를 넣는다.

---

## 10. CLI (`jdelta-cli`)

```text
jdelta snapshot [--build] [--out <dir>]
jdelta diff [<base>..<head>] [--format markdown|json] [--out <file>] [--exact-base]
            [--baseline-dir <dir>] [--build-baseline] [--build] [--build-command <cmd>]
            [--skip-staleness-check] [--project-dir <dir>] [--model auto|gradle|layout]
jdelta diff --base-classes <spec>... --head-classes <spec>...      # git 없이 directory 비교
jdelta record -- <command...>
jdelta merge-traces <runId>                                 # -Pjdelta.record=true로 직접 실행한 run 병합
jdelta verify --impacted <json> --phase1 <dir> --phase2 <dir>   # jdeltaVerify가 호출
jdelta impacted-tests [<base>..<head>] [--format text|json|gradle-filter]
jdelta report [<base>..<head>] [--format markdown|json]     # diff + impact 전체
```

- `<base>..<head>` 생략 시 `origin/HEAD`, `main`, `master` 중 처음 존재하는 것의 merge-base..working tree. head는 working tree(`HEAD`) 또는 snapshot이 저장된 commit만 될 수 있다.
- source 변경 중 class delta로 드러나지 않는 것은 `SOURCE_ONLY_CHANGED`(subject는 `//<path>` 형식의 `FileId`), build script·version catalog 변경은 `BUILD_CONFIGURATION_CHANGED`/`DEPENDENCY_CHANGED`(`CONSERVATIVE_UNKNOWN`)로 report에 넣는다.
- `record -- ./gradlew test`: command가 `gradlew`/`gradle`이면 init script(`--init-script`)와 `-Pjdelta.record=true`를 주입한다. 끝나면 `.jdelta/traces/raw/<runId>/`를 trace store에 병합하고 raw 파일을 지운다. test가 실패해도 실행된 test의 trace는 병합하고 command의 exit code를 돌려준다. 그 외 command는 `JAVA_TOOL_OPTIONS`에 agent를 넣는 generic mode(agent가 listener jar를 system classloader search에 추가)로 계획했으나 **v0.1 구현에서는 아직 거부한다.**
- `diff`는 trace store가 있으면 Test Impact와 work plan(recompile 범위, conservative share, fallback)을 report에 넣는다. `impacted-tests`는 trace가 없어도 계산한다(모든 test가 `NO_TRACE_FOR_TEST`).
- `impacted-tests --format gradle-filter`는 module별 `:m:test --tests '<class>.<method>' ...` 인자를 출력해 Gradle plugin 없이도 쓸 수 있게 한다(`| xargs ./gradlew`). `--tests`는 바로 앞 task에만 걸리므로 impacted test가 없는 module이 "No tests found"로 실패하지 않고, 공백이 든 test 이름(Kotlin backtick)은 따옴표로 지킨다.

exit code:

| code | 의미 |
|------|------|
| 0 | 성공 |
| 1 | 내부 오류 |
| 2 | 사용법 오류 |
| 3 | baseline snapshot 없음 |
| 4 | stale build output |

배포: Gradle `application` plugin distribution(zip/tar, `bin/jdelta`). CLI classpath(`lib/`)와 섞지 않도록 `agent/jdelta-agent.jar`, `agent/jdelta-junit.jar`, `plugin/jdelta-gradle-plugin.jar`를 따로 둔다. init script는 실행 시 `.jdelta/init/jdelta.init.gradle`로 생성한다(plugin jar의 절대 경로가 들어가야 하므로).

---

## 11. Report (`jdelta-report`)

### 11.1 Markdown 구조

```markdown
# jdelta report: main (a1b2c3d, merge-base) .. HEAD (working tree)

## Summary
3 semantic changes · 4 modules · impacted tests 17 / 1,240 (conservative share 12%)

## Semantic Deltas
### 1. `com.acme.PriceCalculator#calculate(Order)` — METHOD_BODY_CHANGED
| layer | impact |
| compile ABI | unchanged |
| binary ABI  | unchanged |
- Impacted tests: 4 (OBSERVED)
- Why: body hash changed; descriptor, access, annotations, signature unchanged

## ABI Impact
## Test Impact          (test별: confidence, reason path 1줄)
## Build Impact         (RecompileModule 권고와 api/implementation 근거)
## Conservative Fallbacks (trigger, reason code, 포함된 범위)
## Confidence Summary   (EXACT / OBSERVED / INFERRED / CONSERVATIVE_UNKNOWN 건수)
## Next Actions
```

규칙: 모든 결론 옆에 confidence를 쓴다. fallback은 summary에서 숨기지 않는다. 숫자가 큰 목록(수백 test)은 접어서(`<details>`) 보여준다.

### 11.2 JSON

```json
{
  "schemaVersion": "0.1",
  "stability": "unstable",
  "base": { "rev": "main", "sha": "a1b2c3d", "resolution": "merge-base" },
  "head": { "rev": "HEAD", "sha": "e15cd1a", "dirty": true },
  "deltas": [
    { "subject": ":app|com/acme/Timeouts.DEFAULT_MS:J", "kind": "CONSTANT_VALUE_CHANGED",
      "impact": { "compile": "DOWNSTREAM", "binary": "RUNTIME", "reflection": "NONE", "framework": "NONE", "test": "DOWNSTREAM" },
      "confidence": "EXACT",
      "reasons": [ { "code": "CONSTANT_INLINED_AT_CALLERS", "message": "...", "evidence": [] } ] }
  ],
  "impactedTests": [
    { "test": "junit-jupiter:com.acme.PriceCalculatorTest#calculatesDiscount()", "rank": 1,
      "confidence": "OBSERVED", "path": [":app|com/acme/PriceCalculator#calculate(Lcom/acme/Order;)J"] }
  ],
  "plan": { "items": [], "fallbacks": [] },
  "confidenceSummary": { "EXACT": 2, "OBSERVED": 1, "INFERRED": 0, "CONSERVATIVE_UNKNOWN": 1 }
}
```

HTML/SARIF는 이 JSON에서 파생한다(v0.2+).

---

## 12. Persistence (`.jdelta/`)

```text
.jdelta/
  model/project.json                 최신 ProjectModel
  snapshots/<sha>/
    manifest.json                    formatVersion, revision(sha, label, dirty), 생성 시각,
                                     source set 목록(id, test 여부, 하위 dir 번호, resource hash)
    <n>/**/*.class                   source set n의 classfile 원본(java/kotlin output을 합친 것)
  traces/
    raw/<runId>/<forkId>.json        agent 출력(처리 후 삭제 가능)
    store.json                       병합된 trace store
  metrics/
    runs.jsonl
    misses.jsonl
  reports/<timestamp>_<base>_<head>.{md,json}
  cache/classes/<sha256 앞 2자>/<sha256>.json   classfile parse cache
  lock                               쓰기 동안 file lock
```

- **snapshot은 해석 결과(JSON)가 아니라 classfile 원본을 보관한다.** 읽을 때 현재 reader로 다시 해석하므로 reader가 개선되면 이미 저장된 snapshot에도 바로 적용되고, `ClassSnapshot` 구조가 바뀌어도 migration이 필요 없다. 실제로 11단계 검증 중 reader의 false positive(`@SourceDebugExtension`)를 고친 뒤 저장된 baseline을 다시 build하지 않고 재사용했다. 대가는 디스크 사용량(classfile 크기만큼)과 매번 다시 parse하는 시간이며, parse 시간은 `cache/classes`로 줄인다. resource는 내용 없이 hash만 보관한다.
- `jdelta snapshot --out <dir>`은 같은 형식의 독립 directory를 쓴다. CI artifact로 보관했다가 `--baseline-dir`로 넘긴다.
- 모든 파일에 `formatVersion`. 버전이 맞지 않으면 **migration 하지 않고 버리고 다시 만든다.** `.jdelta/`의 모든 내용은 source + build로 재생성 가능한 cache다. 예외는 `metrics/`이며, 이것만 append-only로 보존한다.
- `.jdelta/`는 `.gitignore`에 넣는 것을 권장한다(CLI가 첫 실행 시 안내).
- JSON 직렬화는 Kotlin module에서 `kotlinx.serialization`을 쓴다. 동시 실행(병렬 CI job이 같은 디렉터리 공유)을 위해 쓰기 구간에 `FileChannel.lock`을 건다.

---

## 13. 테스트 전략

### 13.1 Classifier fixture

scenario는 test code 안에 before/after source 문자열로 둔다. test 실행 시 `javax.tools.JavaCompiler`로 임시 directory에 compile하고 diff한다. 기대값은 별도 `expected.json` 대신 assertion으로 쓴다. scenario와 기대값이 한 화면에 있어야 리뷰하기 쉽기 때문이다. (구현: `jdelta-classfile/src/test/kotlin/jdelta/classfile/JavaFixture.kt`) Kotlin fixture도 같은 방식으로 `kotlin-compiler-embeddable`(test dependency)의 `K2JVMCompiler`를 test JVM 안에서 실행한다(`KotlinFixture.kt`). 한 번 compile에 0.2초 안팎이라 별도 Gradle project가 필요 없었다.

필수 scenario(JDELTA_DESIGN.md §21 + 이 문서의 결정):

- private method body / public method body / 주석만 변경(`SOURCE_ONLY_CHANGED`) / line 이동
- public descriptor 변경, overload 추가
- constant 변경(static, instance constant variable)
- annotation 값 변경(`@Transactional(readOnly)`, `@JsonProperty`)
- lambda 추가로 기존 lambda 번호가 밀리는 경우 → **거짓 delta 0건**
- anonymous class 추가
- enum constant 순서 변경
- sealed permits 변경, record component 변경
- package-private class의 public method를 public subclass가 노출
- Kotlin: inline body, internal member, `const val`, default argument 추가, top-level function
- **결정성**: 같은 source를 두 번 compile → 모든 hash 동일. `-g`와 `-g:none` compile → body hash 동일

`UNKNOWN`(§5.3의 10번)은 모든 fixture에서 0건이어야 한다.

### 13.2 그 밖의 test

- `jdelta-impact`: 손으로 만든 작은 graph로 규칙 표의 각 행을 test. confidence lattice(min/max) property test.
- `jdelta-report`: golden file(Markdown, JSON). 출력 변경은 golden 갱신을 명시적으로 리뷰하게 한다.
- `jdelta-agent`/`jdelta-junit`: 별도 JVM을 띄워 작은 test suite를 실행하고 raw trace를 검증(scope 귀속, `<clinit>` one-time 처리, 병렬 비활성).
- `jdelta-gradle-plugin`: Gradle TestKit. configuration cache on/off, JaCoCo 동시 적용.
- e2e: `samples/acme-shop`(Gradle multi-module, Java + Kotlin, Spring Boot, JUnit 5, MapStruct). 각 데모 scenario를 git patch로 두고, 임시 repo에서 `record → patch 적용 → report`를 실행해 기대 report와 비교한다. 이 sample이 곧 JDELTA_DESIGN.md §21의 첫 데모다.
  - **구현(14·16단계)**: `samples/acme-shop`은 `core`(Java, MapStruct mapper) ← api ← `pricing`(Kotlin, public inline function) ← implementation ← `app`(Kotlin), test 9개다. Spring은 build 시간 때문에 넣지 않았고(Spring profile은 stub annotation으로 engine test에서 검증), scenario 5의 framework metadata는 project 안의 runtime annotation(`@Column`)과 reflection mapper로 흉내 낸다. `./gradlew :jdelta-cli:e2eTest`(`check`에 포함하지 않음, MapStruct를 Maven Central에서 받는다)가 sample을 임시 git repo로 복사해 `snapshot --build` → `record -- ./gradlew test` → scenario 1~6 변경 → `impacted-tests --build`/`diff`를 실제 Gradle로 실행하고 기대값을 검증한다.

---

## 14. 구현 순서와 완료 조건

JDELTA_DESIGN.md §23의 순서를 유지하되 각 단계의 완료 조건을 정한다.

진행 상태: 1~16 완료 ✅ (2026-10-08). 14·16단계 데모 scenario 1~6은 `./gradlew :jdelta-cli:e2eTest`(`DemoScenariosE2ETest`)로, 15단계는 TestKit `ImpactedFirstTest`로 검증한다. 11단계는 jdelta 자신의 repository 사본(10 module)에서 `jdelta diff main..HEAD --build-baseline`으로, 12단계는 TestKit(sample 3 module, configuration cache 재사용 포함)과 같은 사본에서 `--model gradle --build`로 확인했다. 8단계는 `jdelta diff --base-classes/--head-classes`로 directory 비교를 제공한다.

| # | 작업 | 완료 조건 |
|---|------|-----------|
| 1 | Gradle multi-module skeleton, `build-logic`, version catalog, CI(build + test) | `./gradlew build` 통과, 빈 module 10개 |
| 2 | core: `NodeId`(canonical round-trip), `Confidence`, `ImpactLevel`, `DeltaKind` | canonical string parse/format round-trip test |
| 3 | core: `SemanticDelta`, `Reason`, `WorkPlan`, `SemanticGraph`/`IndexedGraph`, `ProjectModel` | 외부 dependency 0 확인(dependency 검사 task) |
| 4 | classfile: `ClassSnapshotReader`(ASM) + parse cache | JDK class 수천 개 parse 오류 0 |
| 5 | classfile: compile(public/package)/binary/reflection fingerprint 분리 | 각 layer만 바뀌는 fixture에서 해당 hash만 변경 |
| 6 | classfile: body fingerprint + synthetic folding | 결정성 fixture, lambda 번호 이동 fixture 통과 |
| 7 | classfile: directory 기반 diff + 분류기 | §13.1 Java fixture 전부, `UNKNOWN` 0건 |
| 8 | engine + cli: `jdelta diff --baseline-dir`(git 없이 directory 비교부터) | 두 output dir 비교로 report 출력 |
| 9 | report: Markdown + JSON | golden test |
| 10 | Kotlin metadata v0.1(§3.6) | Kotlin fixture 통과 |
| 11 | git 연동, snapshot 저장, merge-base, staleness, `--build-baseline` | 실제 repo에서 `jdelta diff main..HEAD` |
| 12 | Gradle model export(init script) | sample project에서 api/implementation 구분 export |
| 13 | agent + junit listener + raw trace | §13.2 agent test |
| 14 | trace store + impact solver + `impacted-tests` | sample에서 데모 scenario 1~5의 impacted test 기대값 일치 |
| 15 | Gradle impacted-first + verify + miss 기록 | TestKit e2e, phase 1 실패 시에도 phase 2 실행 |
| 16 | framework profile(Spring/Jackson/JPA/MapStruct) | 데모 scenario 5, 6의 report |

1~9가 JDELTA_DESIGN.md의 Month 0-1, 10이 Month 1-2, 13이 Month 2-3, 14가 Month 3-4, 15가 Month 4-5에 대응한다. 16은 Month 5-6의 Spring graph prototype 전에 해두면 report-only Spring mode의 기반이 된다.

---

## 15. 위험과 대응

| 위험 | 영향 | 대응 |
|------|------|------|
| compiler 비결정성, toolchain 변경 | 거짓 body delta 폭증 | toolchain fingerprint 비교 후 `BUILD_CONFIGURATION_CHANGED`로 정직하게 보고. 결정성 test |
| stale build output | "변경 없음"이라는 거짓 결론 | staleness 검사 기본 활성, exit code 4 |
| one-time initialization, cached context | trace miss | `ONE_TIME_INITIALIZATION` fork 단위 포함 + full suite 유지 + miss 측정 |
| Kotlin metadata 신버전 | Kotlin 판단 불가 | Java 규칙 + `CONSERVATIVE_UNKNOWN`. `kotlin-metadata-jvm` 버전 추적 |
| agent overhead | record가 너무 느려 채택 안 됨 | 첫 hit만 기록하는 probe, project class만 instrument. sample에서 overhead 측정을 CI에 기록 |
| 다른 agent/bytecode 도구와 충돌 | test 실패 | ASM shading, JaCoCo 동시 e2e, `enabled=false` 탈출구 |
| conservative fallback 남발 | impacted-first 효용 상실 | conservative share를 report와 metric에 노출, 상위 원인(reason code) 집계 |
| Gradle API/configuration cache 변화 | plugin 고장 | TestKit Gradle version matrix |
| trace store 크기 | `.jdelta/` 비대 | interned table, 필요 시 gzip/SQLite. trace는 cache라 형식 변경 비용이 낮다 |

---

## 16. 남은 질문

JDELTA_DESIGN.md §24의 질문은 §0에서 결정했다. 구현 중 결정할 새 질문:

- reflection ABI에 field 선언 순서를 포함할 것인가? Jackson 기본 property 순서 등에 영향이 있지만 noise가 클 수 있다. fixture와 sample로 측정 후 결정한다.
- `@Nested` test의 Gradle filter 표기(`Outer$Inner.method`)가 모든 지원 Gradle 버전에서 동작하는지 TestKit으로 확인한다.
- CI에서 base snapshot을 전달하는 표준 방법(GitHub Actions cache/artifact 예제)을 v0.1 문서에 포함할 것인가?
- `testFixtures` source set을 별도 module처럼 취급할 것인가, 소유 module의 test 쪽으로 취급할 것인가?
- `DEPENDENCY_CHANGED` 때 외부 jar도 snapshot해서 library ABI diff를 할 것인가? 가능은 하지만(같은 classfile 엔진) v0.1에서는 conservative로 둔다.
