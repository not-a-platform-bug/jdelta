package com.acme.core;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderRowMapperTest {
    @Test
    void mapsOrderToRow() {
        OrderRow row = Mappers.getMapper(OrderRowMapper.class).toRow(new Order("A", 1, 10));
        assertEquals("A", row.sku);
        assertEquals(10, row.price);
    }
}
