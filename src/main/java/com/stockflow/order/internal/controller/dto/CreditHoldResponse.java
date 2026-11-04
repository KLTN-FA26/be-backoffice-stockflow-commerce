package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/** An order over its customer's credit limit, with the numbers it was held on. */
@Schema(name = "CreditHold")
public record CreditHoldResponse(OrderResponse order, Instant checkedAt, BigDecimal creditLimit, BigDecimal exposure,
                                 BigDecimal orderAmount) {
}
