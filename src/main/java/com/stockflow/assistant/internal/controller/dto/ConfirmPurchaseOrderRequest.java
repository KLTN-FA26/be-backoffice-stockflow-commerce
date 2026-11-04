package com.stockflow.assistant.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A suggested purchase order as its owner confirms it. Prices are the supplier's current catalog prices; a SKU the
 * assistant did not suggest is added on the purchase-order screen. The limits are the create-PO form's
 * ({@code CreatePurchaseOrderRequest}, {@code CreatePOLineRequest}), so a value refused there is refused here with the
 * same field named.
 *
 * @param expectedAt null for today plus the slowest line's lead time, as the purchase-order form does
 */
public record ConfirmPurchaseOrderRequest(
        @NotNull UUID warehouseId,
        LocalDate expectedAt,
        @Size(max = 2000) String note,
        @NotEmpty @Size(max = 200) @Valid List<@NotNull Line> lines) {

    public record Line(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String sku,
                       @Positive @Max(1_000_000) int quantity) {
    }
}
