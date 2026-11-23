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
    private final EInvoicePortalReductions reductions = new EInvoicePortalReductions(invoices, shipments,
            mock(EInvoiceIssuanceSteps.class));

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
