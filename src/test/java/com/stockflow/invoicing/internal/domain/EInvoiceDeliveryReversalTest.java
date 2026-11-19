package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WF-05 HĐ-3 (kltn-docs 21 §4.4, 09 §4.3 step 5): the full reduction of an issued original whose delivery was
 * corrected to a failure - every line taken off, the two together nothing, sent without an agreement (one may be
 * attached as evidence), and never voided.
 */
class EInvoiceDeliveryReversalTest {

    private static void assertRefused(ThrowingCallable call, ErrorCode expected) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(expected);
    }

    private static EInvoice reversalOf(EInvoice original, EInvoice.Corrections corrections) {
        return EInvoice.drawUpDeliveryReversal(UUID.randomUUID(), "EINV-20261113-0009", original,
                "Giao khong thanh cong", EInvoiceFixtures.CORRECTED_ON, corrections);
    }

    @Test
    @DisplayName("every line of the original taken off at its own quantity, price and rate: the invoice and its "
            + "reduction come to nothing, goods and fees alike")
    void reducesTheWholeInvoice() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);

        EInvoice reversal = reversalOf(original, EInvoice.Corrections.NONE);

        EInvoice.Snapshot s = reversal.snapshot();
        assertThat(s.type()).isEqualTo(EInvoiceType.ADJUSTMENT);
        assertThat(s.status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(s.correctionBasis()).isEqualTo(CorrectionBasis.DELIVERY_FAILED);
        assertThat(s.originalEInvoiceId()).isEqualTo(original.id());
        assertThat(s.shipmentId()).isEqualTo(original.snapshot().shipmentId());
        assertThat(s.lines()).hasSameSizeAs(original.lines());
        for (int i = 0; i < s.lines().size(); i++) {
            EInvoiceLine taken = s.lines().get(i);
            EInvoiceLine line = original.lines().get(i);
            assertThat(taken.quantity()).isEqualTo(-line.quantity());
            assertThat(taken.unitPrice()).isEqualByComparingTo(line.unitPrice());
            assertThat(taken.taxRate()).isEqualByComparingTo(line.taxRate());
            assertThat(taken.kind()).isEqualTo(line.kind());
            assertThat(taken.amount().add(line.amount())).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(taken.taxAmount().add(line.taxAmount())).isEqualByComparingTo(BigDecimal.ZERO);
        }
        assertThat(s.grandTotal().add(original.snapshot().grandTotal())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.issueDate()).as("dated the day it is drawn up").isEqualTo(EInvoiceFixtures.CORRECTED_ON);
    }

    @Test
    @DisplayName("sent without an agreement, which it may still carry as evidence; never voided")
    void noAgreementNeededAndNeverVoided() {
        CorrectionBasis basis = CorrectionBasis.DELIVERY_FAILED;
        assertThat(basis.requiresAgreement()).isFalse();
        assertThat(basis.acceptsAgreement()).isTrue();
        assertThat(basis.decreaseOnly()).isTrue();
        assertThat(basis.systemOnly()).isTrue();
        assertThat(CorrectionBasis.ERROR.systemOnly()).isFalse();
        assertThat(CorrectionBasis.RETURN.acceptsAgreement()).isFalse();

        EInvoice reversal = reversalOf(EInvoiceFixtures.in(EInvoiceStatus.ISSUED), EInvoice.Corrections.NONE);
        assertRefused(() -> reversal.voidDraft("not wanted", UUID.randomUUID(), EInvoiceFixtures.NOW),
                ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
        assertThat(reversal.status()).isEqualTo(EInvoiceStatus.DRAFT);

        EInvoiceFixtures.agreed(reversal);
        assertThat(reversal.snapshot().agreementNumber()).as("evidence accepted").isEqualTo("BB-01/2026");

        EInvoice bare = reversalOf(EInvoiceFixtures.in(EInvoiceStatus.ISSUED), EInvoice.Corrections.NONE);
        bare.submit(EInvoiceFixtures.SERIES, null, EInvoiceFixtures.NOW);
        assertThat(bare.status()).as("no agreement asked for").isEqualTo(EInvoiceStatus.SUBMITTING);
    }

    @Test
    @DisplayName("only an issued invoice of a shipment that nothing corrects yet: not issued, already adjusted, being "
            + "corrected, or an adjustment - the accountant's to reduce by hand")
    void onlyAnUncorrectedIssuedOriginal() {
        assertRefused(() -> reversalOf(EInvoiceFixtures.in(EInvoiceStatus.DRAFT), EInvoice.Corrections.NONE),
                ErrorCode.EINVOICE_NOT_IN_FORCE);
        EInvoice adjusted = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        adjusted.markAdjusted();
        assertRefused(() -> reversalOf(adjusted, EInvoice.Corrections.NONE),
                ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
        assertRefused(() -> reversalOf(EInvoiceFixtures.in(EInvoiceStatus.ISSUED),
                new EInvoice.Corrections(true, false, new BigDecimal("-100"))),
                ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
        EInvoice adjustment = EInvoiceFixtures.issued(EInvoiceFixtures.agreed(EInvoiceFixtures.adjustment(
                EInvoiceFixtures.in(EInvoiceStatus.ISSUED), CorrectionBasis.ERROR, -1)), 3);
        assertRefused(() -> reversalOf(adjustment, EInvoice.Corrections.NONE),
                ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("WF-05 review, defect 5: the replacement standing for the shipment is reduced in full like an "
            + "original - an adjustment of the replacement, basis DELIVERY_FAILED, no agreement")
    void aReplacementInForceIsReducedToo() {
        EInvoice replacement = EInvoiceFixtures.issued(EInvoiceFixtures.agreed(EInvoiceFixtures.replacement(
                EInvoiceFixtures.in(EInvoiceStatus.ISSUED), 18)), 2);

        EInvoice.Snapshot s = reversalOf(replacement, EInvoice.Corrections.NONE).snapshot();

        assertThat(s.type()).isEqualTo(EInvoiceType.ADJUSTMENT);
        assertThat(s.correctionBasis()).isEqualTo(CorrectionBasis.DELIVERY_FAILED);
        assertThat(s.originalEInvoiceId()).as("it adjusts the replacement, the invoice in force")
                .isEqualTo(replacement.id());
        assertThat(s.shipmentId()).isEqualTo(replacement.snapshot().shipmentId());
        assertThat(s.agreementFileKey()).isNull();
        assertThat(s.grandTotal().add(replacement.snapshot().grandTotal())).isEqualByComparingTo(BigDecimal.ZERO);
        assertRefused(() -> reversalOf(replacement, new EInvoice.Corrections(false, true, BigDecimal.ZERO)),
                ErrorCode.EINVOICE_CORRECTION_NOT_APPLICABLE);
    }
}
