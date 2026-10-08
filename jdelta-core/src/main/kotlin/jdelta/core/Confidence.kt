package jdelta.core

/**
 * 판단의 근거 강도. 선언 순서가 강한 순서다.
 *
 * - path confidence = path 위에서 가장 약한 것 ([weakest])
 * - 결론 confidence = 그 결론에 도달한 path 중 가장 강한 것 ([strongest])
 */
public enum class Confidence {
    /** classfile 소유 관계, descriptor diff처럼 deterministic하게 안다. */
    EXACT,

    /** test 실행 trace처럼 실제 실행에서 관측했다. */
    OBSERVED,

    /** framework rule이나 static analysis로 추론했다. */
    INFERRED,

    /** 안전하게 배제할 수 없어서 포함했다. */
    CONSERVATIVE_UNKNOWN,
    ;

    public infix fun weakest(other: Confidence): Confidence = if (ordinal >= other.ordinal) this else other

    public infix fun strongest(other: Confidence): Confidence = if (ordinal <= other.ordinal) this else other

    public companion object {
        public fun weakestOf(values: Iterable<Confidence>): Confidence = values.fold(EXACT, Confidence::weakest)

        public fun strongestOf(values: Iterable<Confidence>): Confidence =
            values.fold(CONSERVATIVE_UNKNOWN, Confidence::strongest)
    }
}
