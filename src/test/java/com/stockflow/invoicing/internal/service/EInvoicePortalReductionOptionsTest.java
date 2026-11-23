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
