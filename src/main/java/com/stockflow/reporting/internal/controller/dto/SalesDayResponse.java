package com.stockflow.reporting.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "One Asia/Ho_Chi_Minh day of sales; days with no orders are present with zeros")
public record SalesDayResponse(
        @Schema(example = "2026-10-24") LocalDate day,
        @Schema(description = "Orders placed that day, less those cancelled since", example = "7") int ordersCount,
        @Schema(description = "Their total, whole dong", example = "84000000") BigDecimal revenue,
        @Schema(example = "VND") String currency) {
}
