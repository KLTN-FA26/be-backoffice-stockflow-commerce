package com.stockflow.invoicing.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: e-invoices, each written and read with its lines. */
public interface EInvoiceRepository {

    Optional<EInvoice> findById(UUID id);

    /**
     * The invoice with its row locked until the transaction ends, read afresh: two people issuing one draft, or the
     * accountant and the reconciliation job, are decided one after the other.
     */
    Optional<EInvoice> findByIdForUpdate(UUID id);

    /**
     * The invoice that stands for a shipment (kltn-docs 21 BR-01: at most one in force): its original while that is not
     * voided or replaced, else what replaced it. While a replacement is still a draft the invoice it will replace is
     * the one in force, and the one returned.
     */
    Optional<EInvoice> findLiveOriginalOfShipment(UUID shipmentId);

    /** What already corrects an invoice: its live adjustments and replacement. Read under the invoice's lock. */
    EInvoice.Corrections correctionsOf(UUID invoiceId);

    /**
     * The issued invoices in force of an order - originals and replacements, never adjustments - the oldest first:
     * what goods returned on the order are taken off.
     */
    List<EInvoice> findInForceOfOrder(UUID orderId);

    /**
     * The pieces of each order line that the adjustments for returned goods of an invoice already take off it, by
     * order line: adjustments that are not voided, issued or not.
     */
    java.util.Map<UUID, Long> returnedPieces(UUID invoiceId);

    /** Whether an adjustment was ever drawn up for a return, voided or not: a return is taken off its invoices once. */
    boolean hasAdjustmentForReturn(UUID returnRequestId);

    /** Whether an adjustment for this return, not voided, already names this invoice ({@code uk_einvoice_return_adjustment}). */
    boolean hasLiveReturnAdjustment(UUID returnRequestId, UUID invoiceId);

    /**
     * Writes the invoice against the version it was read at.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OPTIMISTIC_LOCK} when it changed meanwhile
     */
    EInvoice save(EInvoice invoice);

    /** Up to {@code limit} invoices waiting for the provider's answer, the longest waiting first. */
    List<UUID> findSubmitting(int limit);

    /**
     * Up to {@code limit} drafts and rejected invoices whose deadline is before {@code dueBefore} and whose accountant
     * was not told yet, the most urgent first.
     */
    List<UUID> findDueUnwarned(Instant dueBefore, int limit);

    /**
     * Up to {@code limit} drafts the system may issue by itself, the nearest deadline first: originals that were never
     * sent and have nothing to look at. A draft that came back from a rejection is not among them - somebody has to
     * correct it first.
     */
    List<UUID> findAutoIssuable(int limit);

    /** The next internal invoice code of {@code day}: {@code EINV-yyyyMMdd-nnnn}. */
    String nextCode(java.time.LocalDate day);
}
