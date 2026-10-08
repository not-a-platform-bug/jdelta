# jdelta

[English](README.md) | **한국어**

**JVM Semantic Delta Engine**: 이 변경이 JVM 프로젝트에서 *의미적으로* 무엇을 바꿨고, 무엇에 영향을 주며, 무엇을 먼저 다시 해야 하는지 알려준다.

```text
$ jdelta impacted-tests main
1  OBSERVED  com.acme.core.PriceCalculatorTest#calculatesRoundedTotal()   PriceCalculator#round(long) -> 이 test가 실행함
2  OBSERVED  com.acme.app.CheckoutServiceTest#checksOut()                 PriceCalculator#round(long) -> 이 test가 실행함
3  OBSERVED  com.acme.pricing.DiscountsTest#appliesPercent()              PriceCalculator#round(long) -> 이 test가 실행함
```

private method 한 줄을 고쳤을 때, 3개 module의 test 9개 중 실제로 그 method를 실행한 3개만 먼저 돌리면 된다는 결과다. 각 판단에는 근거 경로와 신뢰도가 붙는다.

---

## 왜 필요한가

JVM 개발에서 느린 build, 느린 test, 오래 걸리는 CI는 원인이 하나로 모인다.

> **JVM 도구들은 "이 변경이 실제로 무엇을 깨뜨릴 수 있는지"를 정밀하게 모른다.**

모르면 안전을 위해 전부 다시 한다.

```text
private method body 한 줄 변경
  -> module 재컴파일
  -> downstream module 재컴파일
  -> 전체 test suite
  -> CI job 전체 재실행
```

기존 도구는 이 질문에 **file, task, module, test class** 단위로 답한다. 하지만 같은 "파일 변경"이라도 의미는 전혀 다르다.

| 변경 | 실제 의미 | 흔한 도구가 보는 것 |
|---|---|---|
| private method body | 그 method를 실행한 test만 영향. 다른 module은 재컴파일 불필요 | 파일이 바뀜 → module 전체 |
| 주석, 줄 이동 | classfile이 의미상 같음. 아무 영향 없음 | 파일이 바뀜 → module 전체 |
| `public static final` 상수 값 | **호출 측 classfile에 옛 값이 inline되어 남는다.** 의존 module을 다시 컴파일하지 않으면 runtime에 옛 값이 쓰인다 | 의존 module은 그대로일 수 있음 |
| Kotlin public `inline fun` body | **호출 측에 body가 복사된다.** 호출 module 재컴파일 필요 | 시그니처가 같으니 ABI 변화 없음 |
| `@JsonProperty("sku")` → `"code"` | 컴파일은 그대로지만 reflection으로 읽는 framework 동작이 바뀜 | 영향 없음 |
| MapStruct 입력 DTO에 field 추가 | annotation processor가 만든 코드가 바뀔 수 있음 | 보이지 않음 |

`jdelta`는 이 차이를 **classfile 수준의 의미 비교 + 실제 test 실행 기록**으로 구분한다.

## 무엇이 다른가

### 1. 파일이 아니라 의미를 비교한다

source text가 아니라 compile 결과(classfile)를 비교하고, 변화를 ABI layer별로 나눈다.

- **compile ABI** / **binary ABI** / **reflection ABI** / **framework** 영향을 따로 판단한다
- method body hash는 line number, local variable table, lambda 번호를 정규화한다. 주석 한 줄이나 lambda 추가로 생기는 거짓 변경이 0건이다
- `public`인데 package-private 상위 class에서 상속된 member, synthetic bridge, `$default` method 같은 JVM의 구석까지 모델링한다
- Kotlin metadata를 해석해 `internal`(JVM에서는 public이지만 module 밖에서 안 보임), `inline`, nullability·parameter 이름·기본값 변경을 구분한다

### 2. 추측이 아니라 관측으로 test를 고른다

`jdelta record`가 Java agent로 **test method마다 실제로 실행한 method**를 기록한다. 영향 판단은 이 trace와 정적 graph(call, field, 상속, inline)를 함께 따라간다.

- trace 덕분에 static call graph를 끝없이 거슬러 올라가 "거의 모든 test가 영향 대상"이 되는 문제를 피한다
- `<clinit>`, `@BeforeAll`, cached Spring context처럼 **한 번만 실행되는 code**를 따로 추적한다
- trace가 없는 test(새로 추가된 test 포함)는 **항상 포함**한다

### 3. 모르는 것은 모른다고 말한다

모든 판단에 신뢰도가 붙는다.

| confidence | 의미 |
|---|---|
| `EXACT` | descriptor diff, classfile 소유 관계처럼 결정적으로 안다 |
| `OBSERVED` | test 실행 trace로 실제 관측했다 |
| `INFERRED` | framework 규칙이나 정적 분석으로 추론했다 |
| `CONSERVATIVE_UNKNOWN` | 안전하게 배제할 수 없어서 포함했다 |

