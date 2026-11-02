package com.stockflow.order.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.OrderLinesReleasedForProduction;
import com.stockflow.contracts.OrderReleased;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.DeliveryMode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ProductionRecords;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static com.stockflow.order.internal.service.OrderFixtures.WAREHOUSE;
import static com.stockflow.order.internal.service.OrderFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Releasing an order (SCRUM-423, kltn-docs 17 / 19 §4.1): an approved sample for every new design (BR-PRD-08), no credit
 * for an overdue customer (15 §4.3 step 4, 17 BR-02), stock allocated on the warehouse's bins, and the delivery mode
 * only for someone allowed to choose it (LP-1).
 */
class OrderReleaseServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final ProductionRecords production = mock(ProductionRecords.class);
    private final WarehouseDirectory warehouses = mock(WarehouseDirectory.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final OrderService orders = mock(OrderService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final OrderStockAllocation allocation = mock(OrderStockAllocation.class);
    private final DeliveryModeHistory deliveryModes = mock(DeliveryModeHistory.class);
    private final OrderSummary summary = mock(OrderSummary.class);
    private final OrderReleaseService service = new OrderReleaseService(repository, production, warehouses, events,
            orders, Clock.fixed(NOW, ZoneOffset.UTC), inventory, paymentService, allocation, deliveryModes);

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

    private static CurrentUser user(PermissionCode... permissions) {
        return new CurrentUser(STAFF, "coordinator", Set.of(Role.CUSTOMER), Set.of(permissions), DataScope.ALL,
                Set.of());
    }

    @Test
    @DisplayName("a stock-only order goes to READY_TO_FULFILL: stock allocated, holds pinned, saved, OrderReleased published")
    void releasesAStockOrder() {
        Order order = loaded(OrderFixtures.confirmedPrepaid());

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
        Order order = loaded(OrderFixtures.confirmedPrepaid());

        assertThat(codeOf(() -> service.release(order.id().value(), UUID.randomUUID(), STAFF)))
                .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("a new design without an approved sample is refused (BR-PRD-08), naming the blank; nothing is allocated")
    void newDesignNeedsAnApprovedSample() {
        UUID design = UUID.randomUUID();
        Order order = loaded(OrderFixtures.confirmedWithPrintLine(design));
        when(production.approvedSample(design, "CUP-12OZ")).thenReturn(Optional.empty());
        when(production.producedBefore(design, order.id().value())).thenReturn(false);

        assertThatThrownBy(() -> service.release(order.id().value(), WAREHOUSE, STAFF))
                .isInstanceOfSatisfying(com.stockflow.common.error.BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
                    assertThat(ex.getMessage()).contains("CUP-12OZ");
                });
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(allocation, never()).allocate(any(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an approved sample sends the order to IN_PRODUCTION and travels in the production event")
    void approvedSampleGoesToProduction() {
        UUID design = UUID.randomUUID();
        UUID sample = UUID.randomUUID();
        Order order = loaded(OrderFixtures.confirmedWithPrintLine(design));
        when(production.approvedSample(design, "CUP-12OZ")).thenReturn(Optional.of(sample));

        service.release(order.id().value(), WAREHOUSE, STAFF);

        assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        OrderLine printLine = order.printLines().get(0);
        assertThat(order.pullDomainEvents()).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(com.stockflow.order.internal.domain.OrderEvent.LinesReleased.class, released -> {
                    OrderLinesReleasedForProduction payload = released.payload();
                    assertThat(payload.lines()).singleElement().satisfies(line -> {
                        assertThat(line.orderLineId()).isEqualTo(printLine.id());
                        assertThat(line.approvedSampleId()).isEqualTo(sample);
                        assertThat(line.quantity()).isEqualTo(500);
                    });
                }));
        verify(allocation).allocate(order, "HCM");
    }

    @Test
    @DisplayName("a design already printed for an earlier order is a repeat and needs no sample")
    void aRepeatDesignNeedsNoSample() {
        UUID design = UUID.randomUUID();
        Order order = loaded(OrderFixtures.confirmedWithPrintLine(design));
        when(production.approvedSample(design, "CUP-12OZ")).thenReturn(Optional.empty());
        when(production.producedBefore(design, order.id().value())).thenReturn(true);

        service.release(order.id().value(), WAREHOUSE, STAFF);

        assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
    }

    @Test
    @DisplayName("no credit order is released for a customer with an overdue receivable (CUSTOMER_CREDIT_OVERDUE)")
    void overdueCustomerGetsNoCreditRelease() {
        Order order = OrderFixtures.draft(PaymentTerm.CREDIT, UUID.randomUUID());
        order.submitOnCredit(true);
        loaded(order);
        when(paymentService.creditPosition(order.customerId()))
                .thenReturn(new CreditPosition(new BigDecimal("500000"), true));

        assertThat(codeOf(() -> service.release(order.id().value(), WAREHOUSE, STAFF)))
                .isEqualTo(ErrorCode.CUSTOMER_CREDIT_OVERDUE);
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(allocation, never()).allocate(any(), anyString());
    }

    @Test
    @DisplayName("a credit order of a customer who is not overdue is released")
    void creditOrderReleasedWhenNotOverdue() {
        Order order = OrderFixtures.draft(PaymentTerm.CREDIT, UUID.randomUUID());
        order.submitOnCredit(true);
        loaded(order);

        service.release(order.id().value(), WAREHOUSE, STAFF);

        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
    }

    @Test
    @DisplayName("a prepaid order is not asked about credit at all")
    void prepaidOrderSkipsTheCreditCheck() {
        Order order = loaded(OrderFixtures.confirmedPrepaid());

        service.release(order.id().value(), WAREHOUSE, STAFF);

        verify(paymentService, never()).creditPosition(any());
    }

    @Test
    @DisplayName("a deposit order whose balance goes on credit is held to the same overdue rule (BALCREDIT)")
    void depositOnCreditIsHeldToTheOverdueRule() {
        Order order = Order.draft(com.stockflow.order.internal.domain.OrderNumber.of(java.time.LocalDate.of(2026, 10, 27), 3),
                UUID.randomUUID(), UUID.randomUUID(), java.util.List.of(Order.line(
                        new com.stockflow.common.domain.Sku("CUP-12OZ"), 10,
                        com.stockflow.common.domain.Money.vnd(100_000), null)), NOW);
        order.applyTerms(PaymentTerm.DEPOSIT, new BigDecimal("30"), 30, com.stockflow.order.api.BalancePolicy.ON_CREDIT);
        order.lines().forEach(line -> order.attachReservations(line.id(), java.util.List.of(UUID.randomUUID())));
        order.submit();
        order.recordPayment(new com.stockflow.common.domain.Money(new BigDecimal("300000"), order.total().currency()),
                NOW);
        loaded(order);
        when(paymentService.creditPosition(order.customerId()))
                .thenReturn(new CreditPosition(new BigDecimal("1"), true));

        assertThat(codeOf(() -> service.release(order.id().value(), WAREHOUSE, STAFF)))
                .isEqualTo(ErrorCode.CUSTOMER_CREDIT_OVERDUE);
    }

    @Test
    @DisplayName("choosing another delivery mode at release needs the delivery-modes permission (LP-1)")
    void aDifferentModeNeedsThePermission() {
        Order order = loaded(OrderFixtures.confirmedPrepaid());
        DeliveryMode other = order.deliveryMode() == DeliveryMode.FULL_TRUCKLOAD ? DeliveryMode.PARCEL
                : DeliveryMode.FULL_TRUCKLOAD;

        assertThatThrownBy(() -> service.release(order.id().value(), WAREHOUSE, other, user()))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(deliveryModes, never()).apply(any(), any(), any(), any());
    }

    @Test
    @DisplayName("with the permission the mode is applied under the same lock, after the stock is allocated")
    void aDifferentModeIsAppliedForSomeoneAllowed() {
        Order order = loaded(OrderFixtures.confirmedPrepaid());
        DeliveryMode other = order.deliveryMode() == DeliveryMode.FULL_TRUCKLOAD ? DeliveryMode.PARCEL
                : DeliveryMode.FULL_TRUCKLOAD;
        CurrentUser allowed = user(PermissionCode.of(OrderPermissions.DELIVERY_MODES, Action.UPDATE));

        service.release(order.id().value(), WAREHOUSE, other, allowed);

        verify(deliveryModes).apply(order, other, null, allowed.userId());
        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
    }

    @Test
    @DisplayName("keeping the order's own delivery mode needs no permission")
    void theOwnModeNeedsNoPermission() {
        Order order = loaded(OrderFixtures.confirmedPrepaid());

        service.release(order.id().value(), WAREHOUSE, order.deliveryMode(), user());

        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
    }

    @Test
    @DisplayName("a finished production run moves an order whose prints are all produced to READY_TO_FULFILL")
    void productionFinishedReadiesTheOrder() {
        UUID design = UUID.randomUUID();
        Order order = loaded(OrderFixtures.confirmedWithPrintLine(design));
        when(production.approvedSample(any(), anyString())).thenReturn(Optional.of(UUID.randomUUID()));
        service.release(order.id().value(), WAREHOUSE, STAFF);
        order.pullDomainEvents();
        UUID printLine = order.printLines().get(0).id();
        when(production.producedByLine(order.id().value())).thenReturn(Map.of(printLine, 500));

        service.productionFinished(order.id().value());

        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        verify(repository, org.mockito.Mockito.times(2)).save(order);
    }

    @Test
    @DisplayName("production short of the ordered quantity leaves the order IN_PRODUCTION and saves nothing more")
    void productionShortChangesNothing() {
        UUID design = UUID.randomUUID();
        Order order = loaded(OrderFixtures.confirmedWithPrintLine(design));
        when(production.approvedSample(any(), anyString())).thenReturn(Optional.of(UUID.randomUUID()));
        service.release(order.id().value(), WAREHOUSE, STAFF);
        UUID printLine = order.printLines().get(0).id();
        when(production.producedByLine(order.id().value())).thenReturn(Map.of(printLine, 499));

        service.productionFinished(order.id().value());

        assertThat(order.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        verify(repository, org.mockito.Mockito.times(1)).save(order);
    }

    @Test
    @DisplayName("production finished for an unknown or never-released order is logged and ignored")
    void productionFinishedForAnOrderNotReleased() {
        Order draft = loaded(OrderFixtures.draft(PaymentTerm.PREPAID, UUID.randomUUID()));
        when(repository.findByIdForUpdate(new OrderId(UUID.fromString("00000000-0000-0000-0000-000000000001"))))
                .thenReturn(Optional.empty());

        service.productionFinished(draft.id().value());
        service.productionFinished(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        verify(repository, never()).save(any());
        verify(events, never()).publishEventsOf(eq(draft));
    }
}
