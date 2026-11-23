package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.FeeAllocation;
import com.stockflow.order.api.DeliveredShipment;
import com.stockflow.order.api.DeliveredShipments;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.OrderShippingFee;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WF05-E0 L11: an order does not complete while part of its shipping fee is owed on no invoice at all - the draft of the
 * rest was refused when the order's remainder was closed, so there is no unissued invoice to wait for.
 */
class DeliveryInvoicesAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z");
    private static final UUID ORDER = UUID.randomUUID();

    private final EInvoiceQueries queries = mock(EInvoiceQueries.class);
    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final DeliveredShipments shipments = mock(DeliveredShipments.class);
    private final DeliveryInvoicesAdapter adapter = new DeliveryInvoicesAdapter(queries, invoices, shipments,
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** 3,000 pieces delivered and invoiced, carrying {@code shippingInvoiced} of a 100,000 fee. */
    private void anOrderWhoseInvoicesCarry(String shippingInvoiced) {
        when(queries.shippingFeeInvoiceUnissued(ORDER)).thenReturn(false);
        when(shipments.shippingFee(any(), any())).thenReturn(Optional.of(new OrderShippingFee(ORDER, "SO-1",
                UUID.randomUUID(), "HD-1", "VND", new DeliveredShipment.Fee(FeeKind.SHIPPING_FEE, null,
                new BigDecimal("100000"), new BigDecimal("10.00"), new BigDecimal("10.00")), 3000, NOW)));
        when(invoices.invoicedOfOrder(ORDER))
                .thenReturn(new FeeAllocation.Invoiced(3000, new BigDecimal(shippingInvoiced), Set.of()));
    }

    @Test
    @DisplayName("E0 L11: a fee rest no invoice carries keeps the order waiting, though no invoice is unissued")
    void aRestOnNoInvoiceIsUnissued() {
        anOrderWhoseInvoicesCarry("60000");

        assertThat(adapter.shippingFeeUnissued(ORDER)).isTrue();
    }

    @Test
    @DisplayName("the whole fee invoiced: nothing to wait for")
    void theWholeFeeInvoiced() {
        anOrderWhoseInvoicesCarry("100000");

        assertThat(adapter.shippingFeeUnissued(ORDER)).isFalse();
    }

    @Test
    @DisplayName("WF-05 Q-19: a share of the fee waived for a shipment that came back is owed by nobody - the order "
            + "does not wait for it; X-6: an unissued return adjustment is reported as it stands")
    void aWaivedShareIsNotWaitedFor() {
        when(queries.shippingFeeInvoiceUnissued(ORDER)).thenReturn(false);
        // 2,000 of 3,000 pieces delivered and invoiced with 66,667; the third shipment came back: payment waived
        // 36,667 of the 110,000 with VAT, so 100,000 - round(100,000 x 36,667 / 110,000) = 66,666 is owed - which
        // the invoices carry already (a dong of the pieces' rounding over it): no rest is waited for.
        when(shipments.shippingFee(any(), any())).thenReturn(Optional.of(new OrderShippingFee(ORDER, "SO-1",
                UUID.randomUUID(), "HD-1", "VND", new DeliveredShipment.Fee(FeeKind.SHIPPING_FEE, null,
                new BigDecimal("100000"), new BigDecimal("10.00"), new BigDecimal("10.00")), 2000, NOW,
                java.util.List.of(), new com.stockflow.order.api.ShippingFeeWaiver(new BigDecimal("36667"),
                new BigDecimal("110000")))));
        when(invoices.invoicedOfOrder(ORDER))
                .thenReturn(new FeeAllocation.Invoiced(2000, new BigDecimal("66667"), Set.of()));

        assertThat(adapter.shippingFeeUnissued(ORDER)).isFalse();

        when(invoices.returnAdjustmentUnissued(ORDER)).thenReturn(true);
        assertThat(adapter.returnAdjustmentUnissued(ORDER)).isTrue();
        assertThat(adapter.returnAdjustmentUnissued(null)).isFalse();
    }
}
