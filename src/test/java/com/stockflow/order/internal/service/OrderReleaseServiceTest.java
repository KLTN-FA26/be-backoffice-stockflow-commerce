package com.stockflow.order.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderReleased;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static com.stockflow.order.internal.service.OrderFixtures.WAREHOUSE;
import static com.stockflow.order.internal.service.OrderFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Releasing an order (SCRUM-423, kltn-docs 17 §4.2): no credit for an overdue customer (15 §4.3 step 4, 17 BR-02), stock
 * allocated on the warehouse's bins, and no order with print lines until their goods are received.
 */
class OrderReleaseServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final WarehouseDirectory warehouses = mock(WarehouseDirectory.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final OrderService orders = mock(OrderService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final OrderStockAllocation allocation = mock(OrderStockAllocation.class);
    private final com.stockflow.order.internal.domain.SalesContractRepository contracts =
            mock(com.stockflow.order.internal.domain.SalesContractRepository.class);
    private final OrderSummary summary = mock(OrderSummary.class);
    private final OrderReleaseService service = new OrderReleaseService(repository, warehouses, events,
            orders, Clock.fixed(NOW, ZoneOffset.UTC), inventory, paymentService, allocation, contracts,
            org.mockito.Mockito.mock(ShippingWarehouseService.class), org.mockito.Mockito.mock(com.stockflow.order.internal.service.DeliveryBatchService.class), org.mockito.Mockito.mock(com.stockflow.order.internal.domain.DeliveryBatchRecords.class));

    @BeforeEach
    void knownWarehouse() {
        when(warehouses.codeOf(WAREHOUSE)).thenReturn(Optional.of("HCM"));
        when(paymentService.creditPosition(any())).thenReturn(new CreditPosition(BigDecimal.ZERO, false));
    }

    private Order loaded(Order order) {
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(orders.findById(order.id().value())).thenReturn(Optional.of(summary));
        return order;
    }

    @Test
    @DisplayName("a stock-only order goes to READY_TO_FULFILL: stock allocated, holds pinned, saved, OrderReleased published")
    void releasesAStockOrder() {
        Order order = loaded(OrderFixtures.confirmed());

        assertThat(service.release(order.id().value(), WAREHOUSE, STAFF)).isSameAs(summary);

        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(order.warehouseId()).isEqualTo(WAREHOUSE);
        verify(allocation).allocate(order, "HCM");
        verify(inventory).pinReservations(order.id().value());
        verify(repository).save(order);
        verify(events).publishEventsOf(order);
        assertThat(order.pullDomainEvents()).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(com.stockflow.order.internal.domain.OrderEvent.Released.class,
                        released -> {
                            OrderReleased payload = released.payload();
                            assertThat(payload.warehouseCode()).isEqualTo("HCM");
                        }));
    }

    @Test
    @DisplayName("an order that does not exist is NOT_FOUND and nothing is allocated")
    void unknownOrder() {
        when(repository.findByIdForUpdate(any(OrderId.class))).thenReturn(Optional.empty());

        assertThat(codeOf(() -> service.release(UUID.randomUUID(), WAREHOUSE, STAFF))).isEqualTo(ErrorCode.NOT_FOUND);
        verify(allocation, never()).allocate(any(), anyString());
    }

    @Test
    @DisplayName("a warehouse that does not exist is WAREHOUSE_NOT_FOUND")
    void unknownWarehouse() {
        Order order = loaded(OrderFixtures.confirmed());

        assertThat(codeOf(() -> service.release(order.id().value(), UUID.randomUUID(), STAFF)))
                .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("an order with a print line waits CONFIRMED: its printed goods are not received yet, and its line names "
            + "the blank - nothing is allocated (ORDER_PRINT_GOODS_NOT_RECEIVED)")
    void aPrintLineWaitsForItsGoods() {
        Order order = loaded(OrderFixtures.confirmedWithPrintLine(UUID.randomUUID()));

        assertThat(codeOf(() -> service.release(order.id().value(), WAREHOUSE, STAFF)))
                .isEqualTo(ErrorCode.ORDER_PRINT_GOODS_NOT_RECEIVED);

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(allocation, never()).allocate(any(), anyString());
        verify(repository, never()).save(any());
    }

    /** The order's signed contract: {@code milestones} are what its installments fall due on. */
    private void contractWith(Order order, com.stockflow.order.internal.domain.PaymentMilestone... milestones) {
        com.stockflow.order.internal.domain.SalesContract contract =
                mock(com.stockflow.order.internal.domain.SalesContract.class);
        java.util.List<com.stockflow.order.internal.domain.PlannedInstallment> plan = new java.util.ArrayList<>();
        for (int i = 0; i < milestones.length; i++) {
            plan.add(new com.stockflow.order.internal.domain.PlannedInstallment(UUID.randomUUID(), i + 1, "I" + i,
                    milestones[i], null, com.stockflow.common.domain.Money.vnd(1_000), 0, null));
        }
        when(contract.plan()).thenReturn(plan);
        when(contracts.findLiveByOrderId(order.id().value())).thenReturn(Optional.of(contract));
    }

    @Test
    @DisplayName("an order whose contract pays after delivery is not released for a customer with an overdue "
            + "installment (CUSTOMER_CREDIT_OVERDUE)")
    void overdueCustomerGetsNoCreditRelease() {
        Order order = loaded(OrderFixtures.confirmed());
        contractWith(order, com.stockflow.order.internal.domain.PaymentMilestone.AFTER_DELIVERY);
        when(paymentService.creditPosition(order.customerId()))
                .thenReturn(new CreditPosition(new BigDecimal("500000"), true));

        assertThat(codeOf(() -> service.release(order.id().value(), WAREHOUSE, STAFF)))
                .isEqualTo(ErrorCode.CUSTOMER_CREDIT_OVERDUE);
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(allocation, never()).allocate(any(), anyString());
    }

    @Test
    @DisplayName("an order whose contract pays after delivery is released for a customer who is not overdue")
    void creditOrderReleasedWhenNotOverdue() {
        Order order = loaded(OrderFixtures.confirmed());
        contractWith(order, com.stockflow.order.internal.domain.PaymentMilestone.AFTER_DELIVERY);

        service.release(order.id().value(), WAREHOUSE, STAFF);

        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
    }

    @Test
    @DisplayName("an order whose contract is paid before delivery is not asked about credit at all")
    void prepaidOrderSkipsTheCreditCheck() {
        Order order = loaded(OrderFixtures.confirmed());
        contractWith(order, com.stockflow.order.internal.domain.PaymentMilestone.ON_SIGNING);

        service.release(order.id().value(), WAREHOUSE, STAFF);

        verify(paymentService, never()).creditPosition(any());
    }
}
