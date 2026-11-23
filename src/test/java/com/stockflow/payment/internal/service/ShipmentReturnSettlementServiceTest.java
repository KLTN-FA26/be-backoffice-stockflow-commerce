package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.OrderSettlementResult;
import com.stockflow.payment.api.SettleShipmentReturnCommand;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.SettlementBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 BE-03 (X-2, {@code PaymentService.settleShipmentReturn}): the shipment's money settles once - per closure and
 * per shipment - in the caller's transaction, by the return's rule; nothing for a closure worth nothing or an order
 * with no installments.
 */
class ShipmentReturnSettlementServiceTest {

    private static final Instant AT = Instant.parse("2026-11-20T03:00:00Z");

    private final InstallmentBook installments = mock(InstallmentBook.class);
    private final SettlementBook settlements = mock(SettlementBook.class);
    private final OrderSettlementService service = new OrderSettlementService(installments, settlements);

    private final UUID orderId = UUID.randomUUID();
    private final UUID shipmentId = UUID.randomUUID();
    private final UUID closureId = UUID.randomUUID();

    private SettleShipmentReturnCommand command(UUID closure, long goods,
                                                SettleShipmentReturnCommand.ShippingFee fee) {
        return new SettleShipmentReturnCommand(orderId, shipmentId, null, closure, BigDecimal.valueOf(goods),
                SettleShipmentReturnCommand.Cause.CARRIER_RETURN, AT, UUID.randomUUID(), Set.of(), fee);
    }

    private SettleShipmentReturnCommand command(UUID closure, long goods) {
        return command(closure, goods, SettleShipmentReturnCommand.ShippingFee.NONE);
    }

    private List<Installment> paidPlan() {
        Installment onSigning = Installment.open(UUID.randomUUID(), UUID.randomUUID(), orderId, UUID.randomUUID(),
                "HD-1", "SO-1", 1, "Deposit", InstallmentMilestone.ON_SIGNING, null, null, BigDecimal.valueOf(1_000_000),
                "VND", 0);
        onSigning.reach(AT.minusSeconds(86_400), java.time.LocalDate.of(2026, 11, 19));
        onSigning.pay(BigDecimal.valueOf(1_000_000), AT.minusSeconds(86_400));
        return List.of(onSigning);
    }

    @Test
    @DisplayName("settled by the return's rule: kind SHIPMENT_RETURNED, the shipment named, what was paid refundable")
    void settles() {
        when(installments.lockOfOrder(orderId)).thenReturn(paidPlan());
        when(settlements.find(any(), any())).thenReturn(Optional.empty());
        when(settlements.findOfShipment(shipmentId)).thenReturn(Optional.empty());
        when(settlements.ofOrder(orderId)).thenReturn(List.of());

        OrderSettlementResult result = service.settleShipmentReturn(command(closureId, 400_000)).orElseThrow();

        assertThat(result.kind()).isEqualTo("SHIPMENT_RETURNED");
        assertThat(result.refundable()).isEqualByComparingTo("400000");
        ArgumentCaptor<OrderSettlement> saved = ArgumentCaptor.forClass(OrderSettlement.class);
        verify(settlements).save(saved.capture());
        assertThat(saved.getValue().shipmentId()).isEqualTo(shipmentId);
        assertThat(saved.getValue().sourceId()).isEqualTo(closureId);
    }

    @Test
    @DisplayName("idempotent on the closure: a replay returns the settlement made and writes nothing")
    void replayOfTheClosure() {
        OrderSettlement made = OrderSettlement.shipmentReturned(paidPlan(), closureId, shipmentId, null,
                BigDecimal.valueOf(400_000), BigDecimal.ZERO, Set.of(), BigDecimal.ZERO, null, AT,
                java.time.LocalDate.of(2026, 11, 20)).settlement();
        when(settlements.find(OrderSettlement.Kind.SHIPMENT_RETURNED, closureId)).thenReturn(Optional.of(made));

        assertThat(service.settleShipmentReturn(command(closureId, 400_000))).get()
                .extracting(OrderSettlementResult::settlementId).isEqualTo(made.id());
        verify(settlements, never()).save(any());
        verify(installments, never()).lockOfOrder(any());
    }

