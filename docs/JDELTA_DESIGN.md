# jdelta 설계 문서

## 1. 결론

`jdelta`는 빌드를 빠르게 실행해주는 단순 도구가 아니다. Spring 분석기만도 아니다.

`jdelta`는 JVM Semantic Delta Engine이다. JVM 프로젝트의 소스, classfile, build module, generated source, test trace, framework runtime metadata를 하나의 semantic change graph로 연결하고, 다음 질문에 답하는 엔진이다.

> 이 변경은 의미적으로 무엇을 바꾸었고, 무엇에 영향을 줄 수 있으며, 안전하게 다시 해야 하는 최소 작업은 무엇인가?

장기 목표는 JVM 생태계에 부족한 semantic invalidation layer를 만드는 것이다. Gradle, Maven, JUnit, Spring, CI, IDE, migration tool은 모두 "무엇이 바뀌었고 무엇을 다시 해야 하는가?"라는 질문을 가진다. 현재 대부분의 도구는 file, task, module, test class, ApplicationContext 같은 coarse-grained 단위로 답한다. `jdelta`는 symbol, ABI, generated source, test method, runtime graph 단위로 답해야 한다.

## 2. 핵심 문제

JVM 개발자가 겪는 표면 문제는 다음과 같다.

- build가 느리다.
- test가 느리다.
- Spring Boot 재시작이 느리다.
- Gradle incremental build가 언제 깨지는지 이해하기 어렵다.
- Kotlin compile이 느리다.
- CI pipeline이 오래 걸린다.
- annotation processor가 opaque하다.

더 깊은 원인은 하나다.

> JVM tooling은 "이 변경이 실제로 무엇을 깨뜨릴 수 있는지"를 충분히 정밀하게 모른다.

모르면 안전을 위해 많은 일을 다시 한다.

```text
작은 method body 변경
  -> module compile
  -> downstream compile
  -> 전체 test
  -> Spring context restart
  -> CI job rerun
```

이 낭비를 줄이려면 프로젝트를 file 묶음이 아니라 semantic graph로 봐야 한다.

## 3. 프로젝트 철학

### 3.1 새 빌드 도구를 만들지 않는다

Gradle, Maven, Bazel, Pants를 대체하지 않는다. `jdelta`는 그 아래 또는 옆에서 사용할 수 있는 engine이어야 한다.

가능한 integration:

- Gradle plugin
- Maven plugin
- Bazel rule
- Pants plugin
- JUnit extension
- Spring runtime integration
- IDE plugin
- CI integration

### 3.2 속도보다 정확도를 우선한다

잘못된 test skipping은 치명적이다. 초기 기본값은 test skip이 아니라 impacted-first여야 한다.

```text
1. 영향받은 test를 먼저 실행한다.
2. 빠른 실패 신호를 준다.
3. 이후 전체 test suite를 계속 실행한다.
4. 전체 test 결과와 비교해서 miss 여부를 기록한다.
5. 충분한 신뢰가 쌓인 뒤에만 opt-in skip mode를 제공한다.
```

### 3.3 "모른다"를 표현한다

신뢰할 수 있는 도구는 아는 척하지 않는다.

`jdelta`는 모든 판단에 confidence를 붙여야 한다.

```kotlin
enum class Confidence {
    EXACT,
    OBSERVED,
    INFERRED,
    CONSERVATIVE_UNKNOWN
}
```

의미:

- `EXACT`: classfile 소유 관계, descriptor diff처럼 deterministic하게 안다.
- `OBSERVED`: test 실행 trace처럼 실제 실행에서 관측했다.
- `INFERRED`: framework rule이나 static analysis로 추론했다.
- `CONSERVATIVE_UNKNOWN`: 안전하게 배제할 수 없어서 포함했다.

### 3.4 JVM의 복잡성을 숨기지 않고 모델링한다

JVM 프로젝트는 Java classfile만으로 끝나지 않는다.

- Java
- Kotlin/JVM
- annotation processor
- generated source
- reflection
- Spring bean graph
- AOP proxy
- classloader
- JUnit runtime
- constant inlining
- generic signature
- Kotlin Metadata

