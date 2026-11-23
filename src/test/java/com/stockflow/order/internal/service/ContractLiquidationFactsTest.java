package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.FeeKind;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.payment.api.ContractLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * What the liquidation of a contract says (owner, 2026-11-23): the contract's value as signed, the value performed -
 * the goods delivered successfully and kept, with VAT, 0 when nothing was delivered, by G1's one "delivered something"
 * - what was paid, what was paid back, and 0 left; and a contract whose money is not settled is refused rather than
 * drawn. The order is a real aggregate brought to its deliveries the way the application brings it; the money is the
 * payment module's {@link ContractLedger}, stubbed with the figures payment would keep.
 */
class ContractLiquidationFactsTest {

    private static final Instant PLACED = Instant.parse("2026-11-02T03:00:00Z");
    private static final Instant DELIVERY = Instant.parse("2026-11-10T03:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final BigDecimal TEN = new BigDecimal("10");
    /** Cups 1,100,000 + lids 110,000 + plate fee 550,000 + shipping fee 165,000, all with 10% VAT. */
    private static final BigDecimal CONTRACT_VALUE = new BigDecimal("1925000");
    private static final SalesContracts.Party PARTY = new SalesContracts.Party("Party", "0312345678", "HCM", "A",
            "GIÁM ĐỐC");

    private final SalesContracts contracts = mock(SalesContracts.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ReturnRequestRepository returns = mock(ReturnRequestRepository.class);
    private final ContractLedger ledger = mock(ContractLedger.class);
    private final SalesDocumentFacts shared = mock(SalesDocumentFacts.class);
    private final ContractLiquidationDocument document = mock(ContractLiquidationDocument.class);
    private final ContractPaperService papers = new ContractPaperService(contracts, mock(DeliveryBatchService.class),
            orders, returns, ledger, shared, mock(ContractAppendixDocument.class), document,
            Clock.fixed(Instant.parse("2026-12-28T03:00:00Z"), ZoneOffset.UTC));
    private Order order;

    /** 10 cups and 5 lids, a plate fee and a shipping fee; S1 carries 6 cups, S2 the other 4 and the lids. */
    @BeforeEach
    void anOrderInTwoShipments() {
        order = Order.draft(OrderNumber.of(LocalDate.of(2026, 11, 2), 11), UUID.randomUUID(), UUID.randomUUID(),
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
        order.recordHandover(new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(cups, 6)), PLACED, STAFF),
                BigDecimal.ZERO);
        order.recordHandover(new ShipmentHandover(S2, List.of(new ShipmentHandover.Line(cups, 4),
                new ShipmentHandover.Line(lids, 5)), PLACED, STAFF), BigDecimal.ZERO);

        when(orders.findById(new OrderId(order.id().value()))).thenReturn(Optional.of(order));
        when(returns.findByOrder(any())).thenReturn(List.of());
        when(shared.names(any())).thenReturn(Map.of());
        when(document.render(any())).thenReturn(new byte[]{1});
    }

    private void contract(String status) {
        when(contracts.get(order.id().value())).thenReturn(new SalesContracts.ContractView(UUID.randomUUID(),
                "HD-20261102-000011-1", order.id().value(), order.orderNumber().value(), status, PARTY, PARTY, 2,
                new SalesContracts.Totals(new BigDecimal("1750000"), new BigDecimal("175000"), CONTRACT_VALUE, "VND"),
                List.of(), List.of(), PLACED, STAFF, LocalDate.of(2026, 11, 2), null, PLACED, STAFF, null, null, null,
                null, 0, null));
    }

    private void books(String owed, String paid, String refunded, String outstanding, String toRefund) {
        when(ledger.of(order.id().value())).thenReturn(new ContractLedger.Standing(new BigDecimal(owed),
                BigDecimal.ZERO, List.of(new ContractLedger.Payment(LocalDate.of(2026, 11, 3), "FT-1",
                new BigDecimal(paid))), new BigDecimal(refunded), new BigDecimal(outstanding),
                new BigDecimal(toRefund)));
    }

    private ContractLiquidationDocument.Facts drawn() {
        papers.liquidation(order.id().value());
        ArgumentCaptor<ContractLiquidationDocument.Facts> facts =
                ArgumentCaptor.forClass(ContractLiquidationDocument.Facts.class);
        verify(document).render(facts.capture());
        return facts.getValue();
    }

    private void comesBack(UUID shipment) {
        order.recordShipmentDeliveryFailed(shipment, DeliveryFailureReason.CUSTOMER_ABSENT, null, DELIVERY, STAFF);
        order.recordShipmentReturned(shipment, DELIVERY, STAFF);
        order.closeReturnedShipment(shipment, DELIVERY, DELIVERY, STAFF);
    }

    @Test
    @DisplayName("everything delivered and paid: the contract's value as signed, the goods with VAT performed, the "
            + "fees as the other charges, everything paid, 0 left")
    void aContractPerformedInFull() {
        order.recordShipmentDelivered(S1, DELIVERY, STAFF);
        order.recordShipmentDelivered(S2, DELIVERY, STAFF);
        contract("COMPLETED");
        books("1925000", "1925000", "0", "0", "0");

        ContractLiquidationDocument.Facts facts = drawn();
        assertThat(facts.contractValue()).isEqualByComparingTo(CONTRACT_VALUE);
        assertThat(facts.performedValue()).as("10 cups and 5 lids with VAT").isEqualByComparingTo("1210000");
        assertThat(facts.otherCharges()).as("plate fee 550,000 + shipping fee 165,000").isEqualByComparingTo("715000");
        assertThat(facts.paid()).isEqualByComparingTo("1925000");
        assertThat(facts.refunded()).isZero();
        assertThat(facts.remaining()).isZero();
        assertThat(facts.reason()).isNull();
    }

    @Test
    @DisplayName("a partial carrier return: the shipment brought back is not performed - 6 cups are - and its goods "
            + "and the waived share of the fee were paid back; the contract's value stays as signed")
    void aPartialCarrierReturn() {
        order.recordShipmentDelivered(S1, DELIVERY, STAFF);
        comesBack(S2);
        contract("COMPLETED");
        // Payment closed S2's goods (440,000 + 110,000) and waived its share of the fee (75,000): owed 1,300,000.
        books("1300000", "1925000", "625000", "0", "0");

        ContractLiquidationDocument.Facts facts = drawn();
        assertThat(facts.contractValue()).isEqualByComparingTo(CONTRACT_VALUE);
        assertThat(facts.performedValue()).as("6 cups with VAT").isEqualByComparingTo("660000");
        assertThat(facts.otherCharges()).as("plate fee 550,000 + shipping fee kept 90,000")
                .isEqualByComparingTo("640000");
        assertThat(facts.refunded()).isEqualByComparingTo("625000");
        assertThat(facts.remaining()).isZero();
        assertThat(facts.goods()).extracting(ContractLiquidationDocument.GoodsLine::delivered)
                .as("only the delivered shipment counts").containsExactly(6L, 0L);
    }

    @Test
    @DisplayName("nothing delivered (Q-6): the contract LIQUIDATED is accepted, the value performed is 0, the reason "
            + "says the goods could not be delivered, the plate fee kept and the rest paid back")
    void nothingDelivered() {
        comesBack(S1);
        comesBack(S2);
        assertThat(order.deliveredSomething()).isFalse();
        contract("LIQUIDATED");
        books("550000", "1925000", "1375000", "0", "0");

        ContractLiquidationDocument.Facts facts = drawn();
        assertThat(facts.performedValue()).isZero();
        assertThat(facts.otherCharges()).as("the plate fee").isEqualByComparingTo("550000");
        assertThat(facts.remaining()).isZero();
        assertThat(facts.reason()).contains(order.orderNumber().value()).contains("giao không thành công");
        assertThat(facts.goods()).allSatisfy(line -> assertThat(line.delivered()).isZero());
    }

    @Test
    @DisplayName("money not settled - a balance owed, or a refund owed - is refused with SALES_CONTRACT_STATUS_INVALID "
            + "and no paper is drawn")
    void anUnsettledContractIsRefused() {
        order.recordShipmentDelivered(S1, DELIVERY, STAFF);
        order.recordShipmentDelivered(S2, DELIVERY, STAFF);
        contract("COMPLETED");

        books("1925000", "1825000", "0", "100000", "0");
        assertThat(catchThrowableOfType(BusinessException.class, () -> papers.liquidation(order.id().value()))
                .errorCode()).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        books("1825000", "1925000", "0", "0", "100000");
        assertThat(catchThrowableOfType(BusinessException.class, () -> papers.liquidation(order.id().value()))
                .errorCode()).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        verify(document, never()).render(any());
    }
}
