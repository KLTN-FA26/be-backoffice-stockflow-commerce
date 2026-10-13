package com.stockflow.production.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Decision QA2 and 03 §16 SUBCONTRACTED → CANCELLED with the collaborators mocked: which end a subcontracted order
 * reaches, what is created in house, and that the agreed fee goes to the settlement once — never paid. The stock,
 * PO and settlement steps themselves run against PostgreSQL in {@code PrintRunIntegrationTest}.
 */
class SubcontractCancellationTest {

    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    private static final BigDecimal FEE = new BigDecimal("20000");

    private final ProductionOrderRepository orders = mock(ProductionOrderRepository.class);
    private final OrderService salesOrders = mock(OrderService.class);
    private final WorkshopStock stock = mock(WorkshopStock.class);
    private final ProductionCompletion completion = mock(ProductionCompletion.class);
    private final SubcontractSettling settling = mock(SubcontractSettling.class);
    private final SubcontractService service = new SubcontractService(orders, mock(ProductionRecords.class),
            mock(ProductionOrders.class), mock(SubcontractPurchasing.class), mock(InventoryService.class), salesOrders,
            mock(WarehouseService.class), stock, completion, settling, mock(ProductionWorkflow.class),
            mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));
    private final CurrentUser manager = new CurrentUser(UUID.randomUUID(), "manager", Set.of(Role.WAREHOUSE_MANAGER),
            Set.of(), DataScope.ALL, Set.of());

    /** A child of 3 split off a ready order of 10, sent out, {@code received} back, {@code passed} of them good. */
    private ProductionOrder child(int received, int passed, OrderStatus salesOrderStatus) {
        ProductionOrder.Work work = new ProductionOrder.Work(UUID.randomUUID(), new Sku("CUP-PP-500"), UUID.randomUUID(),
                "a".repeat(64), 10, LocalDate.of(2026, 11, 1));
        ProductionOrder parent = ProductionOrder.forOrderLine(UUID.randomUUID(), "LSX-P", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), work);
        parent.passPrepress(UUID.randomUUID(), NOW);
        ProductionOrder child = ProductionOrder.subcontractedChild(UUID.randomUUID(), "LSX-C", parent, 3, null,
                new ProductionOrder.Subcontract(ProductionOrder.SubcontractMethod.FULL_SERVICE, UUID.randomUUID(),
                        new BigDecimal("1500"), "VND", UUID.randomUUID()));
        child.sendToSubcontractor();
        if (received > 0) {
            child.receivedFromSubcontractor(received);
            if (passed >= 0) {
                child.recordQc(passed, received - passed, NOW);
            }
        }
        when(orders.findByIdForUpdate(child.id())).thenReturn(Optional.of(child));
        when(orders.nextNumber(any())).thenReturn("LSX-H");
        OrderSummary sales = mock(OrderSummary.class);
        when(sales.status()).thenReturn(salesOrderStatus);
        when(salesOrders.findById(child.salesOrderId())).thenReturn(Optional.of(sales));
        when(stock.holdForReprint(any(), anyInt())).thenReturn(true);
        return child;
    }

    private ProductionOrder createdInHouse() {
        ArgumentCaptor<ProductionOrder> created = ArgumentCaptor.forClass(ProductionOrder.class);
        verify(orders).createChild(created.capture(), eq(manager.userId()), any());
        return created.getValue();
    }

    @Test
    void abandonedWithAGoodUnitTheOrderKeepsItAndTheRestGoesInHouseWithTheFeeSettledOnce() {
        ProductionOrder child = child(1, 1, OrderStatus.IN_PRODUCTION);

        service.cancel(manager, child.id(), "subcontractor gave up", FEE, "setup costs", "HCM-PACK01");

        assertThat(child.status()).as("its good unit is the order's").isEqualTo(ProductionOrder.Status.COMPLETED);
        assertThat(child.work().plannedQuantity()).isEqualTo(1);
        ProductionOrder inHouse = createdInHouse();
        assertThat(inHouse.mode()).isEqualTo(ProductionOrder.Mode.IN_HOUSE);
        assertThat(inHouse.origin()).isEqualTo(ProductionOrder.Origin.SHORTFALL);
        assertThat(inHouse.work().plannedQuantity()).as("only what was still owed").isEqualTo(2);
        verify(settling).requireBlanksAccountedFor(child);
        verify(completion).complete(child, "HCM-PACK01", manager.userId(), FEE, "setup costs");
        verify(settling, never()).settle(any(), any(), any(), any(), any());   // the completion settles, once
        verify(stock, never()).writeOff(any(), any(), any(), any());
    }

    @Test
    void abandonedWithNothingGoodItIsCancelledAndReplacedWholeAndSettledWithTheFee() {
        ProductionOrder child = child(1, 0, OrderStatus.IN_PRODUCTION);

        service.cancel(manager, child.id(), "subcontractor gave up", FEE, "setup costs", null);

        assertThat(child.status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        ProductionOrder replacement = createdInHouse();
        assertThat(replacement.origin()).isEqualTo(ProductionOrder.Origin.REPLACEMENT);
        assertThat(replacement.work().plannedQuantity()).isEqualTo(3);
        verify(stock).releaseUnissued(child);
        verify(settling).settle(eq(child), any(), eq(FEE), eq("setup costs"), eq(manager.userId()));
        verify(completion, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void unitsWaitingForQcAreInspectedFirst() {
        ProductionOrder child = child(1, -1, OrderStatus.IN_PRODUCTION);
        assertThatThrownBy(() -> service.cancel(manager, child.id(), "gave up", null, null, null))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_PRODUCTION_TRANSITION);
        verify(orders, never()).createChild(any(), any(), any());
    }

    @Test
    void aFeeNeedsItsReasonAndIsNeverNegative() {
        ProductionOrder child = child(0, 0, OrderStatus.IN_PRODUCTION);
        assertThatThrownBy(() -> service.cancel(manager, child.id(), "gave up", FEE, " ", null))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.cancel(manager, child.id(), "gave up", FEE.negate(), "x", null))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(child.status()).isEqualTo(ProductionOrder.Status.SUBCONTRACTED);
    }

    @Test
    void theTypedReasonsAreKeptOffTheLog() {
        // Develop #99 log hygiene (lane B finding B-7): reasons are typed text; the log gets ids and counts only.
        ch.qos.logback.classic.Logger logs =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.stockflow.production");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logs.addAppender(appender);
        try {
            String typed = "gave up" + (char) 13 + (char) 10 + "INFO FORGED line";
            ProductionOrder abandoned = child(1, 1, OrderStatus.IN_PRODUCTION);
            service.cancel(manager, abandoned.id(), typed, FEE, "fee " + typed, null);
            ProductionOrder dropped = child(0, 0, OrderStatus.CANCELLED);
            service.cancel(manager, dropped.id(), typed, null, null, null);
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .allSatisfy(line -> assertThat(line).doesNotContain("FORGED").doesNotContain("gave up"));
        } finally {
            logs.detachAppender(appender);
        }
    }

    @Test
    void theSalesOrderCancelledItsGoodUnitsAreWrittenOffAndNothingIsCreatedInHouse() {
        ProductionOrder child = child(1, 1, OrderStatus.CANCELLED);

        service.cancel(manager, child.id(), "customer withdrew", null, null, null);

        assertThat(child.status()).isEqualTo(ProductionOrder.Status.CANCELLED);
        assertThat(child.scrap()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WorkshopStock.Spoilt>> written = ArgumentCaptor.forClass(List.class);
        verify(stock).writeOff(eq(child), written.capture(), eq(manager.userId()), any());
        assertThat(written.getValue()).singleElement().extracting(WorkshopStock.Spoilt::quantity).isEqualTo(1);
        verify(orders, never()).createChild(any(), any(), any());
        verify(settling).settle(eq(child), eq("customer withdrew"), eq(BigDecimal.ZERO), any(), eq(manager.userId()));
    }
}