`jdelta`의 차별점은 이 복잡성을 무시하지 않고 명시적인 모델로 끌어올리는 데 있다.

## 4. 명시적 비목표

초기 구현이 흐려지지 않도록 다음은 비목표로 둔다.

- Gradle/Maven 대체 빌드 도구를 만들지 않는다.
- 초기부터 test skip을 기본값으로 제공하지 않는다.
- Spring partial reload를 첫 목표로 삼지 않는다.
- ABI를 public Java method signature 목록으로 단순화하지 않는다.
- Kotlin/JVM을 "Java classfile과 동일"하게 취급하지 않는다.
- annotation processor를 처음부터 완전 sandbox로 만들려고 하지 않는다.
- unknown impact를 숨기지 않는다.

## 5. 전체 시스템 흐름

기본 흐름:

```text
baseline project snapshot
  + current project snapshot
  -> semantic delta classification
  -> semantic graph impact solving
  -> safe work plan
  -> explainable report
```

v0.1 흐름:

```text
classfile ABI diff
  + method body fingerprint
  + JUnit execution trace
  + Gradle module mapping
  -> impacted tests first
  -> full suite fallback
```

장기 흐름:

```text
annotation processor trace
  -> generated source invalidation

Spring runtime graph
  -> reload recommendation
  -> bean subgraph reload plan
```

## 6. 핵심 데이터 모델

`jdelta`의 본질은 graph다.

Node는 의미 단위다. Edge는 영향이 전파될 수 있는 관계다.

### 6.1 초기 Node

```text
ProjectNode
ModuleNode
SourceSetNode
SourceFileNode
ClassNode
MethodNode
ConstructorNode
FieldNode
AnnotationNode
ResourceNode
TestClassNode
TestMethodNode
GradleTaskNode
```

### 6.2 확장 Node

```text
GeneratedSourceNode
AnnotationProcessorNode
ProcessorOptionNode
SpringBeanNode
SpringEndpointNode
ConfigurationPropertyNode
DependencyNode
RuntimeTraceNode
```

### 6.3 초기 Edge

```text
BELONGS_TO
DEFINES
DECLARES
REFERENCES
CALLS
READS_FIELD
WRITES_FIELD
EXTENDS
IMPLEMENTS
OVERRIDES
ANNOTATED_BY
COMPILED_IN_MODULE
EXECUTED_BY_TEST
TASK_INPUT
TASK_OUTPUT
```

### 6.4 확장 Edge

```text
GENERATED_FROM
GENERATED_BY
CONSUMED_BY_PROCESSOR
READS_RESOURCE
WRITES_RESOURCE
CREATES_BEAN
INJECTS_BEAN
HANDLES_ENDPOINT
BINDS_CONFIGURATION
CONDITIONAL_ON
PROXIED_BY
```

### 6.5 Stable ID

graph와 trace를 revision 사이에서 맞추려면 stable ID가 중요하다.

초기 기준:

- class: module ID + JVM internal name
- method: owner internal name + method name + descriptor
- constructor: owner internal name + descriptor
- field: owner internal name + field name + descriptor
- test method: JUnit engine ID + class name + method name
- module: build system path
- source set: module ID + source set name

Kotlin은 추가 고려가 필요하다.

- top-level function
- extension function
- inline function
- default argument synthetic method
- suspend lowering
- companion object
- internal visibility

## 7. ABI는 하나가 아니다

`jdelta`의 가장 중요한 기술 결정은 ABI를 여러 층으로 나누는 것이다.

### 7.1 Compile ABI

다른 source를 compile할 때 필요한 표면이다.

포함:

- visible class
- visible method descriptor
- visible field descriptor
- generic signature
- annotation type과 compile에 의미 있는 annotation value
- constant value
- superclass/interface shape
- record component
- sealed hierarchy surface

### 7.2 Binary ABI

이미 compile된 class가 runtime에서 link될 수 있는지와 관련된다.