분석기가 설명하지 못한 classfile 변화는 조용히 무시하지 않고 `UNKNOWN`으로 보고한다. 보수적으로 포함된 test 비율이 높으면 report가 "이번 변경에는 impacted-first 효과가 낮다"고 직접 알려준다.

### 4. test를 건너뛰지 않는다 (impacted-first)

잘못된 test skip은 치명적이다. 그래서 v0.1은 skip하지 않는다.

```text
1. 영향받는 test를 먼저 실행 → 빠른 실패 신호
2. 이어서 전체 suite 실행 → 안전성 유지
3. 두 결과를 비교해 놓친 실패(miss)를 기록 → 신뢰도를 측정
```

phase 1이 실패해도 전체 suite는 끝까지 돌고, build는 마지막에 실패한다. miss 기록은 나중에 opt-in skip 모드를 판단할 근거 데이터가 된다.

### 5. 기존 build를 바꾸지 않는다

새 build 도구가 아니다. Gradle 옆에서 동작한다.

- build script 수정 없이 init script로 plugin을 주입해 project 구조(`api`/`implementation` 구분 포함)를 읽는다
- agent는 runtime dependency 0개(ASM은 relocate)라 사용자 classpath와 충돌하지 않는다
- 결과는 사람이 읽는 Markdown과 기계가 읽는 JSON으로 나온다

## 데모 scenario

`samples/acme-shop`(Java + Kotlin, 3개 module, MapStruct)에서 실제 Gradle로 검증한 결과다. `./gradlew :jdelta-cli:e2eTest`로 다시 돌릴 수 있다.

| # | 변경 | jdelta의 판단 |
|---|---|---|
| 1 | private method body | compile ABI 변화 없음. 그 method를 실행한 test 3개만 선택 (`OBSERVED`) |
| 2 | public method signature | downstream 재컴파일 권고. 바뀐 test 자신이 1순위(`EXACT`), 이어서 하위 module 호출자의 test |
| 3 | Kotlin public inline body | `KOTLIN_INLINE_BODY_CHANGED`: 호출 module 재컴파일 필요, 이미 compile된 호출자는 옛 body를 실행 |
| 4 | `public static final` 상수 | 값이 inline된 호출자를 다시 compile하도록 권고. 영향 범위를 좁힐 수 없어 보수적으로 포함하고 그 사실을 명시 |
| 5 | runtime annotation 값 | compile ABI는 그대로, framework 영향 `RUNTIME`. 해당 class를 실행한 test (`INFERRED`) |
| 6 | MapStruct 입력 DTO | 생성 code가 바뀔 수 있음을 설명하고, 생성 class를 실행한 test를 보수적으로 포함 |

report 예시(scenario 3, 발췌):

```markdown
### 2. `com.acme.pricing.MeasureKt#measured(String, Function0)` — KOTLIN_INLINE_BODY_CHANGED
| compile ABI | **downstream** |
| binary ABI  | runtime only   |
- Why: inline function body changed; the body is copied into callers, which keep the old copy until recompiled

