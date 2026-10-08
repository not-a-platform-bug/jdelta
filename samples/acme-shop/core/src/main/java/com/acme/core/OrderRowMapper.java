package com.acme.core;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct가 compile 시점에 OrderRowMapperImpl을 생성한다. */
@Mapper
public interface OrderRowMapper {
    @Mapping(target = "price", source = "unitPrice")
    OrderRow toRow(Order order);
}