포함:

- class internal name
- method descriptor
- field descriptor
- class hierarchy
- interface method
- access flag
- bridge method 중 의미 있는 것

### 7.3 Reflection ABI

Spring, Jackson, Hibernate 같은 framework가 reflection으로 보는 표면이다.

포함:

- private field
- constructor
- annotation
- parameter name
- record component
- sealed type metadata
- default constructor
- getter/setter naming

### 7.4 Framework ABI

framework가 의미를 부여하는 표면이다.

예:

- Spring: `@Bean`, `@Component`, `@Transactional`, `@RequestMapping`, `@ConfigurationProperties`
- Jackson: `@JsonProperty`, `@JsonCreator`
- Hibernate/JPA: `@Entity`, `@Id`, relationship annotation
- MapStruct: `@Mapper`, `@Mapping`

### 7.5 Kotlin Semantic ABI

Kotlin/JVM은 classfile만 보면 부족하다.

중요한 항목:

- public inline function body
- reified type parameter
- suspend function
- default argument
- data class generated member
- value class
- sealed class
- internal visibility
- typealias
- top-level function
- extension function
- companion object
- Kotlin Metadata annotation

## 8. Delta Classification

Delta Classifier는 baseline snapshot과 current snapshot을 비교해서 semantic delta를 만든다.

초기 delta kind:

```kotlin
enum class DeltaKind {
    CLASS_ADDED,
    CLASS_REMOVED,
    CLASS_ACCESS_CHANGED,
    CLASS_HIERARCHY_CHANGED,
    METHOD_ADDED,
    METHOD_REMOVED,
    METHOD_BODY_CHANGED,
    METHOD_DESCRIPTOR_CHANGED,
    METHOD_ACCESS_CHANGED,
    METHOD_ANNOTATION_CHANGED,
    FIELD_ADDED,
    FIELD_REMOVED,
    FIELD_DESCRIPTOR_CHANGED,
    FIELD_ACCESS_CHANGED,
    FIELD_ANNOTATION_CHANGED,
    CONSTANT_VALUE_CHANGED,
    GENERIC_SIGNATURE_CHANGED,
    RESOURCE_CHANGED,
    KOTLIN_METADATA_CHANGED,
    KOTLIN_INLINE_BODY_CHANGED,
    UNKNOWN
}
```

각 delta는 영향 profile과 reason을 가진다.

```kotlin
data class SemanticDelta(
    val subject: NodeId,
    val kind: DeltaKind,
    val compileImpact: ImpactLevel,
    val binaryImpact: ImpactLevel,
    val reflectionImpact: ImpactLevel,
    val frameworkImpact: ImpactLevel,
    val testImpact: ImpactLevel,
    val confidence: Confidence,
    val reasons: List<Reason>
)
```

```kotlin
enum class ImpactLevel {
    NONE,
    LOCAL,
    DOWNSTREAM,
    RUNTIME,
    UNKNOWN
}
```

## 9. 주요 변경 규칙

### 9.1 Method body only change

method body만 바뀌고 descriptor, visibility, annotation, exception, generic signature, Kotlin metadata가 그대로라면:

- compile impact: 보통 없음
- binary impact: 없음
- reflection impact: 없음
- test impact: 해당 method 또는 caller를 실행했던 test
- confidence: ABI는 `EXACT`, test는 `OBSERVED`

예외:

- public Kotlin inline function body
- compile-time constant나 generated code에 영향을 주는 변경
- framework annotation/metadata가 함께 바뀐 경우

### 9.2 Public method signature change

visible method descriptor가 바뀌면:

- compile impact: downstream
- binary impact: downstream
- test impact: downstream test와 observed test
- confidence: `EXACT`

### 9.3 Annotation metadata change

annotation 값이 바뀌면:

- Java compile impact는 낮을 수 있다.
- reflection impact는 있을 수 있다.
- framework impact는 annotation profile에 따라 크다.
- framework trace가 없으면 conservative fallback이 필요하다.

예:

