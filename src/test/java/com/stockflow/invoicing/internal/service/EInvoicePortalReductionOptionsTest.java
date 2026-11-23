package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.order.api.DeliveredShipment;
import com.stockflow.order.api.DeliveredShipments;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 audit r2 contract M-10 (decision Q-8, Q-9): "Ghi hoá đơn giảm" is offered on an invoice exactly when the record
 * would be accepted - a replacement whose delivery failed, a return's goods waiting on a replacement, an original whose
 * reduction was voided as unsendable - and never on a replacement of a delivery that stands with no return on it.
 */
class EInvoicePortalReductionOptionsTest {

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final DeliveredShipments shipments = mock(DeliveredShipments.class);
    private final EInvoiceIssuanceSteps steps = mock(EInvoiceIssuanceSteps.class);
    private final EInvoiceProviders providers = mock(EInvoiceProviders.class);
    private final com.stockflow.order.api.ReturnRequestLookup returns =
            mock(com.stockflow.order.api.ReturnRequestLookup.class);
    private final EInvoicePortalReductions reductions = new EInvoicePortalReductions(invoices, shipments, steps,
            providers, returns);

    private EInvoice issuedReplacement() {
        EInvoice original = EInvoiceFixtures.issued(EInvoiceFixtures.draft(), 1);
        EInvoice replacement = EInvoiceFixtures.issued(EInvoiceFixtures.agreed(
                EInvoiceFixtures.replacement(original, 20)), 2);
        when(invoices.findById(replacement.id())).thenReturn(Optional.of(replacement));
        when(invoices.findByIdForUpdate(replacement.id())).thenReturn(Optional.of(replacement));
        return replacement;
    }

    private void deliveredNow(EInvoice invoice) {
        when(shipments.find(invoice.snapshot().orderId(), invoice.snapshot().shipmentId()))
                .thenReturn(Optional.of(mock(DeliveredShipment.class)));
    }

