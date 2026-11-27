package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * How many RECORDED receipts a filter matches and what they add up to (2026-11-14 plan, BE2-A), shown above the
 * accountant's list. {@code totalAmount} and {@code currency} are null only should the receipts ever be in two
 * currencies; {@code byCurrency} then holds each.
 */
@Schema(name = "PaymentReceiptSummary")
public record PaymentReceiptSummaryResponse(long count, BigDecimal totalAmount, String currency,
                                            List<CurrencyTotal> byCurrency) {

    /** The RECORDED receipts in one currency. */
    public record CurrencyTotal(String currency, long count, BigDecimal totalAmount) {
    }
}
