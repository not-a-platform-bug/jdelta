package jdelta.core

/** ABI layer 하나에 대한 영향 범위. 의미는 ARCHITECTURE.md §2.4. */
public enum class ImpactLevel(private val severity: Int) {
    /** 이 layer에서 영향 없음 */
    NONE(0),

    /** 소유 module(과 그 test source set) 안에서만 영향 */
    LOCAL(1),

    /** 소유 module에 의존하는 module까지 영향 */
    DOWNSTREAM(3),

    /** recompile로는 드러나지 않고 실행 시점에만 드러남 */
    RUNTIME(2),

    /** 판단 불가. conservative fallback 적용 */
    UNKNOWN(4),
    ;

    public infix fun max(other: ImpactLevel): ImpactLevel = if (severity >= other.severity) this else other

    public companion object {
        public fun maxOf(levels: Iterable<ImpactLevel>): ImpactLevel = levels.fold(NONE, ImpactLevel::max)
    }
}
