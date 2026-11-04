package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(name = "PurchaseOrderRevisionDetail", description = "A revision's header, lines and what it changes")
public record PurchaseOrderRevisionDetailResponse(
        PurchaseOrderRevisionResponse revision,
        String currency,
        LocalDate expectedDate,
        String note,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        List<Line> lines,
        @Schema(description = "The closest earlier approved revision (the one just before when none was approved);"
                + " null for the first") Long comparedWithRevisionNo,
        List<RevisionChangeResponse> changes) {

    public record Line(
            int lineNo,
            String sku,
            String description,
            String unit,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal taxRate,
            BigDecimal lineSubtotal,
            BigDecimal lineTax,
            BigDecimal lineTotal) {}
}
