package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF05-E0 L11: an order does not complete while part of its shipping fee is owed on no invoice at all - the draft of the
 * rest was refused when the order's remainder was closed, so there is no unissued invoice to wait for. Audit r2
 * lifecycle M-1 / money M-2: nor while a plate fee is on no invoice - an order whose goods all came back keeps it (Q-19),
 * and only the rest invoice carries it (BE-62).
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
        when(shipments.orderFees(any(), any())).thenReturn(Optional.of(new OrderShippingFee(ORDER, "SO-1",
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
        when(shipments.orderFees(any(), any())).thenReturn(Optional.of(new OrderShippingFee(ORDER, "SO-1",
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

    private static DeliveredShipment.Fee plate(String sku) {
        return new DeliveredShipment.Fee(FeeKind.PLATE_FEE, sku, new BigDecimal("2500000"), new BigDecimal("8.00"),
                new BigDecimal("8.00"), false);
    }

    /** An order of 4,000 pieces with a plate fee of SKU-A, no shipping fee line, {@code orderUnits} still meant. */
    private void anOrderWithAPlateFee(long orderUnits, FeeAllocation.Invoiced invoiced) {
        when(queries.shippingFeeInvoiceUnissued(ORDER)).thenReturn(false);
        when(shipments.orderFees(any(), any())).thenReturn(Optional.of(new OrderShippingFee(ORDER, "SO-1",
                UUID.randomUUID(), "HD-1", "VND", null, orderUnits, null, java.util.List.of(plate("SKU-A")))));
        when(invoices.invoicedOfOrder(ORDER)).thenReturn(invoiced);
    }

    @Test
    @DisplayName("audit r2 M-1: every shipment came back, the rest invoice of the plate fee refused and never drawn up - "
            + "the order waits for the plate fee's invoice, though nothing was delivered and no invoice is unissued")
    void aPlateFeeOnNoInvoiceIsUnissuedWhenNothingWasDelivered() {
        anOrderWithAPlateFee(0, FeeAllocation.Invoiced.NOTHING);

        assertThat(adapter.shippingFeeUnissued(ORDER)).isTrue();
    }

    @Test
    @DisplayName("audit r3 LM-2: an issued rest invoice carries 50,000 of shipping fee payment waived since, and the "
            + "plate fee is on no invoice - the order waits, by the draw-up's own rule; once the plate fee is on an "
            + "invoice and the shipping fee taken off by a reduction (a draft: information only), it does not")
    void anIssuedRestInvoiceDecidedAgain() {
        EInvoice rest = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261117-0001",
                new EInvoice.Delivery(ORDER, "SO-1", "HD-1", null, null, null, UUID.randomUUID(),
                        java.time.LocalDate.of(2026, 11, 17)),
                com.stockflow.invoicing.internal.domain.EInvoiceFixtures.SELLER,
                com.stockflow.invoicing.internal.domain.EInvoiceFixtures.BUYER,
                java.util.List.of(com.stockflow.invoicing.internal.domain.EInvoiceLine.fee(UUID.randomUUID(), 1,
                        com.stockflow.invoicing.internal.domain.EInvoiceLine.FeeKind.SHIPPING_FEE, null, "Phi van chuyen",
                        "Lan", new BigDecimal("50000"), new BigDecimal("10.00"))), false, false);
        when(queries.shippingFeeInvoiceUnissued(ORDER)).thenReturn(false);
        when(shipments.orderFees(any(), any())).thenReturn(Optional.of(new OrderShippingFee(ORDER, "SO-1",
                UUID.randomUUID(), "HD-1", "VND", new DeliveredShipment.Fee(FeeKind.SHIPPING_FEE, null,
                new BigDecimal("100000"), new BigDecimal("10.00"), new BigDecimal("10.00")), 0, null,
                java.util.List.of(plate("SKU-A")), new com.stockflow.order.api.ShippingFeeWaiver(
                new BigDecimal("110000"), new BigDecimal("110000")))));
        when(invoices.findIssuedShippingFeeInvoices(ORDER)).thenReturn(java.util.List.of(rest));
        when(invoices.invoicedOfOrder(ORDER)).thenReturn(new FeeAllocation.Invoiced(0, new BigDecimal("50000"),
                Set.of()));

        assertThat(adapter.shippingFeeUnissued(ORDER)).isTrue();

        when(invoices.invoicedOfOrder(ORDER)).thenReturn(new FeeAllocation.Invoiced(0, new BigDecimal("50000"),
                Set.of("SKU-A")));
        assertThat(adapter.shippingFeeUnissued(ORDER)).as("the shipping fee still to take off").isTrue();
        when(invoices.shippingFeeReducedOf(rest.id())).thenReturn(new BigDecimal("-50000"));
        assertThat(adapter.shippingFeeUnissued(ORDER)).isFalse();
        when(invoices.deliveryReductionUnissued(ORDER)).thenReturn(true);
        assertThat(adapter.deliveryReductionUnissued(ORDER)).as("audit r3 EINV-7: information").isTrue();
        assertThat(adapter.deliveryReductionUnissued(null)).isFalse();
    }

    @Test
    @DisplayName("a plate fee on an invoice in force (the rest invoice, or a delivery's): nothing to wait for")
    void aPlateFeeCarriedIsNotWaitedFor() {
        anOrderWithAPlateFee(0, new FeeAllocation.Invoiced(0, BigDecimal.ZERO, Set.of("SKU-A")));

        assertThat(adapter.shippingFeeUnissued(ORDER)).isFalse();
    }

    @Test
    @DisplayName("a plate fee while a delivery is still to be invoiced: that delivery's invoice carries it - the "
            + "shipment's own blocker stands, the fee rest does not")
    void aPlateFeeWaitsForTheDeliveryToCarryIt() {
        anOrderWithAPlateFee(4000, new FeeAllocation.Invoiced(2000, BigDecimal.ZERO, Set.of()));

        assertThat(adapter.shippingFeeUnissued(ORDER)).isFalse();
    }

    // ================================================================================================= O-1

    private static final UUID SHIPMENT = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();

    /** WF5-C's invoice: 3 cartons of 1,000 cups at 1,200,000 a carton, at the delivery day's 8%. */
    private static EInvoiceLine wf5cGoods() {
        return EInvoiceLine.goods(UUID.randomUUID(), 1, LINE, "LY-GIAY-DEMO-12OZ-MOC", "Ly giấy 12oz", "Thùng", 3,
                1000, new BigDecimal("1200000"), new BigDecimal("8.00"));
    }

    @Test
    @DisplayName("O-1: the rates are the invoice's that stands for the shipment - the one the return's adjustment "
            + "reduces - and the refund at them is that adjustment to the dong: 1,101,600 for 850 of WF5-C's cups")
    void ratesAreTheInvoicesAndTheRefundIsTheAdjustment() {
        EInvoice invoice = mock(EInvoice.class);
        EInvoiceLine goods = wf5cGoods();
        when(invoice.lines()).thenReturn(List.of(goods, EInvoiceLine.fee(UUID.randomUUID(), 2,
                EInvoiceLine.FeeKind.SHIPPING_FEE, null, "Phí vận chuyển", "Lần", new BigDecimal("300000"),
                new BigDecimal("8.00"))));
        when(invoices.findLiveOriginalOfShipment(SHIPMENT)).thenReturn(Optional.of(invoice));

        Map<UUID, BigDecimal> rates = adapter.invoicedRates(ORDER, SHIPMENT);

        assertThat(rates).containsOnlyKeys(LINE);
        assertThat(rates.get(LINE)).isEqualByComparingTo("8");
        // What the adjustment takes off for those 850 cups (EInvoiceCorrections.drawUpForReturn): the order side
        // values them at the same rate (OrderLine.grossAt, ReturnWf05DecisionsTest.InvoicedRate) to 1,101,600.
        EInvoiceLine reduction = EInvoiceCorrections.reduction(goods, 850, 1,
                ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi")));
        assertThat(reduction.amount().add(reduction.taxAmount())).isEqualByComparingTo("-1101600");
        verify(shipments, never()).find(any(), any());
    }

    @Test
    @DisplayName("O-1: no invoice drawn up yet - the rate drawing one up would give, the delivery day's")
    void noInvoiceYetIsTheDrawUpsRate() {
        when(invoices.findLiveOriginalOfShipment(SHIPMENT)).thenReturn(Optional.empty());
        when(shipments.find(ORDER, SHIPMENT)).thenReturn(Optional.of(new DeliveredShipment(ORDER, "SO-1",
                UUID.randomUUID(), "HD-1", SHIPMENT, null, null, NOW, "VND", List.of(new DeliveredShipment.Line(LINE,
                "LY-GIAY-DEMO-12OZ-MOC", "Ly giấy 12oz", 3, 1000, 3000, new BigDecimal("1200"),
                new BigDecimal("8.00"), new BigDecimal("10.00"))), List.of(), 3000, 3000, null, null)));

        assertThat(adapter.invoicedRates(ORDER, SHIPMENT)).containsOnlyKeys(LINE)
                .hasEntrySatisfying(LINE, rate -> assertThat(rate).isEqualByComparingTo("8"));
    }

    @Test
    @DisplayName("O-1: a return that names no shipment, or a shipment neither invoiced nor delivered: nothing - its "
            + "lines keep their own rates")
    void nothingKnown() {
        when(invoices.findLiveOriginalOfShipment(SHIPMENT)).thenReturn(Optional.empty());
        when(shipments.find(ORDER, SHIPMENT)).thenReturn(Optional.empty());

        assertThat(adapter.invoicedRates(ORDER, null)).isEmpty();
        assertThat(adapter.invoicedRates(ORDER, SHIPMENT)).isEmpty();
    }
}