- `@Transactional(readOnly = true)` -> `@Transactional`
- `@RequestMapping` path 변경
- `@JsonProperty` name 변경
- `@Entity` mapping 변경

### 9.4 Kotlin public inline function body change

public inline function body가 바뀌면:

- Java식 ABI는 unchanged처럼 보일 수 있다.
- Kotlin semantic ABI는 바뀐다.
- downstream Kotlin caller 재컴파일이 필요할 수 있다.
- caller test를 포함해야 한다.

### 9.5 Compile-time constant change

public static final primitive/String constant 값이 바뀌면:

- field descriptor는 그대로일 수 있다.
- compiled caller에 예전 값이 inline되어 있을 수 있다.
- downstream recompilation을 권장해야 한다.

### 9.6 Annotation processor input change

annotation processor가 소비한 symbol이 바뀌면:

- generated source가 invalidation될 수 있다.
- generated output을 통해 compile impact가 전파된다.
- confidence는 processor trace 품질에 달려 있다.

`ap-trace`가 없으면 known processor-sensitive annotation에 대해 conservative하게 판단한다.

## 10. Impact Solver

Impact Solver는 semantic delta에서 시작해 graph를 따라 안전한 work plan을 만든다.

초기 work plan:

- local module recompile
- downstream module recompile
- impacted tests first
- full suite after impacted tests
- Markdown/JSON report

장기 work plan:

- annotation processor rerun
- generated source invalidation
- Spring bean subgraph reload
- Spring `ApplicationContext` refresh
- full restart

### 10.1 Typed traversal

모든 edge가 모든 impact를 전파하지 않는다. Traversal은 delta kind와 edge kind를 함께 봐야 한다.

예:

- `METHOD_BODY_CHANGED`는 `EXECUTED_BY_TEST`를 통해 test impact로 전파된다.
- `METHOD_DESCRIPTOR_CHANGED`는 caller와 downstream module로 전파된다.
- `FIELD_ANNOTATION_CHANGED`는 framework ABI rule로 전파될 수 있다.
- `KOTLIN_INLINE_BODY_CHANGED`는 Kotlin call site와 downstream Kotlin module로 전파된다.
- `CONSTANT_VALUE_CHANGED`는 source/binary caller로 전파된다.

### 10.2 Conservative fallback

알 수 없는 영역에 도달하면:

- unknown reason을 기록한다.
- 안전한 fallback action을 포함한다.
- report에 fallback 이유를 드러낸다.

예:

```text
Money.currency annotation changed.
Jackson reflection impact possible.
No Jackson profile trace exists.
Recommendation: include serialization tests and full suite fallback.
Confidence: CONSERVATIVE_UNKNOWN.
```

## 11. 첫 구현 범위

첫 공개 구현은 다음 조합이 가장 좋다.

> JVM classfile semantic diff + JUnit impacted-first + Gradle integration

이 범위는 충분히 깊고, 동시에 구현 가능한 크기다.

### 11.1 v0.1 지원 범위

지원:

- Gradle project
- Java classfile
- Kotlin/JVM classfile의 제한적 metadata 감지
- JUnit 5
- multi-module mapping
- Git diff baseline
- classfile ABI diff
- method body fingerprint
- test-to-class/method trace
- Markdown report
- impacted tests first, then full suite

미지원:

- 기본 test skipping
- Spring partial reload
- annotation processor sandbox
- full Kotlin semantic ABI
- Maven plugin
- Bazel/Pants integration
- production-safe generated source invalidation

### 11.2 v0.1 사용자 흐름

test trace 기록:

```bash
jdelta record -- ./gradlew test
```

semantic diff 분석:

```bash
jdelta diff main..HEAD
```

impacted test 계산:

```bash
jdelta impacted-tests main..HEAD
```

Gradle impacted-first:

```bash
./gradlew test -Pjdelta.impactedFirst=true
```

### 11.3 v0.1 출력 예시

