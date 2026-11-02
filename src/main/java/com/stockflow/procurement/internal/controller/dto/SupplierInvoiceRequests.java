package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request bodies of the supplier invoice endpoints (SCRUM-308/309). */
public final class SupplierInvoiceRequests {

    private SupplierInvoiceRequests() {
    }

    /**
     * One line as on the paper. {@code quantity} is in the PO line's base unit, as ordered and received;
     * {@code unitPrice} before tax, at most 2 decimals; {@code taxRate} in percent, absent is 0.
     */
    public record Line(@NotNull UUID purchaseOrderLineId,
                       UUID receiptLineId,
                       @NotNull @Min(1) Integer quantity,
                       @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal unitPrice,
                       @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxRate) {
    }

    /** {@code purchaseOrderId} absent: the invoice bills lines of several of the supplier's orders. */
    public record Enter(@NotNull UUID supplierId,
                        UUID purchaseOrderId,
                        @NotBlank @Size(max = 64) String invoiceNumber,
                        @NotNull LocalDate invoiceDate,
                        LocalDate dueDate,
                        @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
                        @Size(max = 500) String note,
                        @NotEmpty @Size(max = 200) List<@NotNull @Valid Line> lines) {
    }

    /**
     * The whole editable invoice, the order it names too ({@code purchaseOrderId} absent: several); {@code version}
     * the one read, to refuse a change made meanwhile.
     */
    public record Revise(UUID purchaseOrderId,
                         @NotBlank @Size(max = 64) String invoiceNumber,
                         @NotNull LocalDate invoiceDate,
                         LocalDate dueDate,
                         @Size(max = 500) String note,
                         @NotEmpty @Size(max = 200) List<@NotNull @Valid Line> lines,
                         Long version) {
    }

    public record Cancel(@NotBlank @Size(max = 500) String reason) {
    }
}
