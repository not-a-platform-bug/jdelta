package com.acme.app

import com.acme.core.Order
import com.acme.core.Timeouts
import com.acme.pricing.Discounts
import com.acme.pricing.measured

class CheckoutService(private val discounts: Discounts = Discounts()) {
    fun checkout(order: Order): Long = measured("checkout") { discounts.discounted(order, 10) }

    fun timeoutMs(): Long = Timeouts.DEFAULT_MS * 2
}
