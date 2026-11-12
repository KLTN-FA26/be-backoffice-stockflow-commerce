package com.stockflow.invoicing.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
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
     * Whether the shipment has an original that was replaced. Its replacement stands for the shipment and
     * {@link #findLiveOriginalOfShipment} returns it, so this answers true only where that found nothing; a second
     * original beside a replacement is refused here and by {@code tg_einvoice_original_per_shipment}.
     */
    boolean hasReplacedOriginalOfShipment(UUID shipmentId);

    /**
     * The invoice that stands for the order's shipping fee rest - the original that names no shipment while it is not
     * voided or replaced, else what replaced it - as {@link #findLiveOriginalOfShipment} answers for a shipment.
     */
    Optional<EInvoice> findLiveShippingFeeInvoice(UUID orderId);

    /**
     * Takes the order's invoicing lock until the transaction ends: drafts of one order are drawn up one after the
     * other, so each sees the fees the one before put on its invoice, and a draft and the voiding of a corrected
     * delivery never pass each other.
     */
    void lockOrder(UUID orderId);

    /** What the order's originals that were not voided already carry of its fees, and how many pieces. */
    FeeAllocation.Invoiced invoicedOfOrder(UUID orderId);

    /**
     * Writes the invoice against the version it was read at.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OPTIMISTIC_LOCK} when it changed meanwhile
     */
    EInvoice save(EInvoice invoice);

    /**
     * Up to {@code limit} invoices waiting for the provider's answer and nobody is asking about right now: the one
     * asked about least recently first, and those marked unknown to the provider (which wait for the accountant) after
     * all the others, so that no group of invoices keeps the rest out of a bounded batch.
     */
    List<UUID> findSubmitting(int limit);

    /**
     * Up to {@code limit} invoices still to be issued - drafts, rejected ones, and ones the provider has not answered
     * for - whose deadline is before {@code dueBefore} and whose accountant was not told yet, the most urgent first.
     */
    List<UUID> findDueUnwarned(Instant dueBefore, int limit);

    /**
     * Up to {@code limit} drafts the system may issue by itself, the earliest dated first: originals that were never
     * sent and have nothing to look at. A draft that came back from a rejection is not among them - somebody has to
     * correct it first. Dated order, not deadline order, because an invoice is refused while one dated before it waits
     * ({@link #findEarlierUnissued}), and the deadline does not tell a Friday's invoice from a Saturday's.
     */
    List<UUID> findAutoIssuable(int limit);

    /**
     * The code of an invoice other than {@code id}, dated in the year of {@code issueDate} but before it, that is
     * still to be issued - a draft, a rejected one, or one the provider has not answered for - the earliest dated
     * first; empty when there is none. A voided draft is not waiting for anything.
     */
    Optional<String> findEarlierUnissued(UUID id, LocalDate issueDate);

    /**
     * Up to {@code limit} invoices sent to the provider before {@code submittedBefore} that still wait for its answer
     * and whose accountants were not told about this submission, the longest waiting first.
     */
    List<UUID> findStuckUnannounced(Instant submittedBefore, int limit);

    /**
     * Notes that the accountants were told this submission is stuck.
     *
     * @return false when that was noted before: the submission is announced once
     */
    boolean markStuckAnnounced(UUID id, String transactionId, Instant now);

    /** The next internal invoice code of {@code day}: {@code EINV-yyyyMMdd-nnnn}. */
    String nextCode(java.time.LocalDate day);
}
