package com.acme.app

import com.acme.core.Order
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CheckoutServiceTest {
    @Test
    fun checksOut() {
        assertEquals(27, CheckoutService().checkout(Order("A", 3, 10)))
    }

    @Test
    fun timeout() {
        assertEquals(2000, CheckoutService().timeoutMs())
    }
}
