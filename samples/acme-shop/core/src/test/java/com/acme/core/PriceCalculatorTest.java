package com.acme.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceCalculatorTest {
    private final PriceCalculator calculator = new PriceCalculator();

    @Test
    void calculatesRoundedTotal() {
        assertEquals(30, calculator.calculate(new Order("A", 3, 9)));
    }

    @Test
    void computesTax() {
        assertEquals(10, calculator.tax(100));
    }
}