## Test Impact
| 1 | `com.acme.app.CheckoutServiceTest#checksOut()` | OBSERVED | CheckoutService#checkout(Order) → CheckoutServiceTest#checksOut() |
```

## 언제 쓰면 좋은가

**잘 맞는 경우**

- **test suite가 몇 분씩 걸리는 Gradle multi-module project.** 영향받는 test를 먼저 돌려 전체 suite가 끝나기 전에 실패 신호를 얻는다. 아래 측정에서 선택된 test는 전체 test 시간의 3~41%였다.
- **PR review와 CI report.** test trace가 없어도 `jdelta diff`가 각 변경의 ABI 수준 의미를 보여준다. downstream 재컴파일이 필요한지, 상수가 inline되었는지, Kotlin `inline` body가 바뀌었는지, framework metadata가 바뀌었는지.
- **Java/Kotlin 혼용, annotation processor, reflection 중심 framework를 쓰는 code.** "파일이 바뀌었다"만으로는 실제 영향을 알기 어려운 경우다.
- **library나 공용 module 관리자.** 변경이 의존하는 쪽의 compile/binary 호환성을 깨는지 알고 싶을 때.

**덜 유용한 경우**

- **작고 빠른 test suite.** 10초 정도면 끝나는 suite에서는 Gradle의 고정 비용(약 2.5초)이 커서 impacted-first로 줄어드는 시간이 몇 초뿐이다.
- **거의 모든 code가 실행하는 부분의 변경**(core utility, 상수, build script). 많은 test가 선택되거나 module 전체로 물러서고, report가 그 사실을 알려준다.
- **Gradle + JUnit이 아니거나, test가 주로 resource와 외부 설정에 의존하는 project.** resource 변경은 항상 보수적으로 다룬다.

## 측정 결과

jdelta 자신의 repository 사본에 jdelta를 돌려 측정했다. Gradle module 10개, trace가 기록된 test method 93개, 전체 suite wall time 10.6초(병렬 fork의 test 시간 합 34.8초). Apple M2 Pro(12 core), JDK 25, Gradle 9.5.1. 작은 repository 하나와 몇 가지 변경으로 잰 값이므로 벤치마크가 아니라 경향으로 읽어야 한다.

**비용**

| 항목 | 결과 |
|---|---|
| 기록 오버헤드(`jdelta record`) | 전체 suite 11.0–12.7초 → agent 사용 시 12.3–13.3초 (약 +5~10%, 편차 있음) |
| test당 기록된 method 수 | 중앙값 179, 최대 535 |
| 저장 공간 | trace store 1.9 MB, snapshot 하나 2.3 MB |
| 분석 시간(`impacted-tests`, build 이후) | 0.7–1.0초 |

**대표적인 변경에서의 선택**

| 변경 | 선택된 test (93개 중) | test 시간 비중 | phase 1 wall time* |
|---|---|---|---|
| report 출력 형식 body | 9 (`OBSERVED`) | 20% | 5.4초 |
| 널리 쓰이는 core ID parser body | 13 (`OBSERVED`) | 27% | 5.4초 |
| 분류기의 private method | 4 (`OBSERVED`) | 3% | 2.9초 |
| 주석만 변경 | 0 | 0% | – |
| core enum에 public method 추가 | 24 (`INFERRED`) | 36% | 6.0초 |
| `const val` 변경 | 29 (`OBSERVED` 7, `INFERRED` 12, 보수적 10) | 41% | 6.5초 |

\* 선택된 test만 Gradle로 실행한 시간이며 Gradle 고정 비용 약 2.5초를 포함한다. 전체 suite는 10.6초다. 이와 별도로 trace가 없는 test 10개(e2e suite처럼 tag로 `test` task에서 빠진 test)는 항상 포함된다.

**놓친 실패가 있었나?**

실제로 test를 깨뜨린 변경 4개에서 전체 suite 실패가 5건 나왔다. **5건 모두 선택된 test에 포함되었고(놓친 것 0건)**, 순위는 1~9위였다. test를 깨뜨리지 않은 변경 2개는 놓침 여부를 확인할 수 없었다.

## 시작하기

JDK 17 이상이 필요하다.

```bash
./gradlew :jdelta-cli:installDist      # jdelta-cli/build/install/jdelta/bin/jdelta
```

분석할 Gradle project에서:

```bash
# 1. 기준점(main)에서: build output snapshot과 test trace를 남긴다
jdelta snapshot --build
jdelta record -- ./gradlew test

# 2. 변경 후: merge-base 대비 의미적 변경 + 영향받는 test
jdelta diff main --build                 # Markdown report (--format json)
jdelta impacted-tests main               # 순서대로 (--format json | gradle-filter)
jdelta impacted-tests main --format gradle-filter | xargs ./gradlew

# 3. 또는 Gradle에서 impacted-first 실행
./gradlew test -Pjdelta.impactedFirst=true -Pjdelta.home=<jdelta 배포본> -Pjdelta.base=main
```

CI에서는 main build마다 `jdelta snapshot --out snap/`을 artifact로 남기고, PR build에서 `jdelta diff --baseline-dir snap/`으로 쓴다. snapshot이 없으면 `--build-baseline`이 임시 git worktree에서 기준점을 build한다. git 없이 두 output directory를 직접 비교할 수도 있다(`jdelta diff --base-classes ... --head-classes ...`).

`.jdelta/`는 언제든 다시 만들 수 있는 cache이므로 `.gitignore`에 넣는다.

## 현재 상태와 한계

v0.1 구현(ARCHITECTURE.md §14의 1~16단계)이 끝났고 schema는 아직 unstable이다.

- 지원: Gradle, Java/Kotlin, JUnit 5(JUnit 4 일부), framework profile(Spring, Jackson, JPA, MapStruct, Lombok)은 annotation 기반 규칙
- test skip 모드는 없다. 전체 suite는 항상 실행된다
- 상수 변경은 옛 값을 쓰는 class를 골라내지 못해 의존 module 전체로 보수적으로 넓힌다
- `jdelta record`는 Gradle command만 지원한다. Maven과 IDE 연동은 아직 없다

## 문서

- [docs/JDELTA_DESIGN.md](docs/JDELTA_DESIGN.md): 문제 정의, 철학, 로드맵
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): v0.1 기술 설계와 구현 결정 (module 구조, 모델, 알고리즘, 구현 순서)

## 개발

```bash
./gradlew build                  # 전체 build + test
./gradlew :jdelta-cli:e2eTest    # 데모 scenario 1~6 (실제 Gradle build, Maven Central 접근 필요)
```
