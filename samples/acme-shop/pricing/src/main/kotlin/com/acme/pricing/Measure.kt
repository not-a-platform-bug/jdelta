package com.acme.pricing

/** caller에 inline되는 public function. body가 바뀌면 caller를 다시 compile해야 한다. */
inline fun <T> measured(label: String, block: () -> T): T {
    val result = block()
    Metrics.record(label)
    return result
}

object Metrics {
    val recorded = mutableListOf<String>()

    fun record(label: String) {
        recorded += label
    }
}
