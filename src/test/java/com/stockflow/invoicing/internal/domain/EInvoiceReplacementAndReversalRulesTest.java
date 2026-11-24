package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF-05 BE-09 (decision Q-9), BE-11, BE-36, BE-10: what may correct an issued invoice once it was replaced, adjusted
 * or reduced for a failed delivery, and how the reduction of a failed delivery is voided.
 */
class EInvoiceReplacementAndReversalRulesTest {

    private static void assertRefused(ThrowingCallable call, ErrorCode expected) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(expected);
    }

    /** An issued replacement of an issued original. */
    private static EInvoice issuedReplacement() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoice replacement = EInvoiceFixtures.agreed(EInvoiceFixtures.replacement(original, 20));
        replacement.submit(EInvoiceFixtures.SERIES, null, EInvoiceFixtures.NOW);
        replacement.issued(2, "M2", "L2", EInvoiceFixtures.NOW, "r.xml", "r.pdf", EInvoiceFixtures.NOW);
        return replacement;
    }

    @Test
    @DisplayName("BE-09 (Q-9): a replacement is never adjusted - an error on it is replaced again "
            + "(EINVOICE_CORRECTION_FORM_FIXED); a return, a trade discount or a failed delivery on it is refused at "
            + "draw-up (EINVOICE_REPLACEMENT_NOT_ADJUSTABLE), never a draft SePay refuses for good (sandbox T-5)")
    void aReplacementIsNeverAdjusted() {
        EInvoice replacement = issuedReplacement();
        assertRefused(() -> EInvoiceFixtures.adjustment(replacement, CorrectionBasis.ERROR, -1),
                ErrorCode.EINVOICE_CORRECTION_FORM_FIXED);
        assertRefused(() -> EInvoiceFixtures.adjustment(replacement, CorrectionBasis.RETURN, -1),
                ErrorCode.EINVOICE_REPLACEMENT_NOT_ADJUSTABLE);
        assertRefused(() -> EInvoiceFixtures.adjustment(replacement, CorrectionBasis.TRADE_DISCOUNT, -1),
                ErrorCode.EINVOICE_REPLACEMENT_NOT_ADJUSTABLE);
        assertRefused(() -> EInvoice.drawUpDeliveryReversal(UUID.randomUUID(), "EINV-1", replacement, "giao hong",
                EInvoiceFixtures.CORRECTED_ON, EInvoice.Corrections.NONE), ErrorCode.EINVOICE_REPLACEMENT_NOT_ADJUSTABLE);
        // A trade discount of the buyer's other invoices does not list a replacement either.
        EInvoice other = EInvoiceFixtures.issuedFor(replacement.customerId(), 9);
        assertRefused(() -> EInvoice.drawUpAdjustment(UUID.randomUUID(), "EINV-2", other,
                CorrectionBasis.TRADE_DISCOUNT, "chiet khau", other.buyer(), EInvoiceFixtures.cartons(other, -1),
                EInvoiceFixtures.CORRECTED_ON, null, List.of(replacement), EInvoice.Corrections.NONE),
                ErrorCode.EINVOICE_REPLACEMENT_NOT_ADJUSTABLE);
        // Replaced again: allowed (sandbox X-2).
        assertThat(EInvoiceFixtures.replacement(replacement, 19).snapshot().type()).isEqualTo(EInvoiceType.REPLACEMENT);
    }

    @Test
    @DisplayName("BE-11: an invoice adjusted before its delivery failed is reduced by what it still invoices - its "
            + "lines and the issued adjustments, line by line - so the three come to nothing")
    void anAdjustedInvoiceIsReducedByWhatIsLeft() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        List<EInvoiceLine> returned = EInvoiceFixtures.cartons(original, -5);
        BigDecimal adjustedBy = returned.stream().map(line -> line.amount().add(line.taxAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        original.markAdjusted();

        EInvoice reversal = EInvoice.drawUpDeliveryReversal(UUID.randomUUID(), "EINV-3", original, "giao hong",
                EInvoiceFixtures.CORRECTED_ON, new EInvoice.Corrections(true, false, adjustedBy), returned);

        EInvoice.Snapshot s = reversal.snapshot();
        assertThat(s.correctionBasis()).isEqualTo(CorrectionBasis.DELIVERY_FAILED);
        EInvoiceLine goods = s.lines().stream().filter(line -> line.kind() == EInvoiceLine.Kind.GOODS).findFirst()
                .orElseThrow();
        assertThat(goods.quantity()).as("20 invoiced, 5 returned: 15 taken off").isEqualTo(-15);
        assertThat(s.grandTotal().add(original.grandTotal()).add(adjustedBy)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("BE-11: an adjustment that took a line below what was invoiced (a price taken down) is not reduced by "
            + "the system; nothing left to take off is not reduced either")
    void whatCannotBeReducedByWholeLinesIsRefused() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        assertRefused(() -> EInvoice.whatIsLeft(original, EInvoiceFixtures.cartons(original, -21)),
                ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
        List<EInvoiceLine> everything = new java.util.ArrayList<>(EInvoiceFixtures.cartons(original, -20));
        EInvoiceLine plate = original.lines().get(1);
        everything.add(plate.corrected(UUID.randomUUID(), 2, -plate.quantity(), plate.unitsPerCarton(), plate.unit(),
                plate.unitPrice(), plate.taxRate(), plate.itemName()));
        assertThat(EInvoice.whatIsLeft(original, everything)).isEmpty();
    }

    /** {@code pieces} loose pieces of the goods line taken off at the piece's price, as a return takes them. */
    private static EInvoiceLine loosePieces(EInvoice invoice, int pieces) {
        EInvoiceLine goods = invoice.lines().getFirst();
        return goods.corrected(UUID.randomUUID(), 1, -pieces, null, "Cai",
                goods.unitPrice().divide(BigDecimal.valueOf(goods.unitsPerCarton())), goods.taxRate(),
                goods.itemName());
    }

    @Test
    @DisplayName("BE-11: goods returned in loose pieces are counted in pieces - what is left comes off as cartons when "
            + "it is whole cartons, else as pieces; loose returns that took the whole line leave nothing (no line of "
            + "quantity 0)")
    void loosePiecesAreCountedInPieces() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoiceLine plate = original.lines().get(1);
        EInvoiceLine noPlate = plate.corrected(UUID.randomUUID(), 2, -1, null, plate.unit(), plate.unitPrice(),
                plate.taxRate(), plate.itemName());

        // 20 cartons of 50 = 1000 pieces; 60 loose pieces back: 940 pieces left, not whole cartons.
        List<EInvoiceLine> left = EInvoice.whatIsLeft(original, List.of(loosePieces(original, 60), noPlate));
        assertThat(left).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(-940);
            assertThat(line.unitsPerCarton()).isNull();
            assertThat(line.unitPrice()).isEqualByComparingTo("2000");
        });

        // 60 + 440 back: 500 pieces left = 10 whole cartons, taken off as cartons at the carton's price.
        left = EInvoice.whatIsLeft(original, List.of(loosePieces(original, 60), loosePieces(original, 440), noPlate));
        assertThat(left).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(-10);
            assertThat(line.unitsPerCarton()).isEqualTo(50);
        });

        // Every piece came back loose, in two returns: nothing is left, and nothing throws.
        assertThat(EInvoice.whatIsLeft(original, List.of(loosePieces(original, 600), loosePieces(original, 400),
                noPlate))).isEmpty();

        // The reduction of what is left, with the loose returns, comes to nothing with the original.
        List<EInvoiceLine> returned = List.of(loosePieces(original, 60), noPlate);
        BigDecimal adjustedBy = returned.stream().map(line -> line.amount().add(line.taxAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        EInvoice reversal = EInvoice.drawUpDeliveryReversal(UUID.randomUUID(), "EINV-8", original, "giao hong",
                EInvoiceFixtures.CORRECTED_ON, new EInvoice.Corrections(true, false, adjustedBy), returned);
        assertThat(reversal.snapshot().grandTotal().add(original.grandTotal()).add(adjustedBy))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("BE-36: an invoice reduced in full for a failed delivery is not corrected again "
            + "(EINVOICE_NOT_IN_FORCE): no adjustment, no replacement, no second reduction")
    void aReversedInvoiceIsNotCorrectedAgain() {
        EInvoice.Snapshot issued = EInvoiceFixtures.in(EInvoiceStatus.ISSUED).snapshot();
        EInvoice reversed = EInvoice.restore(withReversedAt(issued));
        assertRefused(() -> EInvoiceFixtures.adjustment(reversed, CorrectionBasis.ERROR, 1),
                ErrorCode.EINVOICE_NOT_IN_FORCE);
        assertRefused(() -> EInvoiceFixtures.replacement(reversed, 20), ErrorCode.EINVOICE_NOT_IN_FORCE);
        assertRefused(() -> EInvoice.drawUpDeliveryReversal(UUID.randomUUID(), "EINV-4", reversed, "giao hong",
                EInvoiceFixtures.CORRECTED_ON, EInvoice.Corrections.NONE), ErrorCode.EINVOICE_NOT_IN_FORCE);
    }

    @Test
    @DisplayName("BE-10, BE-29: the reduction of a failed delivery is voided only under its code; no other draft takes "
            + "a code")
    void theReductionIsVoidedOnlyUnderItsCode() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoice reduction = EInvoice.drawUpDeliveryReversal(UUID.randomUUID(), "EINV-5", original, "giao hong",
                EInvoiceFixtures.CORRECTED_ON, EInvoice.Corrections.NONE);
        assertRefused(() -> reduction.voidDraft("khong gui duoc", null, EInvoiceFixtures.NOW),
                ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
        reduction.voidDraft("khong gui duoc", EInvoice.VoidReasonCode.UNSENDABLE_REVERSAL, UUID.randomUUID(),
                EInvoiceFixtures.NOW);
        assertThat(reduction.snapshot().status()).isEqualTo(EInvoiceStatus.VOIDED_DRAFT);
        assertThat(reduction.snapshot().voidReasonCode()).isEqualTo(EInvoice.VoidReasonCode.UNSENDABLE_REVERSAL);

        EInvoice draft = EInvoiceFixtures.draft();
        assertRefused(() -> draft.voidDraft("nham", EInvoice.VoidReasonCode.DELIVERY_CORRECTED_BACK, null,
                EInvoiceFixtures.NOW), ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("Q-8: a reduction made on the provider's portal is recorded against a replacement or a reversed "
            + "original as an adjustment marked PORTAL_RECORD; it only takes off")
    void aPortalReductionIsMarked() {
        EInvoice replacement = issuedReplacement();
        EInvoice recorded = EInvoice.recordedReduction(UUID.randomUUID(), "EINV-6", replacement,
                CorrectionBasis.DELIVERY_FAILED, "giam tren cong", EInvoice.whatIsLeft(replacement, List.of()),
                EInvoiceFixtures.CORRECTED_ON, null, EInvoice.Corrections.NONE);
        assertThat(recorded.issuedVia()).isEqualTo(EInvoice.IssuedVia.PORTAL_RECORD);
        assertThat(recorded.snapshot().grandTotal().add(replacement.grandTotal())).isEqualByComparingTo(BigDecimal.ZERO);
        assertRefused(() -> EInvoice.recordedReduction(UUID.randomUUID(), "EINV-7", replacement,
                CorrectionBasis.DELIVERY_FAILED, "tang", EInvoiceFixtures.cartons(replacement, 1),
                EInvoiceFixtures.CORRECTED_ON, null, EInvoice.Corrections.NONE),
                ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
    }

    private static EInvoice.Snapshot withReversedAt(EInvoice.Snapshot s) {
        return new EInvoice.Snapshot(s.id(), s.code(), s.type(), s.status(), s.adjusted(), s.orderId(),
                s.orderNumber(), s.contractNumber(), s.shipmentId(), s.shipmentCode(), s.deliveryBatchId(),
                s.customerId(), s.originalEInvoiceId(), s.provider(), s.attempt(), s.transactionId(), s.templateCode(),
                s.symbol(), s.invoiceNumber(), s.taxAuthorityCode(), s.lookupCode(), s.issueDate(), s.issueDueAt(),
                s.dueWarnedAt(), s.signedAt(), s.seller(), s.buyer(), s.currency(), s.paymentMethod(), s.lines(),
                s.subtotal(), s.taxTotal(), s.grandTotal(), s.amountInWords(), s.warnings(), s.adjustmentReason(),
                s.agreementFileKey(), s.xmlFileKey(), s.pdfFileKey(), s.rejectionCode(), s.rejectionMessage(),
                s.submittedAt(), s.submittedBy(), s.issuedAt(), s.voidedAt(), s.voidedBy(), s.voidReason(),
                s.version(), s.correctionBasis(), s.agreementNumber(), s.agreementDate(), s.returnRequestId(),
                s.listedInvoiceIds(), s.deliveryFailedAt(), s.deliveredOn(), EInvoiceFixtures.NOW, s.issuedVia(),
                s.portalRecordedBy(), s.portalRecordedAt(), s.voidReasonCode());
    }
}
