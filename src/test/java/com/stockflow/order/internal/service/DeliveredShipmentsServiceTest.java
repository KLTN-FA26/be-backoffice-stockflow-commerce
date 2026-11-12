package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.order.api.DeliveredShipment;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.TaxRates;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the order tells the e-invoice about a delivered shipment (WF-05 E2): the goods at the order's prices, the VAT
 * rate of the day of delivery by the product's tax class, the order's fees whole, and where the shipment stands among
 * the order's deliveries. The order is a real aggregate brought to its deliveries the way the application brings it.
 */
class DeliveredShipmentsServiceTest {

    private static final Instant PLACED = Instant.parse("2026-11-02T03:00:00Z");
    private static final Instant FIRST_DELIVERY = Instant.parse("2026-11-10T03:00:00Z");
    /** 2026-11-12 in Vietnam, though still the 11th in UTC: the invoice's day is Vietnam's. */
    private static final Instant SECOND_DELIVERY = Instant.parse("2026-11-11T18:30:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final BigDecimal TEN = new BigDecimal("10");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final SalesContractRepository contracts = mock(SalesContractRepository.class);
    private final TaxRates taxRates = mock(TaxRates.class);
    private final ProductService products = mock(ProductService.class);
    private final DeliveredShipmentsService service = new DeliveredShipmentsService(orders, contracts, taxRates,
            products);
    private Order order;

    /**
     * 10 cups and 5 lids at 10% VAT, a plate fee for the cups and a shipping fee; S1 carries 6 cups, S2 the other 4 and
     * the lids, both handed over.
     */
    @BeforeEach
    void anOrderInTwoShipments() {
        order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 2), 9), UUID.randomUUID(), UUID.randomUUID(),
                List.of(Order.line(new Sku("CUP-12OZ"), 10, Money.vnd(100_000), null).withTax(TEN),
                        Order.line(new Sku("LID-90"), 5, Money.vnd(20_000), null).withTax(TEN)), PLACED);
        order.attachFees(List.of(
                OrderFee.of(Identifiers.newId(), FeeKind.PLATE_FEE, new Sku("CUP-12OZ"), Money.vnd(500_000), TEN),
                OrderFee.of(Identifiers.newId(), FeeKind.SHIPPING_FEE, null, Money.vnd(150_000), TEN)));
        order.lines().forEach(line -> order.attachReservations(line.id(), List.of(UUID.randomUUID())));
        order.decidePartialShipment(true);
        order.sendContract(STAFF, PLACED);
        order.contractSigned(false, false, STAFF, PLACED);
        order.chooseShippingWarehouse(HCM, true, STAFF, PLACED);
        order.release(HCM, "HCM", STAFF, PLACED);
        order.startPicking(STAFF, PLACED);
        order.markPacked(PLACED, STAFF);
        UUID cups = order.lines().get(0).id();
        UUID lids = order.lines().get(1).id();
        order.recordHandover(new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(cups, 6)), PLACED, STAFF,
                new ShipmentHandover.Carriage("SO-S1", 1, "Truck Co")), BigDecimal.ZERO);
        order.recordHandover(new ShipmentHandover(S2, List.of(new ShipmentHandover.Line(cups, 4),
                new ShipmentHandover.Line(lids, 5)), PLACED, STAFF), BigDecimal.ZERO);

        when(orders.findById(new OrderId(order.id().value()))).thenReturn(Optional.of(order));
        when(contracts.findLiveByOrderId(any())).thenReturn(Optional.empty());
        when(products.taxClassesOf(any())).thenReturn(Map.of("CUP-12OZ", TaxClass.STANDARD, "LID-90",
                TaxClass.REDUCED));
        when(products.nameForSku("CUP-12OZ")).thenReturn(Optional.of("Ly giay 12oz"));
        when(products.nameForSku("LID-90")).thenReturn(Optional.empty());
        when(taxRates.onDay(any())).thenReturn(Map.of("STANDARD", TEN, "REDUCED", new BigDecimal("5"), "EXEMPT",
                BigDecimal.ZERO));
    }

    private DeliveredShipment find(UUID shipmentId) {
        return service.find(order.id().value(), shipmentId).orElseThrow();
    }

    @Test
    @DisplayName("a shipment that is not delivered is not invoiced: handed over, failed, corrected, or unknown")
    void onlyADeliveredShipment() {
        assertThat(service.find(order.id().value(), S1)).as("handed over, not delivered").isEmpty();
        assertThat(service.find(UUID.randomUUID(), S1)).as("no such order").isEmpty();
        when(orders.findById(any())).thenReturn(Optional.empty());
        assertThat(service.find(UUID.randomUUID(), S1)).isEmpty();
        when(orders.findById(new OrderId(order.id().value()))).thenReturn(Optional.of(order));

        order.recordShipmentDelivered(S1, FIRST_DELIVERY, STAFF);
        assertThat(service.find(order.id().value(), S1)).isPresent();
        assertThat(service.find(order.id().value(), UUID.randomUUID())).as("not a shipment of the order").isEmpty();

        // 09 §4.3 step 5: a delivery reported wrongly is corrected to a failure - nothing to invoice any more.
        order.correctShipmentToFailed(S1, DeliveryFailureReason.CUSTOMER_ABSENT, null, FIRST_DELIVERY, STAFF);
        assertThat(service.find(order.id().value(), S1)).isEmpty();
    }

    @Test
    @DisplayName("the first delivery: its goods at the order's price, the fees whole, nothing delivered before it")
    void theFirstDelivery() {
        order.recordShipmentDelivered(S1, FIRST_DELIVERY, STAFF);

        DeliveredShipment delivered = find(S1);

        assertThat(delivered.orderNumber()).isEqualTo(order.orderNumber().value());
        assertThat(delivered.customerId()).isEqualTo(order.customerId());
        assertThat(delivered.shipmentCode()).isEqualTo("SO-S1");
        assertThat(delivered.deliveredAt()).isEqualTo(FIRST_DELIVERY);
        assertThat(delivered.currency()).isEqualTo("VND");
        assertThat(delivered.contractNumber()).isNull();
        assertThat(delivered.lines()).extracting(DeliveredShipment.Line::sku, DeliveredShipment.Line::name,
                DeliveredShipment.Line::cartons, DeliveredShipment.Line::units,
                line -> line.unitPrice().toPlainString(), line -> line.taxRate().toPlainString())
                .containsExactly(tuple("CUP-12OZ", "Ly giay 12oz", null, 6, "100000", "10"));
        assertThat(delivered.fees()).extracting(DeliveredShipment.Fee::kind, DeliveredShipment.Fee::sku,
                fee -> fee.amount().toPlainString()).containsExactly(
                tuple(FeeKind.PLATE_FEE, "CUP-12OZ", "500000"), tuple(FeeKind.SHIPPING_FEE, null, "150000"));
        assertThat(delivered.orderUnits()).isEqualTo(15);
        assertThat(delivered.unitsDeliveredBefore()).isZero();
        assertThat(delivered.unitsInShipment()).isEqualTo(6);
    }

    @Test
    @DisplayName("a later delivery knows what was delivered before it, by delivery time - whatever is asked first")
    void aLaterDelivery() {
        order.recordShipmentDelivered(S2, SECOND_DELIVERY, STAFF);
        assertThat(find(S2).unitsDeliveredBefore()).as("S1 is not delivered yet: S2 is the order's first").isZero();

        order.recordShipmentDelivered(S1, FIRST_DELIVERY, STAFF);
        DeliveredShipment second = find(S2);
        assertThat(second.unitsDeliveredBefore()).as("S1 was delivered earlier, though recorded later").isEqualTo(6);
        assertThat(second.unitsInShipment()).isEqualTo(9);
        assertThat(find(S1).unitsDeliveredBefore()).isZero();
        // A SKU without a product name is named by its code; without a carton unit it is counted in pieces.
        assertThat(second.lines()).extracting(DeliveredShipment.Line::sku, DeliveredShipment.Line::name,
                DeliveredShipment.Line::units).containsExactly(tuple("CUP-12OZ", "Ly giay 12oz", 4),
                tuple("LID-90", "LID-90", 5));
    }

    @Test
    @DisplayName("the VAT rate is the day of delivery's for the product's tax class, with the order's rate beside it")
    void theRateOfTheDayOfDelivery() {
        order.recordShipmentDelivered(S2, SECOND_DELIVERY, STAFF);
        // A reduction in force on 12/11 (Vietnam): STANDARD 8%. The lids' class is REDUCED: 5%, not the order's 10%.
        when(taxRates.onDay(LocalDate.of(2026, 11, 12))).thenReturn(Map.of("STANDARD", new BigDecimal("8"), "REDUCED",
                new BigDecimal("5"), "EXEMPT", BigDecimal.ZERO));

        DeliveredShipment delivered = find(S2);

        assertThat(delivered.lines()).extracting(DeliveredShipment.Line::sku,
                line -> line.taxRate().toPlainString(), line -> line.contractTaxRate().toPlainString())
                .containsExactly(tuple("CUP-12OZ", "8", "10.00"), tuple("LID-90", "5", "10.00"));
        assertThat(delivered.fees()).extracting(DeliveredShipment.Fee::kind, fee -> fee.taxRate().toPlainString(),
                fee -> fee.contractTaxRate().toPlainString()).containsExactly(
                // the plate fee follows its SKU's class; the shipping fee, which names no product, STANDARD (D31)
                tuple(FeeKind.PLATE_FEE, "8", "10.00"), tuple(FeeKind.SHIPPING_FEE, "8", "10.00"));
    }

    @Test
    @DisplayName("a SKU whose product has no tax class keeps the rate the order fixed")
    void noTaxClassKeepsTheOrdersRate() {
        order.recordShipmentDelivered(S1, FIRST_DELIVERY, STAFF);
        when(products.taxClassesOf(any())).thenReturn(Map.of());
        when(taxRates.onDay(any())).thenReturn(Map.of("STANDARD", new BigDecimal("8")));

        DeliveredShipment delivered = find(S1);

        assertThat(delivered.lines().getFirst().taxRate()).isEqualByComparingTo("10");
        assertThat(delivered.fees().get(0).taxRate()).as("the plate fee's SKU has no class either")
                .isEqualByComparingTo("10");
        assertThat(delivered.fees().get(1).taxRate()).as("the shipping fee is STANDARD").isEqualByComparingTo("8");
    }
}
