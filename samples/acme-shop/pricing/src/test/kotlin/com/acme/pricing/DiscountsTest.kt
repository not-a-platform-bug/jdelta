package com.acme.pricing

import com.acme.core.Order
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiscountsTest {
    @Test
    fun appliesPercent() {
        assertEquals(27, Discounts().discounted(Order("A", 3, 10), 10))
    }

    @Test
    fun tiers() {
        assertEquals("gold", Discounts().tierOf(1500))
    }
}