    @Test
    @DisplayName("once per shipment: a second closure of a shipment already settled settles nothing more")
    void onceperShipment() {
        OrderSettlement made = OrderSettlement.shipmentReturned(paidPlan(), closureId, shipmentId, null,
                BigDecimal.valueOf(400_000), BigDecimal.ZERO, Set.of(), BigDecimal.ZERO, null, AT,
                java.time.LocalDate.of(2026, 11, 20)).settlement();
        UUID secondClosure = UUID.randomUUID();
        when(settlements.find(OrderSettlement.Kind.SHIPMENT_RETURNED, secondClosure)).thenReturn(Optional.empty());
        when(settlements.findOfShipment(shipmentId)).thenReturn(Optional.of(made));

        assertThat(service.settleShipmentReturn(command(secondClosure, 400_000))).get()
                .extracting(OrderSettlementResult::settlementId).isEqualTo(made.id());
        verify(settlements, never()).save(any());
    }

    @Test
    @DisplayName("nothing for a closure worth nothing, or an order without installments")
    void nothingToSettle() {
        when(settlements.find(any(), any())).thenReturn(Optional.empty());
        when(settlements.findOfShipment(shipmentId)).thenReturn(Optional.empty());
        assertThat(service.settleShipmentReturn(command(closureId, 0))).isEmpty();
        verify(installments, never()).lockOfOrder(any());

        when(installments.lockOfOrder(orderId)).thenReturn(List.of());
        assertThat(service.settleShipmentReturn(command(closureId, 100))).isEmpty();
        verify(settlements, never()).save(any());
    }

    @Test
    @DisplayName("in the caller's transaction only: MANDATORY on the module's implementation and on this service")
    void mandatory() throws Exception {
        Transactional impl = PaymentServiceImpl.class.getMethod("settleShipmentReturn",
                SettleShipmentReturnCommand.class).getAnnotation(Transactional.class);
        assertThat(impl.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(OrderSettlementService.class.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("Q-19: the share of the shipping fee is payment's to work out - from the order's earlier shipments, "
            + "read under the installments' lock - never the caller's")
    void shippingFeeShare() {
        SettleShipmentReturnCommand.ShippingFee fee = new SettleShipmentReturnCommand.ShippingFee(
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(1_200_000));
        List<Installment> plan = paidPlan();
        OrderSettlement earlier = OrderSettlement.shipmentReturned(plan, UUID.randomUUID(), UUID.randomUUID(), null,
                BigDecimal.valueOf(400_000), BigDecimal.valueOf(33_333), Set.of(), BigDecimal.ZERO, null, AT,
                java.time.LocalDate.of(2026, 11, 20)).settlement();
        when(installments.lockOfOrder(orderId)).thenReturn(plan);
        when(settlements.find(any(), any())).thenReturn(Optional.empty());
        when(settlements.findOfShipment(shipmentId)).thenReturn(Optional.empty());
        when(settlements.ofOrder(orderId)).thenReturn(List.of(earlier));

        OrderSettlementResult result = service.settleShipmentReturn(command(closureId, 400_000, fee)).orElseThrow();

        ArgumentCaptor<OrderSettlement> saved = ArgumentCaptor.forClass(OrderSettlement.class);
        verify(settlements).save(saved.capture());
        assertThat(saved.getValue().shippingFeeWaived()).as("two thirds through both (66,667), less the first's")
                .isEqualByComparingTo("33334");
        assertThat(saved.getValue().closed()).isEqualByComparingTo("433334");
        assertThat(result.shippingFeeWaived()).as("handed back for the order to keep").isEqualByComparingTo("33334");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(installments, settlements);
        order.verify(installments).lockOfOrder(orderId);
        order.verify(settlements).ofOrder(orderId);
    }

    @Test
    @DisplayName("the command states the shipping fee - NONE for an order without one; there is no shorter form that "
            + "would waive nothing for a caller that forgot it")
    void feeStated() {
        assertThatThrownBy(() -> new SettleShipmentReturnCommand(orderId, shipmentId, null, closureId, BigDecimal.TEN,
                SettleShipmentReturnCommand.Cause.LOST, AT, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(java.util.Arrays.stream(SettleShipmentReturnCommand.class.getConstructors())
                .map(java.lang.reflect.Constructor::getParameterCount)).containsExactly(10);
        assertThatThrownBy(() -> new SettleShipmentReturnCommand.ShippingFee(BigDecimal.TEN, BigDecimal.ZERO))
                .as("a fee charged for no goods").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettleShipmentReturnCommand.ShippingFee(new BigDecimal("0.5"), BigDecimal.TEN))
                .as("whole units").isInstanceOf(IllegalArgumentException.class);
    }
}