```text
Detected 3 semantic changes.

1. com.acme.PriceCalculator#calculate(Order): METHOD_BODY_CHANGED
   Compile ABI: unchanged
   Binary ABI: unchanged
   Impacted tests: 4
   Confidence: OBSERVED

2. com.acme.Timeouts#DEFAULT_MS: CONSTANT_VALUE_CHANGED
   Compile ABI: constant value changed
   Downstream recompilation: recommended
   Confidence: EXACT

3. com.acme.Money#currency: FIELD_ANNOTATION_CHANGED
   Reflection ABI: changed
   Framework impact: possible Jackson impact
   Impacted tests: MoneySerializationTest
   Confidence: CONSERVATIVE_UNKNOWN
```

## 12. Repository 구조

Kotlin + Gradle multi-module로 시작한다.

초기 module:

```text
jdelta-core
jdelta-classfile
jdelta-trace
jdelta-junit
jdelta-gradle-plugin
jdelta-cli
jdelta-report
```

확장 module:

```text
jdelta-kotlin
jdelta-aptrace
jdelta-spring
jdelta-maven
jdelta-agent
```

### 12.1 `jdelta-core`

책임:

- stable ID
- graph model
- delta model
- confidence model
- impact solver contract
- report-neutral work plan model

의존하지 말아야 할 것:

- Gradle
- JUnit
- Spring
- ASM
- Kotlin compiler API

### 12.2 `jdelta-classfile`

책임:

- ASM 기반 classfile parser
- class snapshot model
- ABI extraction
- method body fingerprint
- Java semantic diff
- 초기 Kotlin Metadata 감지

의존:

- `jdelta-core`
- ASM

### 12.3 `jdelta-trace`

책임:

- runtime/test trace model
- trace store
- trace compression
- changed symbol -> test lookup

의존:

- `jdelta-core`

### 12.4 `jdelta-junit`

책임:

- JUnit Platform listener
- test lifecycle boundary 기록
- current execution과 `TestMethodNode` 연결
- trace capture hook

의존:

- `jdelta-core`
- `jdelta-trace`
- JUnit Platform

### 12.5 `jdelta-gradle-plugin`

책임:

- Gradle project/module/source-set mapping
- task integration
- `jdeltaRecord`
- `jdeltaDiff`
- `jdeltaImpactedTests`
- `jdeltaImpactedFirstTest`

의존:

- `jdelta-core`
- `jdelta-classfile`
- `jdelta-trace`

### 12.6 `jdelta-cli`

책임:

- CLI UX
- `record`
- `diff`
- `impacted-tests`
- report command

### 12.7 `jdelta-report`

책임:

- Markdown report
- JSON report
- 나중에 HTML/SARIF report

report는 yes/no 결과로 뭉개지 말고 reason과 confidence를 보존해야 한다.

## 13. Persistence model

local state directory:

```text
.jdelta/
  snapshots/
  traces/
  reports/
  cache/
```

초기 저장 대상:

- class snapshot index
- ABI fingerprint
- method body fingerprint
- test execution trace
- module mapping
- dependency coordinate snapshot
- generated report

초기 형식:

- 사람이 확인하기 쉬운 JSON
- 성능 필요가 생기면 SQLite 또는 binary format 검토

## 14. Test trace 전략

첫 trace 구현은 adoption cost를 낮추고, 위험한 test skip을 피해야 한다.

### 14.1 수집 단위

수집:

- test class
- test method
- executed application class
- executed application method where feasible
- module
- timestamp
- classpath fingerprint

기본적으로 third-party library 호출은 과수집하지 않는다.

### 14.2 수집 방식

단계:

```text
1. JUnit Platform listener가 active test method를 기록한다.
2. Java agent가 application class method entry를 instrument한다.
3. Trace store가 active test method와 executed method ID를 연결한다.
4. Impact solver가 trace를 사용해 impacted tests를 정렬한다.
```

### 14.3 안전 모드

초기 기본 동작:

```text
impacted tests first
then full suite
record misses
```

이 방식은 즉시 빠른 feedback을 주면서도 correctness risk를 낮춘다.

## 15. Annotation Processor Trace 로드맵

