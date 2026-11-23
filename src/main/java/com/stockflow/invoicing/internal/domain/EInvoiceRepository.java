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
     * the one in force, and the one returned. An original reduced in full because its delivery was corrected to a
     * failure ({@link #markReversed}) stands for nothing any more: the shipment delivered again gets a new original.
     */
    Optional<EInvoice> findLiveOriginalOfShipment(UUID shipmentId);

    /**
     * Notes that an issued invoice - the original, or the replacement standing for the shipment - no longer stands for
     * its shipment: its delivery was corrected to a failure and the full reduction of it
     * ({@code CorrectionBasis.DELIVERY_FAILED}) was drawn up in this transaction (kltn-docs 21 §4.4-§4.5, BR-01; WF-05
     * HĐ-3). From then on {@link #findLiveOriginalOfShipment}, {@link #findInForceOfOrder}, {@link #invoicedOfOrder},
     * {@link #hasReplacedOriginalOfShipment} and the completion check pass it over - and, for a replacement, the
     * original it replaced - and the shipment may take a new original ({@code uk_einvoice_original_per_shipment},
     * {@code uk_einvoice_in_force_per_shipment}, {@code tg_einvoice_original_per_shipment} leave them out).
     *
     * <p>Outside the aggregate and its version, as the overdue reminder is: the invoice is issued and says nothing new -
     * what changes is which invoice stands for the shipment. {@code tg_einvoice_guard} lets it be written once, on an
     * issued invoice, and only while a live reduction of that kind names it.</p>
     *
     * @return false when it was noted before
     */
    boolean markReversed(UUID invoiceId, Instant at);

    /**
     * Notes that the delivery an invoice still at the provider was sent for has been corrected to a failure (WF-05
     * HĐ-3): nothing can be done to it until the provider answers, and the mark says what to do then - reduce it in
     * full once issued, void it once refused ({@code EInvoiceDrafts.settleCorrectedDelivery}). Outside the aggregate
     * and its version, as {@link #markReversed}: an invoice at the provider does not change. Written once, while it is
     * SUBMITTING ({@code tg_einvoice_delivery_failed}, which also keeps it from being sent again).
     *
     * @return false when it is no longer SUBMITTING, or was marked before
     */
    boolean markDeliveryFailed(UUID invoiceId, Instant at);

    /** Whether the invoice was marked by {@link #markDeliveryFailed}. */
    boolean deliveryFailed(UUID invoiceId);

    /**
     * Takes the mark of {@link #markDeliveryFailed} back: the delivery was corrected again, to delivered (a failure
     * recorded by mistake, or delivered on the carrier's retry), so the invoice is what it was before - one the
     * provider is working on, or one it refused, to be corrected and sent again as usual. Never on an issued invoice,
     * which does not change; one so marked is settled as delivered ({@code EInvoiceDrafts.settleCorrectedDelivery}).
     *
     * @return false when it was not marked, or is issued or voided
     */
    boolean clearDeliveryFailed(UUID invoiceId);

    /**
     * The invoices of a shipment marked by {@link #markDeliveryFailed} and not settled yet: at the provider, or refused
     * and not yet voided. Oldest first.
     */
    List<EInvoice> findUnsettledDeliveryFailedOfShipment(UUID shipmentId);

    /**
     * The replacement of {@code originalId} on its way - drawn up, refused or at the provider - if any. An issued
     * replacement has taken the original's place already ({@link #findLiveOriginalOfShipment} finds it).
     */
    Optional<EInvoice> findPendingReplacementOf(UUID originalId);

    /** What already corrects an invoice: its live adjustments and replacement. Read under the invoice's lock. */
    EInvoice.Corrections correctionsOf(UUID invoiceId);

    /**
     * The issued invoices in force of an order - originals and replacements, never adjustments, never an original
     * reversed for a failed delivery ({@link #markReversed}) - the oldest first:
     * what goods returned on the order are taken off. Ids only: the caller locks them and reads each once, under its
     * lock.
     */
    List<UUID> findInForceOfOrder(UUID orderId);

    /**
     * The pieces of each order line that the adjustments of an invoice already take off it, by order line: every
     * adjustment that is not voided, issued or not, that changes a quantity - a return's, a failed delivery's (all of
     * them), and an error's that lowered a
     * quantity (one that raised it puts pieces back, below zero). Counting only the returns' adjustments let a return
     * take off again pieces an error's adjustment had already taken, and leave pieces it had added unplaced. A price
     * that was wrong and a trade discount change money, not pieces, and are not counted.
     */
    java.util.Map<UUID, Long> piecesTakenOff(UUID invoiceId);

    /** Whether an adjustment was ever drawn up for a return, voided or not: a return is taken off its invoices once. */
    boolean hasAdjustmentForReturn(UUID returnRequestId);

    /** Whether an adjustment for this return, not voided, already names this invoice ({@code uk_einvoice_return_adjustment}). */
    boolean hasLiveReturnAdjustment(UUID returnRequestId, UUID invoiceId);

    /**
     * Whether the shipment has an original that was replaced, and whose replacement still stands for the shipment or
     * may. Its replacement stands for the shipment and {@link #findLiveOriginalOfShipment} returns it, so this answers
     * true only where that found nothing; a second original beside a replacement is refused here and by
     * {@code tg_einvoice_original_per_shipment}. A replacement reversed for a failed delivery ({@link #markReversed})
     * keeps nothing back: the shipment delivered again gets a new original.
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

    /**
     * What the order's originals that were not voided already carry of its fees, and how many pieces. An original
     * reversed for a failed delivery ({@link #markReversed}) carries nothing: its full reduction took its fees off too,
     * and the shipment's new original carries them again.
     */
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
     * correct it first. Dated order, not deadline order: numbers of a series should run in the order of the dates
     * (NĐ 254 Phụ lục điểm 2), and the deadline does not tell a Friday's invoice from a Saturday's.
     */
    List<UUID> findAutoIssuable(int limit);

    /**
     * The code of an invoice other than {@code id}, dated before {@code issueDate}, that is at the provider under
     * {@code series} - SUBMITTING, flagged unknown to the provider or not, since either may already hold a number - the
     * earliest dated first; empty when there is none. What keeps an invoice of the same series from being sent
     * ({@code EInvoiceIssuanceSteps.submit}). Read under {@link #lockSeries}.
     */
    Optional<String> findEarlierSubmitting(UUID id, InvoiceSeries series, LocalDate issueDate);

    /**
     * The code of an invoice other than {@code id}, dated in the year of {@code issueDate} but before it, still to be
     * issued under {@code series}, the earliest dated first; empty when there is none. One at the provider counts when it
     * was sent under that series; a draft or a rejected one, which has no series until it is sent, counts when it is of
     * the same year - it will be numbered under the series in use for its year, which is {@code series}. A voided draft
     * is not waiting for anything. What the draft's screen warns of; it keeps nothing back.
     */
    Optional<String> findEarlierUnissued(UUID id, InvoiceSeries series, LocalDate issueDate);

    /**
     * Takes the series' submission lock until the transaction ends: two invoices of one series are sent one after the
     * other, so the second reads the first's SUBMITTING once it is committed ({@link #findEarlierSubmitting}).
     */
    void lockSeries(InvoiceSeries series);

    /**
     * Up to {@code limit} invoices past their deadline at {@code now}, still to be issued - drafts, rejected ones, and
     * ones at the provider - that were warned of the deadline and whose accountants were last told about them before
     * {@code toldBefore}, the longest overdue first: an invoice nobody issued is announced again, once a day.
     */
    List<UUID> findOverdueToRemind(Instant now, Instant toldBefore, int limit);

    /**
     * Notes that the accountants were reminded now of an invoice still past its deadline, if it still is and was last
     * told about before {@code toldBefore}. Not part of the aggregate and not a new version of it: a reminder changes
     * nothing the invoice says, and must not make the accountant's open screen stale.
     *
     * @return false when the invoice was issued, voided or reminded meanwhile
     */
    boolean markOverdueReminded(UUID id, Instant now, Instant toldBefore);

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

    /**
     * Up to {@code limit} drafts the automatic issuing would take but for a warning that holds them back - originals,
     * never sent, whose warnings are more than a missing e-mail ({@link #findAutoIssuable}'s complement) - and whose
     * accountants were not told of these warnings yet (audit r2 M-2), the earliest dated first.
     */
    List<UUID> findHeldUnannounced(int limit);

    /**
     * Notes that the accountants were told that a draft is held back for the warnings it carries now - the draft read
     * in the same statement, so the reason noted is exactly the one {@link #findHeldUnannounced} compares.
     *
     * @return the warnings noted; empty when the draft is no longer held back, or was told of them before
     */
    Optional<String> markHeldForWarnings(UUID id, Instant now);

    /**
     * Notes that the accountants were told that the automatic issuing was refused for {@code reason} (an error code).
     *
     * @return false when that was noted before: a draft is told of one reason once
     */
    boolean markHeldAnnounced(UUID id, String reason, Instant now);

    /** The next internal invoice code of {@code day}: {@code EINV-yyyyMMdd-nnnn}. */
    String nextCode(java.time.LocalDate day);

    // ---- WF-05 -----------------------------------------------------------------------------------------------------

    /**
     * Takes back the mark of {@link #markReversed} (BE-29): the shipment was delivered after all and the reduction of
     * its failed delivery was voided as {@code DELIVERY_CORRECTED_BACK} in this transaction ({@code tg_einvoice_guard}
     * refuses it otherwise).
     *
     * @return false when it was not marked
     */
    boolean clearReversed(UUID invoiceId);

    /** The shipment's invoice most recently reversed for a failed delivery ({@link #markReversed}), if any. */
    Optional<EInvoice> findReversedOfShipment(UUID shipmentId);

    /**
     * Whether a reduction of a failed delivery naming {@code invoiceId} was voided as {@code UNSENDABLE_REVERSAL}: the
     * accountant reduces it on the provider's portal instead and records that (BE-10).
     */
    boolean hasVoidedUnsendableReversal(UUID invoiceId);

    /** The reduction of a failed delivery naming {@code invoiceId} that is not voided, if any. */
    Optional<EInvoice> findLiveDeliveryFailedReductionOf(UUID invoiceId);

    /**
     * The corrections of an invoice not issued yet - adjustments naming or listing it and replacements of it, drafts,
     * refused or at the provider - oldest first.
     */
    List<EInvoice> findUnissuedCorrectionsOf(UUID invoiceId);

    /** The lines of every adjustment issued against {@code invoiceId} (naming it, not listing it), in order. */
    List<EInvoiceLine> issuedAdjustmentLinesOf(UUID invoiceId);

    /** How many attempts of the invoice the provider refused under {@code rejectionCode} (BE-10: "for good"). */
    int refusalsUnder(UUID invoiceId, String rejectionCode);

    /** Whether a return adjustment drawn up for the order is not issued yet (X-6, Q-16 b: information only). */
    boolean returnAdjustmentUnissued(UUID orderId);

    /**
     * Starts watching a transaction let go without the provider's word (BE-07, Q-5): withdrawn, or refused on the
     * provider's processing report alone. Asked about daily until {@code until}. Twice for one transaction is once.
     */
    void watchTransaction(UUID einvoiceId, String transactionId, String provider, String letGoAs, Instant until);

    /** A transaction watched after it was let go. */
    record WatchedTransaction(UUID einvoiceId, String transactionId, String provider, String letGoAs) {
    }

    /** Up to {@code limit} watched transactions still to ask about now, not asked since {@code askedBefore}. */
    List<WatchedTransaction> findWatchedToAsk(Instant now, Instant askedBefore, int limit);

    /**
     * Whether a transaction of the invoice let go earlier was found issued by the provider after all (BE-07): the
     * delivery already has an invoice in law under it, and no further attempt may be sent.
     */
    boolean watchFound(UUID einvoiceId);

    /** Notes that a watched transaction was asked about at {@code at}. */
    void markWatchAsked(UUID einvoiceId, String transactionId, Instant at);

    /**
     * Records what the provider holds under a watched transaction: it issued it after all.
     *
     * @return false when it was recorded before - the accountants are told once
     */
    boolean markWatchFound(UUID einvoiceId, String transactionId, Instant at, int number, String templateCode,
                           String symbol, String taxAuthorityCode);

    /** Up to {@code limit} invoices at the provider since before {@code submittedBefore} not yet told overdue (BE-08). */
    List<UUID> findResultOverdueUnannounced(Instant submittedBefore, int limit);

    /** Notes that the accountants were told this submission is overdue; false when told before. */
    boolean markResultOverdueAnnounced(UUID id, String transactionId, Instant now);

    /** Notes that the accountants were told the provider numbered this submission under another series (BE-30). */
    boolean markSeriesMismatchAnnounced(UUID id, String transactionId, Instant now);

    /**
     * Notes that the accountants were told an invoice's deadline is near, outside the version (BE-57): the warning
     * says nothing new about the invoice, and must not make the accountant's open screen stale.
     *
     * @return false when it was told before, or is no longer to be issued
     */
    boolean markDueWarned(UUID id, Instant now);

    /** Pieces of a return the shipment's own invoice could not take yet, kept until it is issued (BE-28). */
    record PendingReturnPieces(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber,
                               UUID shipmentId, UUID orderLineId, long pieces) {
    }

    /** Keeps pieces of a return to be taken off the shipment's invoice once it is issued (BE-28). */
    void keepPendingReturn(PendingReturnPieces pieces, Instant now);

    /** The pieces of returns waiting for the shipment's invoice to be issued. */
    List<PendingReturnPieces> findPendingReturnsOfShipment(UUID shipmentId);

    /** The return's pieces were placed (or announced as unplaceable): no longer pending. */
    void settlePendingReturn(UUID returnRequestId, UUID shipmentId, Instant now);
}
