package com.acme.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderRowTest {
    @Test
    void readsColumnNames() {
        assertEquals("sku,price", OrderRow.columns());
    }
}
