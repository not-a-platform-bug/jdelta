package com.acme.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthTest {
    @Test
    fun up() {
        assertEquals("UP", Health.status())
    }
}
