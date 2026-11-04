package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * A goods receipt proposed from a photographed delivery note (design §8.1): what the goods-receipt screen prefills for
 * the person counting the delivery to check against the paper. Nothing has been written; the receipt is created and
 * counted on that screen as before. Every value was read from the photo by a model and must be checked, the
 * quantities, lots and expiry dates above all.
 *
 * <p>{@code readText} is the model's reading of a line, which may hold any text the paper holds: show it as plain
 * text, never as Markdown or HTML. A value that is null below is left out of the JSON, as everywhere in the API.</p>
 *
 * @param poNumber     the purchase order, as the system has it
 * @param supplierName the order's supplier, from the system, not from the photo
 * @param lines        the note's lines matched to an open line of the order, in the order printed
 * @param unmatched    the note's lines that match no open line of the order: none named, one the order does not have,
 *                     or one already received, closed or cancelled
 * @param warnings     about the draft as a whole: {@code NOTHING_READ}, {@code TOO_MANY_LINES}
 */
public record ReceiptDraftResponse(
        String poNumber,
        String supplierName,
        List<Line> lines,
        List<Unmatched> unmatched,
        List<String> warnings) {

    /**
     * @param poLineNo         the line of the purchase order it is
     * @param outstanding      what the supplier still has to deliver on that line
     * @param proposedQuantity the units read, never capped; null when none could be read
     * @param lotNumber        null when none was read, or when the SKU is not lot-tracked
     * @param expiryDate       null when none was read, or when the SKU is not expiry-tracked; kept when already past
     * @param readText         what the model read on the note, to compare with the paper
     * @param warnings         what to check: QUANTITY_UNREADABLE, OVER_DELIVERY, OVER_RECEIPT_TOLERANCE, LOT_REQUIRED,
     *                         LOT_UNREADABLE, EXPIRY_REQUIRED, EXPIRY_UNREADABLE, EXPIRY_NOT_AFTER_RECEIPT,
     *                         EXPIRY_NEAR, DUPLICATE_RECEIPT_LINE; the receipt screen's own codes where it has one
     */
    public record Line(
            int poLineNo,
            String sku,
            int ordered,
            int outstanding,
            @Schema(nullable = true) Integer proposedQuantity,
            @Schema(nullable = true) String lotNumber,
            @Schema(nullable = true) LocalDate expiryDate,
            String readText,
            List<String> warnings) {
    }

    /** @param quantity the units read; null when none could be */
    public record Unmatched(String readText, @Schema(nullable = true) Integer quantity) {
    }
}
