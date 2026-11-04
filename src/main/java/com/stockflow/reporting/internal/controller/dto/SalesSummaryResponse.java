package com.stockflow.reporting.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Totals of the range beside the equally long period right before it")
public record SalesSummaryResponse(
        LocalDate from,
        LocalDate to,
        @Schema(description = "First day of the previous period") LocalDate previousFrom,
        @Schema(description = "Last day of the previous period") LocalDate previousTo,
        @Schema(example = "VND") String currency,
        Totals current,
        Totals previous,
        @Schema(description = "Orders, this period against the previous, in percent with one decimal "
                + "(12.5 = up 12.5%). Null when the previous period had no orders", example = "12.5")
        BigDecimal ordersChangePercent,
        @Schema(description = "Revenue change in percent; null when the previous period had none")
        BigDecimal revenueChangePercent,
        @Schema(description = "Average order value change in percent; null when the previous period had none")
        BigDecimal averageOrderValueChangePercent) {

    @Schema(description = "Totals of one period")
    public record Totals(
            @Schema(description = "Orders placed, less those cancelled", example = "42") long ordersCount,
            @Schema(description = "Their total, whole dong", example = "504000000") BigDecimal revenue,
            @Schema(description = "revenue / ordersCount, whole dong; 0 when there are no orders", example = "12000000")
            BigDecimal averageOrderValue) {
    }
}
