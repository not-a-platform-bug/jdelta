package com.acme.core;

import java.lang.reflect.Field;

/** reflection으로 column 이름을 읽는 작은 mapper. */
public class OrderRow {
    @Column(name = "sku")
    public String sku;

    @Column(name = "price")
    public long price;

    public static String columns() {
        StringBuilder sb = new StringBuilder();
        for (Field f : OrderRow.class.getDeclaredFields()) {
            Column c = f.getAnnotation(Column.class);
            if (c != null) sb.append(sb.length() == 0 ? "" : ",").append(c.name());
        }
        return sb.toString();
    }
}
