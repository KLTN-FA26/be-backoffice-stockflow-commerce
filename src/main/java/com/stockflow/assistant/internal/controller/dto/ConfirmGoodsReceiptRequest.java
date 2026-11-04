package com.stockflow.assistant.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * A goods receipt read from a delivery note, as its owner confirms it: the count, each line on a line of the purchase
 * order, as the goods-receipt screen takes it. The receipt is created as a DRAFT, to confirm on that screen. The limits
 * are that screen's ({@code CreateGoodsReceiptRequest}, {@code ReceiptLinesRequest}).
 *
 * @param locationCode the RECEIVING area every line goes to, unless a line names its own; a line with neither is
 *                     refused
 */
public record ConfirmGoodsReceiptRequest(
        @Size(max = 100) String deliveryNote,
        @Size(max = 2000) String note,
        @Size(max = 64) String locationCode,
        @NotEmpty @Size(max = 200) @Valid List<@NotNull Line> lines) {

    public record Line(@Min(1) int poLineNo, @Positive int quantity, @Size(max = 64) String lotNumber,
                       LocalDate expiryDate, @Size(max = 64) String locationCode, @Size(max = 255) String note) {
    }
}