    @Test
    @DisplayName("M-10: a replacement of a delivery that stands, with no return waiting on it - nothing is offered, "
            + "and the record is refused by the same rule")
    void aStandingReplacementOffersNothing() {
        EInvoice replacement = issuedReplacement();
        deliveredNow(replacement);

        EInvoiceIssuance.ExternalReductionOptions options = reductions.applicability(replacement.snapshot());

        assertThat(options.any()).isFalse();
        BusinessException refused = catchThrowableOfType(BusinessException.class, () -> reductions.record(
                replacement.id(), new EInvoicePortalReductions.PortalReduction("1", "C26TSE", 7,
                        EInvoiceFixtures.CORRECTED_ON, "M1", "M1", Instant.now(), null,
                        new EInvoiceProvider.Documents(new byte[] {1}, new byte[] {1}), "portal",
                        CorrectionBasis.DELIVERY_FAILED, null), null));
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("M-10: a replacement whose delivery failed offers DELIVERY_FAILED; one with a return's refunded goods "
            + "waiting on it offers RETURN, naming the return by the id returnRequestId takes")
    void aFailedDeliveryOrAWaitingReturnIsOffered() {
        EInvoice replacement = issuedReplacement();
        assertThat(reductions.applicability(replacement.snapshot()).deliveryFailed()).isTrue();

        deliveredNow(replacement);
        UUID ret = UUID.randomUUID();
        EInvoiceRepository.PendingReturnPieces kept = new EInvoiceRepository.PendingReturnPieces(ret,
                "RMA-20261113-0001", replacement.snapshot().orderId(), replacement.snapshot().orderNumber(),
                replacement.snapshot().shipmentId(), UUID.randomUUID(), 30);
        when(invoices.findPendingReturnsOfShipment(replacement.snapshot().shipmentId()))
                .thenReturn(List.of(kept, kept));

        EInvoiceIssuance.ExternalReductionOptions options = reductions.applicability(replacement.snapshot());
        assertThat(options.deliveryFailed()).isFalse();
        assertThat(options.returns()).containsExactly(new EInvoiceIssuance.ReturnOption(ret, "RMA-20261113-0001"));

        when(invoices.hasAdjustmentForReturn(ret)).thenReturn(true);
        assertThat(reductions.applicability(replacement.snapshot()).any()).as("recorded once").isFalse();
    }

    /** The return adjustment of two cartons the system drew up on {@code original}, voided by the accountant. */
    private EInvoice voidedReturnAdjustment(EInvoice original) {
        EInvoice draft = EInvoiceFixtures.adjustment(original, CorrectionBasis.RETURN, -2);
        draft.voidDraft("the provider of the original is not usable any more: made on its portal", null,
                EInvoiceFixtures.NOW);
        when(invoices.findVoidedReturnAdjustmentsOf(original.id())).thenReturn(List.of(draft));
        UUID ret = draft.snapshot().returnRequestId();
        when(returns.find(ret)).thenReturn(Optional.of(new com.stockflow.order.api.ReturnRequestReference(ret,
                "RMA-20261113-0007", original.orderId(), "SO-20261112-0001", original.customerId(), "COMPLETED",
                false, original.shipmentId())));
        return draft;
    }

    private EInvoicePortalReductions.PortalReduction returnOnPortal(UUID returnRequestId) {
        return new EInvoicePortalReductions.PortalReduction("1", "C26TVT", 9, EInvoiceFixtures.CORRECTED_ON, "M9",
                "L9", Instant.now(), null, new EInvoiceProvider.Documents(new byte[] {1}, new byte[] {1}),
                "made on the old provider's portal", CorrectionBasis.RETURN, returnRequestId);
    }

    @Test
    @DisplayName("audit r3 EINV-2 (Q-8, HĐ-5): a return's adjustment of an original issued through a provider that is "
            + "no longer usable could not be sent and was voided - RETURN is offered naming the return, and the record "
            + "takes off what the voided draft took off, once")
    void aReturnOnAnOriginalOfAnUnusableProviderIsRecorded() {
        EInvoice original = EInvoiceFixtures.issued(EInvoiceFixtures.draft(), 3);
        when(invoices.findById(original.id())).thenReturn(Optional.of(original));
        when(invoices.findByIdForUpdate(original.id())).thenReturn(Optional.of(original));
        when(invoices.correctionsOf(original.id())).thenReturn(EInvoice.Corrections.NONE);
        when(invoices.nextCode(any())).thenReturn("EINV-20261113-0009");
        EInvoice voided = voidedReturnAdjustment(original);
        UUID ret = voided.snapshot().returnRequestId();

        EInvoiceIssuance.ExternalReductionOptions options = reductions.applicability(original.snapshot());
        assertThat(options.deliveryFailed()).isFalse();
        assertThat(options.returns()).containsExactly(new EInvoiceIssuance.ReturnOption(ret, "RMA-20261113-0007"));

        reductions.record(original.id(), returnOnPortal(ret), null);

        var recorded = org.mockito.ArgumentCaptor.forClass(EInvoice.class);
        verify(steps).recordExternalReduction(recorded.capture(), any(), org.mockito.ArgumentMatchers.eq(9), any(),
                any(), any(), any(), any());
        EInvoice.Snapshot reduction = recorded.getValue().snapshot();
        assertThat(reduction.correctionBasis()).isEqualTo(CorrectionBasis.RETURN);
        assertThat(reduction.returnRequestId()).isEqualTo(ret);
        assertThat(reduction.originalEInvoiceId()).isEqualTo(original.id());
        assertThat(reduction.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(-2);
            assertThat(line.orderLineId()).isEqualTo(original.lines().getFirst().orderLineId());
        });

        BusinessException other = catchThrowableOfType(BusinessException.class,
                () -> reductions.record(original.id(), returnOnPortal(UUID.randomUUID()), null));
        assertThat(other.errorCode()).as("a return it was never adjusted for")
                .isEqualTo(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("audit r3 EINV-2: while the original's provider can still send corrections, a voided return "
            + "adjustment is not recorded from the portal - it goes through the provider (one rule with the void of "
            + "an unsendable reduction)")
    void aReturnOnAnOriginalOfAUsableProviderGoesThroughIt() {
        EInvoice original = EInvoiceFixtures.issued(EInvoiceFixtures.draft(), 3);
        when(invoices.findById(original.id())).thenReturn(Optional.of(original));
        when(invoices.findByIdForUpdate(original.id())).thenReturn(Optional.of(original));
        EInvoice voided = voidedReturnAdjustment(original);
        EInvoiceProvider provider = mock(EInvoiceProvider.class);
        when(provider.issuesCorrections()).thenReturn(true);
        when(providers.usable(original.provider())).thenReturn(true);
        when(providers.find(original.provider())).thenReturn(Optional.of(provider));

        assertThat(reductions.applicability(original.snapshot()).any()).isFalse();
        BusinessException refused = catchThrowableOfType(BusinessException.class,
                () -> reductions.record(original.id(), returnOnPortal(voided.snapshot().returnRequestId()), null));
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE);
        verify(steps, never()).recordExternalReduction(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
                any(), any(), any(), any());
    }

    /** A correction of an error taking {@code cartons} cartons off {@code original}, agreed or not, then voided. */
    private EInvoice voidedErrorAdjustment(EInvoice original, int cartons, boolean agreed) {
        EInvoice draft = EInvoiceFixtures.adjustment(original, CorrectionBasis.ERROR, cartons);
        if (agreed) {
            EInvoiceFixtures.agreed(draft);
        }
        draft.voidDraft("the provider of the original is not usable any more: made on its portal", null,
                EInvoiceFixtures.NOW);
        return draft;
    }

    private EInvoicePortalReductions.PortalReduction errorOnPortal(UUID voidedAdjustmentId) {
        return new EInvoicePortalReductions.PortalReduction("1", "C26TVT", 11, EInvoiceFixtures.CORRECTED_ON, "M11",
                "L11", Instant.now(), null, new EInvoiceProvider.Documents(new byte[] {1}, new byte[] {1}),
                "made on the old provider's portal", CorrectionBasis.ERROR, null, voidedAdjustmentId);
    }

    private EInvoice originalOfAnUnusableProvider() {
        EInvoice original = EInvoiceFixtures.issued(EInvoiceFixtures.draft(), 3);
        when(invoices.findById(original.id())).thenReturn(Optional.of(original));
        when(invoices.findByIdForUpdate(original.id())).thenReturn(Optional.of(original));
        when(invoices.correctionsOf(original.id())).thenReturn(EInvoice.Corrections.NONE);
        when(invoices.nextCode(any())).thenReturn("EINV-20261113-0011");
        return original;
    }

    @Test
    @DisplayName("audit r3 EINV-2, the ERROR half: a correction of an error of an original whose provider is no "
            + "longer usable, agreed and voided - ERROR is offered naming the draft, and the record states what the "
            + "draft stated, with its agreement, and is noted against the draft so it is recorded once")
    void anErrorOnAnOriginalOfAnUnusableProviderIsRecorded() {
        EInvoice original = originalOfAnUnusableProvider();
        EInvoice voided = voidedErrorAdjustment(original, -2, true);
        when(invoices.findUnrecordedVoidedErrorAdjustmentsOf(original.id())).thenReturn(List.of(voided));
        EInvoice.Snapshot issued = mock(EInvoice.Snapshot.class);
        UUID recordedId = UUID.randomUUID();
        when(issued.id()).thenReturn(recordedId);
        when(steps.recordExternalReduction(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(),
                any(), any())).thenReturn(issued);

        EInvoiceIssuance.ExternalReductionOptions options = reductions.applicability(original.snapshot());
        assertThat(options.any()).isTrue();
        assertThat(options.returns()).isEmpty();
        assertThat(options.errors()).containsExactly(new EInvoiceIssuance.ErrorOption(voided.id(),
                voided.snapshot().code(), "sai so luong", voided.snapshot().grandTotal()));

        reductions.record(original.id(), errorOnPortal(voided.id()), null);

        var recorded = org.mockito.ArgumentCaptor.forClass(EInvoice.class);
        verify(steps).recordExternalReduction(recorded.capture(), any(), org.mockito.ArgumentMatchers.eq(11), any(),
                any(), any(), any(), any());
        EInvoice.Snapshot reduction = recorded.getValue().snapshot();
        assertThat(reduction.correctionBasis()).isEqualTo(CorrectionBasis.ERROR);
        assertThat(reduction.issuedVia()).isEqualTo(EInvoice.IssuedVia.PORTAL_RECORD);
        assertThat(reduction.originalEInvoiceId()).isEqualTo(original.id());
        assertThat(reduction.returnRequestId()).isNull();
        assertThat(reduction.adjustmentReason()).as("what the agreement corrects").isEqualTo("sai so luong");
        assertThat(reduction.agreementFileKey()).isEqualTo(voided.snapshot().agreementFileKey());
        assertThat(reduction.agreementNumber()).isEqualTo("BB-01/2026");
        assertThat(reduction.grandTotal()).isEqualByComparingTo(voided.snapshot().grandTotal());
        assertThat(reduction.lines()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(-2));
        verify(invoices).notePortalRecordOf(voided.id(), recordedId);

        BusinessException other = catchThrowableOfType(BusinessException.class,
                () -> reductions.record(original.id(), errorOnPortal(UUID.randomUUID()), null));
        assertThat(other.errorCode()).as("a draft that is not one of its voided errors")
                .isEqualTo(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("audit r3 EINV-2, the ERROR half: not offered nor recorded while the provider can still send it, "
            + "without its agreement, or when it raises; voidedAdjustmentId only with ERROR, and ERROR only with it")
    void anErrorIsRecordedOnlyWhenNothingElseCan() {
        EInvoice original = originalOfAnUnusableProvider();
        EInvoice unagreed = voidedErrorAdjustment(original, -1, false);
        EInvoice raising = voidedErrorAdjustment(original, 1, true);
        when(invoices.findUnrecordedVoidedErrorAdjustmentsOf(original.id())).thenReturn(List.of(unagreed, raising));
        assertThat(reductions.applicability(original.snapshot()).any()).as("no agreement; raises").isFalse();
        for (EInvoice voided : List.of(unagreed, raising)) {
            assertThat(catchThrowableOfType(BusinessException.class, () -> reductions.record(original.id(),
                    errorOnPortal(voided.id()), null)).errorCode())
                    .isEqualTo(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE);
        }

        EInvoice agreed = voidedErrorAdjustment(original, -1, true);
        when(invoices.findUnrecordedVoidedErrorAdjustmentsOf(original.id())).thenReturn(List.of(agreed));
        EInvoiceProvider provider = mock(EInvoiceProvider.class);
        when(provider.issuesCorrections()).thenReturn(true);
        when(providers.usable(original.provider())).thenReturn(true);
        when(providers.find(original.provider())).thenReturn(Optional.of(provider));
        assertThat(reductions.applicability(original.snapshot()).any()).as("the provider can send it").isFalse();
        assertThat(catchThrowableOfType(BusinessException.class, () -> reductions.record(original.id(),
                errorOnPortal(agreed.id()), null)).errorCode())
                .isEqualTo(ErrorCode.EINVOICE_EXTERNAL_REDUCTION_NOT_APPLICABLE);

        assertThat(catchThrowableOfType(BusinessException.class, () -> reductions.record(original.id(),
                errorOnPortal(null), null)).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(catchThrowableOfType(BusinessException.class, () -> reductions.record(original.id(),
                new EInvoicePortalReductions.PortalReduction("1", "C26TVT", 12, EInvoiceFixtures.CORRECTED_ON, "M12",
                        "L12", Instant.now(), null, new EInvoiceProvider.Documents(new byte[] {1}, new byte[] {1}),
                        "portal", CorrectionBasis.RETURN, UUID.randomUUID(), agreed.id()), null)).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(steps, never()).recordExternalReduction(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("M-10: an original in force offers nothing - DELIVERY_FAILED only once its own reduction was voided "
            + "as unsendable, RETURN never (a return on an original is adjusted through the provider)")
    void anOriginalOnlyAfterItsReductionWasVoided() {
        EInvoice original = EInvoiceFixtures.issued(EInvoiceFixtures.draft(), 3);
        when(invoices.findById(original.id())).thenReturn(Optional.of(original));
        assertThat(reductions.applicability(original.snapshot()).any()).isFalse();
        verify(invoices, never()).findPendingReturnsOfShipment(any());
    }
}
