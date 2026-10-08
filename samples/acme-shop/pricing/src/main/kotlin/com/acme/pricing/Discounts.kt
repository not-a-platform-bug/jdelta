package com.acme.pricing

import com.acme.core.Order
import com.acme.core.PriceCalculator

class Discounts(private val calculator: PriceCalculator = PriceCalculator()) {
    fun discounted(order: Order, percent: Int): Long = calculator.calculate(order) * (100 - percent) / 100

    fun tierOf(total: Long): String = if (total >= 1000) "gold" else "basic"
}