`jdelta-aptrace`는 annotation processor가 무엇을 읽고 생성했는지 기록하는 하위 프로젝트다.

기록 대상:

- processor class
- processor option
- read element
- read annotation
- traversed type
- generated source
- generated resource
- originating element
- observable resource read/write
- determinism warning

초기에는 표준 JSR 269 processor를 대상으로 한다. Lombok처럼 compiler internal에 깊게 들어가는 processor는 나중 단계로 미룬다.

## 16. Spring 로드맵

Spring은 static bytecode graph가 아니라 runtime graph다. 따라서 Spring 지원은 runtime 관측을 포함해야 한다.

### 16.1 Bean graph extraction

수집:

- bean name
- bean class
- factory method
- dependency
- scope
- profile
- condition
- configuration property
- endpoint mapping
- proxy/advisor metadata

### 16.2 Reload recommendation

semantic delta를 기반으로 추천:

- no Spring action
- related tests only
- bean metadata changed
- context refresh recommended
- full restart required

### 16.3 Partial reload prototype

정확한 bean graph extraction이 된 뒤에만 시도한다.

전략:

```text
stable layer:
  Spring Framework
  third-party dependencies
  datasource
  entity manager
  web server
  infrastructure beans

reloadable layer:
  application service classes
  controllers
  selected configuration classes
  mappers
```

reload strategy:

```kotlin
enum class ReloadStrategy {
    NO_RELOAD_REQUIRED,
    SAFE_TARGET_SWAP,
    BEAN_SUBGRAPH_RECREATE,
    APPLICATION_CONTEXT_REFRESH,
    FULL_RESTART_REQUIRED,
    UNKNOWN_RESTART_REQUIRED
}
```

어려운 case:

- constructor signature 변경
- `@Bean` method 변경
- `@Configuration` class 변경
- AOP annotation 변경
- JPA entity 변경
- Jackson DTO shape 변경
- static singleton state
- ThreadLocal state
- classloader leak

## 17. CLI 설계

초기 CLI:

```bash
jdelta snapshot
jdelta diff <base>..<head>
jdelta record -- <command>
jdelta impacted-tests <base>..<head>
jdelta report <base>..<head>
```

확장 CLI:

```bash
jdelta ap trace -- <javac-command>
jdelta spring graph
jdelta spring reload-plan <base>..<head>
```

출력 형식:

```bash
jdelta diff main..HEAD --format markdown
jdelta diff main..HEAD --format json
```

## 18. Report 설계

report는 다음을 답해야 한다.

- 무엇이 바뀌었나?
- 어떤 ABI layer가 바뀌었나?
- 어떤 module이 영향받나?
- 어떤 test가 영향받나?
- 어떤 판단이 exact/observed/inferred/conservative unknown인가?
- 어떤 작업을 추천하나?
- 어떤 unknown 때문에 fallback했나?

section:

- Semantic Deltas
- ABI Impact
- Test Impact
- Build Impact
- Conservative Fallbacks
- Confidence Summary
- Next Actions

## 19. Adoption path

도입은 보수적으로 설계한다.

```text
Stage 1: jdelta diff report만 생성
Stage 2: JUnit trace 기록, impacted tests 출력
Stage 3: impacted tests first 실행, full suite 유지
Stage 4: miss detection과 confidence metric 축적
Stage 5: 신뢰된 module에 한해 opt-in skip mode
```

## 20. 6개월 로드맵

### Month 0-1: JVM ABI Diff

deliverable:

- Gradle multi-module skeleton
- `jdelta-core`
- `jdelta-classfile`
- ASM parser
- class snapshot
- compile ABI / binary ABI extraction
- method body fingerprint
- basic CLI diff
- Markdown report

### Month 1-2: Kotlin Metadata Awareness

deliverable:

- Kotlin Metadata 감지
- inline function marker support
- suspend/default argument synthetic mapping research
- conservative Kotlin semantic ABI rules

### Month 2-3: JUnit Trace

deliverable:

- JUnit Platform listener
- Java agent prototype
- test-to-method trace store
- trace report

