package com.acme.core;

public class PriceCalculator {
    public long calculate(Order order) {
        return round(order.quantity() * order.unitPrice());
    }

    public long tax(long amount) {
        return amount / 10;
    }

    private long round(long amount) {
        return (amount + 5) / 10 * 10;
    }
}
