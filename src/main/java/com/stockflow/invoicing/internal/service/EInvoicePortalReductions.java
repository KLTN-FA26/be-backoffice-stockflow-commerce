package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.InvoiceSeries;
import com.stockflow.order.api.DeliveredShipments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Records a reduction of a failed delivery that the accountant made on the provider's portal because no provider can
 * send it through this system (WF-05 BE-10, Q-9; decision Q-8: "one command for every case SePay's API cannot do").
 *
 * <p>Two cases of a shipment's invoice, and no other ({@code EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE}) - besides
 * the shipping fee a rest invoice carries beyond what payment keeps, when no reduction drawn up here can take it off
 * (confirmation audit CF-4, CF-10; {@code recordRest}):</p>
 * <ul>
 *   <li><b>An original whose own reduction no provider could send</b>, which the accountant voided as
 *       {@code UNSENDABLE_REVERSAL} ({@link EInvoiceDrafts#voidUnsendableReversal}): the original was issued through a
 *       provider no longer usable here, or one that issues no corrections, or the provider refused it for good. The
 *       original stays reversed meanwhile, so a redelivery takes its own original; this records what reduced it.</li>
 *   <li><b>A replacement whose shipment is not delivered now</b>: no provider adjusts a replacement (TT 91/2026/TT-BTC
 *       Điều 10 khoản 6 điểm a; SePay 422 {@code INVALID_ORIGIN_INVOICE}, sandbox run T-5), so the system drew nothing
 *       up and told the accountants ({@code REPLACEMENT_NOT_REDUCIBLE}). Recorded, it reverses the replacement as the
 *       system's own reduction would, and a redelivery takes a new original.</li>
 * </ul>
 * <p>Either way the reduction takes off what the invoice still invoices - its lines and every adjustment issued against
 * it ({@link EInvoice#whatIsLeft}) - and is an adjustment like any other: {@code DELIVERY_FAILED}, issued, {@code
 * issued_via} PORTAL_RECORD, its files stored and locked for ten years.</p>
 *
 * <p>And, under {@code basis} RETURN with the return it is for, <b>a return's goods on a replacement</b> (Q-9: a
 * return is adjusted, never replaced, and no provider adjusts a replacement): the refunded pieces kept for it when the
 * refund was approved, taken off its lines.</p>
 *
 * <p>Audit r3 EINV-2 (Q-8: "Cùng một lệnh này dùng cho mọi ca SePay không làm được qua API"; HĐ-5): <b>a return's
 * goods on an original no provider can correct any more</b> - issued through a provider no longer usable here, or one
 * that issues no corrections, or that refused the adjustment for good. The system drew the return's adjustment up; it
 * could not be sent ({@code EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE}), so the accountant voided it and made it on the
 * provider's portal. The record takes off what that voided draft took off, as far as the original still holds it -
 * before, only a replacement was taken, and the reduction made in law had no record here: the register still declared
 * the original's full output VAT and the return showed its refund with no adjustment.</p>
 *
 * <p>The same for <b>the correction of an error of such an original</b> (basis ERROR, with {@code voidedAdjustmentId}
 * naming the voided draft): drawn up here and agreed in writing with the buyer, it could not be sent, the accountant
 * voided it and made it on the portal. The record states what the voided draft stated and carries its agreement
 * ({@link EInvoice#recordedErrorReduction}); only a correction that takes off is recorded, and each voided draft once
 * ({@code invoicing.einvoice_portal_record}). An error has no return to key it by, hence the draft's id.</p>
 */
@Component
class EInvoicePortalReductions {

    private final EInvoiceRepository invoices;
    private final DeliveredShipments shipments;
    private final EInvoiceIssuanceSteps steps;
    private final EInvoiceProviders providers;
    private final com.stockflow.order.api.ReturnRequestLookup returns;

    EInvoicePortalReductions(EInvoiceRepository invoices, DeliveredShipments shipments, EInvoiceIssuanceSteps steps,
                             EInvoiceProviders providers, com.stockflow.order.api.ReturnRequestLookup returns) {
        this.invoices = invoices;
        this.shipments = shipments;
        this.steps = steps;
        this.providers = providers;
        this.returns = returns;
    }

    /** What the portal gave the reduction, checked against its XML by the caller. */
    record PortalReduction(String templateCode, String symbol, int number, LocalDate issueDate,
                           String taxAuthorityCode, String lookupCode, Instant signedAt, String sellerTaxCode,
                           EInvoiceProvider.Documents documents, String note, CorrectionBasis basis,
                           UUID returnRequestId, UUID voidedAdjustmentId) {

        PortalReduction(String templateCode, String symbol, int number, LocalDate issueDate, String taxAuthorityCode,
                        String lookupCode, Instant signedAt, String sellerTaxCode, EInvoiceProvider.Documents documents,
                        String note, CorrectionBasis basis, UUID returnRequestId) {
            this(templateCode, symbol, number, issueDate, taxAuthorityCode, lookupCode, signedAt, sellerTaxCode,
                    documents, note, basis, returnRequestId, null);
        }
    }

    /**
     * @throws BusinessException {@code EINVOICE_NOT_FOUND}; {@code EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE} for any
     *         other invoice, or while a reduction of its failed delivery still stands; {@code
     *         EINVOICE_PORTAL_FILE_INVALID} for another seller's XML; {@code VALIDATION_FAILED} for a date before the
     *         invoice's; {@code EINVOICE_CORRECTION_NOT_APPLICABLE} when an adjustment took a line below what it
     *         invoiced; {@code EINVOICE_SERIES_NOT_CONFIGURED} for a template that is not a VAT invoice's
     */
    @Transactional
    public EInvoice.Snapshot record(UUID originalId, PortalReduction portal, UUID by) {
        EInvoice.Snapshot unlocked = invoices.findById(originalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND)).snapshot();
        invoices.lockOrder(unlocked.orderId());
        EInvoice original = invoices.findByIdForUpdate(originalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EINVOICE_NOT_FOUND));
        EInvoice.Snapshot standing = original.snapshot();
        if (restInvoice(standing)) {
            return recordRest(original, standing, portal, by);
        }
        if (!reducible(standing)) {
            throw notApplicable(standing, "it is not an issued invoice of a shipment");
        }
        if (invoices.findLiveDeliveryFailedReductionOf(originalId).isPresent()) {
            throw notApplicable(standing, "a reduction of its failed delivery stands - issue it, or void it as "
                    + "unsendable first");
        }
        // An original - or a replacement whose reduction was drawn up before replacements stopped being adjusted - whose
        // own reduction was voided as unsendable; it stays reversed meanwhile.
        if (portal.basis() == CorrectionBasis.ERROR) {
            return recordError(original, standing, portal, by);
        }
        if (portal.voidedAdjustmentId() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "voidedAdjustmentId: only with basis ERROR, naming the voided correction of an error");
        }
        if (portal.basis() == CorrectionBasis.RETURN) {
            return recordReturn(original, standing, portal, by);
        }
        if (portal.basis() != CorrectionBasis.DELIVERY_FAILED || portal.returnRequestId() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "basis: DELIVERY_FAILED, RETURN with the return it "
                    + "is for (returnRequestId), or ERROR with the voided correction it is for (voidedAdjustmentId)");
        }
        if (!deliveryFailedRecordable(standing)) {
            throw notApplicable(standing, "its reduction was not voided as unsendable, and it is not a replacement of "
                    + "a delivery that failed");
        }
        requireSameSeller(standing, portal);
        List<EInvoiceLine> issuedAdjustments = invoices.issuedAdjustmentLinesOf(originalId);
        List<EInvoiceLine> lines = EInvoice.whatIsLeft(original, issuedAdjustments);
        if (lines.isEmpty()) {
            throw notApplicable(standing, "it invoices nothing any more");
        }
        BigDecimal issuedTotal = issuedAdjustments.stream().map(line -> line.amount().add(line.taxAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        EInvoice reduction = EInvoice.recordedReduction(Identifiers.newId(), invoices.nextCode(portal.issueDate()),
                original, CorrectionBasis.DELIVERY_FAILED, portal.note(), lines, portal.issueDate(), null,
                new EInvoice.Corrections(!issuedAdjustments.isEmpty(), false, issuedTotal));
        return steps.recordExternalReduction(reduction, series(standing, portal), portal.number(), portal.taxAuthorityCode(),
                portal.lookupCode(), portal.signedAt(), portal.documents(), by);
    }

    /**
     * Confirmation audit CF-4, CF-10: the shipping fee a rest invoice carries beyond what payment keeps now (its share
     * was waived after it was issued, Q-19), taken off on the provider's portal because no reduction drawn up here can
     * do it - the rest invoice is a replacement (Q-9), or an original whose reduction the accountant voided as no
     * provider could send it (an old provider's invoice, HĐ-5). Recorded, it takes off the rest invoice's shipping fee
     * line by exactly the share {@link EInvoiceDrafts#portalShareOf} gives it - never the plate fees it also carries -
     * and is counted as a reduction of the rest ({@code shippingFeeReducedOf}): the order's rest is then settled, and it
     * closes. The rest invoice is not marked reversed: it still invoices its plate fees and the shipping fee kept.
     * Before, a replaced rest invoice was no rest invoice at all, and an old provider's one was drawn up a reduction
     * that was voided and drawn up again for ever, the order waiting on it.
     */
    private EInvoice.Snapshot recordRest(EInvoice original, EInvoice.Snapshot standing, PortalReduction portal,
                                         UUID by) {
        if (portal.basis() != CorrectionBasis.DELIVERY_FAILED || portal.returnRequestId() != null
                || portal.voidedAdjustmentId() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "basis: DELIVERY_FAILED for the shipping fee "
                    + "waived on an invoice of the rest of an order's fees, with no returnRequestId or "
                    + "voidedAdjustmentId");
        }
        if (unissuedReductionOf(standing)) {
            throw notApplicable(standing, "a reduction of it is not issued yet - issue it, or void it as unsendable "
                    + "first");
        }
        BigDecimal share = restPortalShare(standing);
        if (share.signum() <= 0) {
            throw notApplicable(standing, "none of the shipping fee it carries is waived and left to the portal");
        }
        requireSameSeller(standing, portal);
        EInvoiceLine line = original.lines().stream()
                .filter(candidate -> candidate.feeKind() == EInvoiceLine.FeeKind.SHIPPING_FEE).findFirst()
                .orElseThrow(() -> notApplicable(standing, "it carries no shipping fee"));
        EInvoiceLine off = line.corrected(Identifiers.newId(), 1, -1, null, line.unit(), share, line.taxRate(),
                line.itemName());
        EInvoice reduction = EInvoice.recordedReduction(Identifiers.newId(), invoices.nextCode(portal.issueDate()),
                original, CorrectionBasis.DELIVERY_FAILED, portal.note(), List.of(off), portal.issueDate(), null,
                invoices.correctionsOf(standing.id()));
        return steps.recordExternalReduction(reduction, series(standing, portal), portal.number(),
                portal.taxAuthorityCode(), portal.lookupCode(), portal.signedAt(), portal.documents(), by);
    }

    /** {@link EInvoiceDrafts#portalShareOf} of the rest invoice as the order's fees stand now; 0 without them. */
    private BigDecimal restPortalShare(EInvoice.Snapshot rest) {
        return shipments.orderFees(rest.orderId(), Instant.now())
                .map(order -> EInvoiceDrafts.portalShareOf(invoices, order, rest.id())).orElse(BigDecimal.ZERO);
    }

    /** A reduction of a failed delivery - or of a waived fee - naming {@code standing} that is not issued yet. */
    private boolean unissuedReductionOf(EInvoice.Snapshot standing) {
        return invoices.findUnissuedCorrectionsOf(standing.id()).stream()
                .anyMatch(correction -> correction.correctionBasis() == CorrectionBasis.DELIVERY_FAILED);
    }

    /**
     * An issued invoice of the rest of an order's fees: it names no shipment, and is the original of the rest or the
     * replacement in force of it.
     */
    private static boolean restInvoice(EInvoice.Snapshot standing) {
        return standing.status() == EInvoiceStatus.ISSUED && standing.type() != EInvoiceType.ADJUSTMENT
                && standing.shipmentId() == null && standing.orderId() != null && standing.reversedAt() == null;
    }

    /**
     * A return's goods on a replacement, reduced on the provider's portal (Q-9; TT 91/2026 Điều 10 khoản 5 điểm c.1 -
     * a return is adjusted, never replaced, and no provider adjusts a replacement): the pieces kept when the refund was
     * approved ({@code EInvoiceCorrections.drawUpForReturn}, {@code EInvoiceReturnUnadjusted} ON_A_REPLACEMENT), taken
     * off the replacement's lines as far as it still holds them, recorded once.
     */
    private EInvoice.Snapshot recordReturn(EInvoice original, EInvoice.Snapshot standing, PortalReduction portal,
                                           UUID by) {
        if (portal.returnRequestId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "returnRequestId: required for a return");
        }
        if (!replacementInForce(standing)) {
            return recordReturnOnOriginal(original, standing, portal, by);
        }
        if (invoices.hasAdjustmentForReturn(portal.returnRequestId())) {
            throw notApplicable(standing, "only a return's goods on a replacement in force, or a return's adjustment "
                    + "of an original no provider could send, are recorded, once");
        }
        List<EInvoiceRepository.PendingReturnPieces> kept = invoices.findPendingReturnsOfShipment(standing.shipmentId())
                .stream().filter(pieces -> pieces.returnRequestId().equals(portal.returnRequestId())).toList();
        if (kept.isEmpty()) {
            throw notApplicable(standing, "no refunded goods of that return wait on it");
        }
        requireSameSeller(standing, portal);
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        Map<UUID, Long> taken = invoices.piecesTakenOff(standing.id());
        List<EInvoiceLine> lines = new ArrayList<>();
        for (EInvoiceRepository.PendingReturnPieces pieces : kept) {
            long wanted = pieces.pieces();
            for (EInvoiceLine line : original.lines()) {
                if (wanted <= 0 || line.kind() != EInvoiceLine.Kind.GOODS
                        || !pieces.orderLineId().equals(line.orderLineId())) {
                    continue;
                }
                long take = Math.min(wanted, line.pieces() - taken.getOrDefault(line.orderLineId(), 0L));
                EInvoiceLine reduction = take <= 0 ? null
                        : EInvoiceCorrections.reduction(line, take, lines.size() + 1, t);
                if (reduction != null) {
                    lines.add(reduction);
                    taken.merge(line.orderLineId(), take, Long::sum);
                    wanted -= take;
                }
            }
        }
        if (lines.isEmpty()) {
            throw notApplicable(standing, "it holds none of those goods any more");
        }
        EInvoice reduction = EInvoice.recordedReduction(Identifiers.newId(), invoices.nextCode(portal.issueDate()),
                original, CorrectionBasis.RETURN, portal.note(), lines, portal.issueDate(), portal.returnRequestId(),
                invoices.correctionsOf(standing.id()));
        EInvoice.Snapshot recorded = steps.recordExternalReduction(reduction, series(standing, portal),
                portal.number(), portal.taxAuthorityCode(), portal.lookupCode(), portal.signedAt(),
                portal.documents(), by);
        invoices.settlePendingReturn(portal.returnRequestId(), standing.shipmentId(), recorded.issuedAt());
        return recorded;
    }

    /**
     * Audit r3 EINV-2: a return's adjustment of an original that no provider could send, voided by the accountant and
     * made on the provider's portal (Q-8, HĐ-5), recorded: what the voided draft took off - the refunded pieces of the
     * return, per order line - as far as the original still holds them, once.
     */
    private EInvoice.Snapshot recordReturnOnOriginal(EInvoice original, EInvoice.Snapshot standing,
                                                     PortalReduction portal, UUID by) {
        EInvoice voided = returnRecordable(standing).stream()
                .filter(draft -> portal.returnRequestId().equals(draft.snapshot().returnRequestId())).findFirst()
                .orElseThrow(() -> notApplicable(standing, "only a return's goods on a replacement in force, or a "
                        + "return's adjustment of an original no provider could send - voided first - are recorded, "
                        + "once"));
        requireSameSeller(standing, portal);
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        Map<UUID, Long> taken = invoices.piecesTakenOff(standing.id());
        List<EInvoiceLine> lines = new ArrayList<>();
        for (EInvoiceLine reduced : voided.lines()) {
            if (reduced.kind() != EInvoiceLine.Kind.GOODS) {
                continue;
            }
            long wanted = -reduced.pieces();
            for (EInvoiceLine line : original.lines()) {
                if (wanted <= 0 || line.kind() != EInvoiceLine.Kind.GOODS
                        || !reduced.orderLineId().equals(line.orderLineId())) {
                    continue;
                }
                long take = Math.min(wanted, line.pieces() - taken.getOrDefault(line.orderLineId(), 0L));
                EInvoiceLine reduction = take <= 0 ? null
                        : EInvoiceCorrections.reduction(line, take, lines.size() + 1, t);
                if (reduction != null) {
                    lines.add(reduction);
                    taken.merge(line.orderLineId(), take, Long::sum);
                    wanted -= take;
                }
            }
        }
        if (lines.isEmpty()) {
            throw notApplicable(standing, "it holds none of those goods any more");
        }
        EInvoice reduction = EInvoice.recordedReduction(Identifiers.newId(), invoices.nextCode(portal.issueDate()),
                original, CorrectionBasis.RETURN, portal.note(), lines, portal.issueDate(), portal.returnRequestId(),
                invoices.correctionsOf(standing.id()));
        return steps.recordExternalReduction(reduction, series(standing, portal), portal.number(),
                portal.taxAuthorityCode(), portal.lookupCode(), portal.signedAt(), portal.documents(), by);
    }

    /**
     * Audit r3 EINV-2, the ERROR half: a correction of an error of an original that no provider could send, voided by
     * the accountant and made on the provider's portal (Q-8, HĐ-5), recorded as the voided draft stated it, with its
     * agreement, once. Recorded, it takes off what the original invoices - so the pieces it takes off must still be
     * there: a correction agreed in writing is not trimmed to fit, it is refused.
     */
    private EInvoice.Snapshot recordError(EInvoice original, EInvoice.Snapshot standing, PortalReduction portal,
                                          UUID by) {
        if (portal.voidedAdjustmentId() == null || portal.returnRequestId() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "voidedAdjustmentId: required for an error, and no returnRequestId");
        }
        EInvoice voided = errorRecordable(standing).stream()
                .filter(draft -> draft.id().equals(portal.voidedAdjustmentId())).findFirst()
                .orElseThrow(() -> notApplicable(standing, "only a voided correction of an error of an original no "
                        + "provider could send, that takes off and carries its agreement, is recorded, once"));
        requireSameSeller(standing, portal);
        Map<UUID, Long> taken = invoices.piecesTakenOff(standing.id());
        Map<UUID, Long> held = new java.util.HashMap<>();
        for (EInvoiceLine line : original.lines()) {
            if (line.kind() == EInvoiceLine.Kind.GOODS) {
                held.merge(line.orderLineId(), line.pieces(), Long::sum);
            }
        }
        Map<UUID, Long> wanted = new java.util.HashMap<>();
        for (EInvoiceLine line : voided.lines()) {
            if (line.kind() == EInvoiceLine.Kind.GOODS && line.quantity() < 0) {
                wanted.merge(line.orderLineId(), -line.pieces(), Long::sum);
            }
        }
        wanted.forEach((orderLine, pieces) -> {
            if (pieces > held.getOrDefault(orderLine, 0L) - taken.getOrDefault(orderLine, 0L)) {
                throw notApplicable(standing, "it holds fewer of those goods than the correction takes off");
            }
        });
        EInvoice reduction = EInvoice.recordedErrorReduction(Identifiers.newId(),
                invoices.nextCode(portal.issueDate()), original, voided, voided.snapshot().adjustmentReason(),
                portal.issueDate(), invoices.correctionsOf(standing.id()));
        EInvoice.Snapshot recorded = steps.recordExternalReduction(reduction, series(standing, portal),
                portal.number(), portal.taxAuthorityCode(), portal.lookupCode(), portal.signedAt(),
                portal.documents(), by);
        invoices.notePortalRecordOf(voided.id(), recorded.id());
        return recorded;
    }

    /**
     * The voided corrections of an error of an original in force whose provider can correct it no more (audit r3
     * EINV-2), not recorded yet, that {@link EInvoice#recordedErrorReduction} would take: each takes off and carries
     * its written agreement. By the rule {@link #returnRecordable} applies to returns.
     */
    private List<EInvoice> errorRecordable(EInvoice.Snapshot standing) {
        if (standing.type() != EInvoiceType.ORIGINAL || standing.reversedAt() != null) {
            return List.of();
        }
        return invoices.findUnrecordedVoidedErrorAdjustmentsOf(standing.id()).stream()
                .filter(voided -> voided.snapshot().agreementFileKey() != null
                        && EInvoice.reducesOnly(voided.lines())
                        && !EInvoiceDrafts.correctionSendable(providers, invoices, standing, voided.snapshot()))
                .toList();
    }

    /**
     * The voided return adjustments of an original in force whose provider can correct it no more (audit r3 EINV-2), one
     * per return that has no live adjustment: what may be recorded from the portal against it. Empty for anything else,
     * and while the provider could still send them - the reduction then goes through it, as the void of a failed
     * delivery's reduction requires ({@code EInvoiceDrafts#voidUnsendableReversal}): one rule, so the two never drift.
     */
    private List<EInvoice> returnRecordable(EInvoice.Snapshot standing) {
        if (standing.type() != EInvoiceType.ORIGINAL || standing.reversedAt() != null) {
            return List.of();
        }
        return invoices.findVoidedReturnAdjustmentsOf(standing.id()).stream()
                .filter(voided -> !EInvoiceDrafts.correctionSendable(providers, invoices, standing, voided.snapshot()))
                .toList();
    }

    /**
     * What may be recorded against the invoice now (audit r2 M-10: the screen offered "Ghi hoá đơn giảm" on every
     * replacement in force, and anything but a failed delivery or a return waiting on it answered 409), by the same
     * checks {@link #record} makes, read without the order's lock: a failed delivery's reduction while one is recordable
     * and the invoice still invoices something; the returns whose refunded goods wait on a replacement in force and
     * were not recorded yet. Whether the invoice still holds a return's goods is left to {@link #record}, which says so.
     */
    EInvoiceIssuance.ExternalReductionOptions applicability(EInvoice.Snapshot standing) {
        if (restInvoice(standing)) {
            // Confirmation audit CF-4, CF-10: the waived shipping fee of a rest invoice only the portal can take off.
            boolean waived = !unissuedReductionOf(standing) && restPortalShare(standing).signum() > 0;
            return waived ? new EInvoiceIssuance.ExternalReductionOptions(true, List.of(), List.of())
                    : EInvoiceIssuance.ExternalReductionOptions.NONE;
        }
        if (!reducible(standing) || invoices.findLiveDeliveryFailedReductionOf(standing.id()).isPresent()) {
            return EInvoiceIssuance.ExternalReductionOptions.NONE;
        }
        boolean deliveryFailed = deliveryFailedRecordable(standing) && invoices.findById(standing.id())
                .map(original -> !EInvoice.whatIsLeft(original, invoices.issuedAdjustmentLinesOf(standing.id()))
                        .isEmpty()).orElse(false);
        List<EInvoiceIssuance.ReturnOption> options = new ArrayList<>();
        if (replacementInForce(standing)) {
            java.util.Set<UUID> seen = new java.util.HashSet<>();
            for (EInvoiceRepository.PendingReturnPieces pieces : invoices.findPendingReturnsOfShipment(
                    standing.shipmentId())) {
                if (seen.add(pieces.returnRequestId()) && !invoices.hasAdjustmentForReturn(pieces.returnRequestId())) {
                    options.add(new EInvoiceIssuance.ReturnOption(pieces.returnRequestId(), pieces.rmaNumber()));
                }
            }
        }
        // Audit r3 EINV-2: a return's adjustment of an original its provider could not send, voided to be made on the
        // portal.
        for (EInvoice voided : returnRecordable(standing)) {
            UUID returnRequestId = voided.snapshot().returnRequestId();
            options.add(new EInvoiceIssuance.ReturnOption(returnRequestId, returns.find(returnRequestId)
                    .map(com.stockflow.order.api.ReturnRequestReference::rmaNumber).orElse(null)));
        }
        // Audit r3 EINV-2, the ERROR half: a correction of an error of such an original, voided to be made on the portal.
        List<EInvoiceIssuance.ErrorOption> errors = errorRecordable(standing).stream()
                .map(voided -> new EInvoiceIssuance.ErrorOption(voided.id(), voided.snapshot().code(),
                        voided.snapshot().adjustmentReason(), voided.snapshot().grandTotal())).toList();
        return new EInvoiceIssuance.ExternalReductionOptions(deliveryFailed, options, errors);
    }

    /** An issued invoice of a shipment that is not itself an adjustment: the only kind anything is recorded against. */
    private static boolean reducible(EInvoice.Snapshot standing) {
        return standing.status() == EInvoiceStatus.ISSUED && standing.type() != EInvoiceType.ADJUSTMENT
                && standing.shipmentId() != null;
    }

    /**
     * A failed delivery's reduction is recorded for an original whose own was voided as unsendable, or a replacement
     * in force whose shipment is not delivered now.
     */
    private boolean deliveryFailedRecordable(EInvoice.Snapshot standing) {
        boolean unsendableVoided = standing.reversedAt() != null
                && invoices.hasVoidedUnsendableReversal(standing.id());
        boolean replacementFailed = replacementInForce(standing)
                && shipments.find(standing.orderId(), standing.shipmentId()).isEmpty();
        return unsendableVoided || replacementFailed;
    }

    private static boolean replacementInForce(EInvoice.Snapshot standing) {
        return standing.type() == EInvoiceType.REPLACEMENT && standing.reversedAt() == null;
    }

    private static InvoiceSeries series(EInvoice.Snapshot standing, PortalReduction portal) {
        return new InvoiceSeries(standing.provider(), portal.templateCode(), portal.symbol(),
                portal.issueDate().getYear());
    }

    private static void requireSameSeller(EInvoice.Snapshot standing, PortalReduction portal) {
        if (portal.sellerTaxCode() != null && !portal.sellerTaxCode().equals(standing.seller().taxCode())) {
            throw new BusinessException(ErrorCode.EINVOICE_PORTAL_FILE_INVALID,
                    "The XML is of seller %s, not %s".formatted(portal.sellerTaxCode(), standing.seller().taxCode()));
        }
        if (portal.issueDate().isBefore(standing.issueDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "issueDate: not before the invoice it reduces (%s)"
                    .formatted(standing.issueDate()));
        }
    }

    private static BusinessException notApplicable(EInvoice.Snapshot invoice, String why) {
        return new BusinessException(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE,
                "No reduction made on the provider's portal is recorded against %s: %s".formatted(invoice.code(), why));
    }
}