### Month 3-4: Impact Solver

deliverable:

- semantic delta -> test impact traversal
- impacted tests CLI
- confidence-aware reasons
- impacted-first plan

### Month 4-5: Gradle Plugin

deliverable:

- `jdeltaRecord`
- `jdeltaDiff`
- `jdeltaImpactedTests`
- impacted-first test task integration
- multi-module sample project

### Month 5-6: Spring Graph Prototype

deliverable:

- Spring bean graph dump
- changed class -> affected bean mapping
- annotation/configuration property reload recommendation
- report-only Spring mode

## 21. 첫 데모 프로젝트

첫 데모는 JVM 개발자가 바로 "이 도구는 JVM을 이해한다"고 느껴야 한다.

구성:

- Spring Boot
- Gradle multi-module
- Java + Kotlin mixed
- JUnit 5
- MapStruct 또는 QueryDSL

scenario:

```text
1. private method body 변경
   - compile ABI unchanged
   - impacted tests first
   - Spring context reload unnecessary

2. public method signature 변경
   - compile ABI changed
   - downstream module impacted

3. Kotlin public inline function body 변경
   - Kotlin semantic ABI changed
   - downstream Kotlin callers impacted

4. compile-time constant 변경
   - constant value changed
   - downstream recompilation recommended

5. annotation metadata 변경
   - Java compile ABI may be unchanged
   - framework/reflection ABI changed
   - conservative tests included

6. annotation processor-sensitive DTO 변경
   - generated source invalidation explained
   - conservative until ap-trace exists
```

## 22. 첫 커밋 구현 결정

첫 구현은 다음으로 시작한다.

- Kotlin
- Gradle Kotlin DSL
- Java 17 baseline
- ASM
- JUnit 5
- 최소 dependency CLI

처음 만들 것:

- `jdelta-core`의 stable model
- `jdelta-classfile`의 class snapshot과 ABI diff
- `jdelta-cli`의 report-only diff
- `jdelta-report`의 Markdown/JSON report

처음 피할 것:

- Spring dependency
- Kotlin compiler internal
- 복잡한 bytecode instrumentation
- test skip
- binary persistence

## 23. 초기 구현 순서

구체적인 작업 순서:

```text
1. Gradle multi-module skeleton 생성
2. jdelta-core에 NodeId, Confidence, ImpactLevel, DeltaKind 추가
3. jdelta-core에 SemanticDelta, Reason, WorkPlan 모델 추가
4. jdelta-classfile에 ASM 기반 ClassSnapshotReader 추가
5. class snapshot에서 compile ABI fingerprint와 binary ABI fingerprint 분리
6. method body fingerprint 추가
7. baseline/current directory diff 구현
8. jdelta-cli에서 diff command 노출
9. jdelta-report에서 Markdown report 출력
10. 작은 fixture project로 method body/signature/constant/annotation diff test 작성
```

이 순서로 가면 `jdelta`의 핵심 철학인 "ABI는 하나가 아니다"와 "body change는 compile impact와 test impact가 다르다"를 첫 버전부터 증명할 수 있다.

## 24. 열어둘 질문

구현 중 결정할 질문:

- core graph는 처음부터 persistent graph abstraction이 필요한가, 아니면 immutable data class로 충분한가?
- method body fingerprint에서 line number/debug info를 어느 단계부터 제거할 것인가?
- Kotlin Metadata는 v0.1에서 어디까지 parse하고 어디부터 conservative하게 둘 것인가?
- CLI binary 이름은 처음부터 `jdelta`로 둘 것인가?
- Java agent는 method-level trace부터 갈 것인가, class-level trace로 시작할 것인가?
- report JSON schema를 v0.1부터 stable하게 둘 것인가?

## 25. 한 문장 기준

`jdelta`의 기준 문장은 이것이다.

> 우리는 무엇이 바뀌었는지 알고, 그것이 무엇에 영향을 줄 수 있는지 설명하며, 모르면 모른다고 말하고 안전한 길을 선택한다.
