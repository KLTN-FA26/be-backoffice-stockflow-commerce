package com.stockflow.production.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.OrderService;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.procurement.api.SubcontractorTerms;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BR-PRD-13 at the split: a subcontractor that prints for us but is no longer active gets no work (03 §16). */
class SubcontractSplitTest {

    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

    private final ProductionOrderRepository orders = mock(ProductionOrderRepository.class);
    private final SubcontractPurchasing purchasing = mock(SubcontractPurchasing.class);
    private final SubcontractService service = new SubcontractService(orders, mock(ProductionRecords.class),
            mock(ProductionOrders.class), purchasing, mock(InventoryService.class), mock(OrderService.class),
            mock(WarehouseService.class), mock(WorkshopStock.class), mock(ProductionCompletion.class),
            mock(SubcontractSettling.class), mock(ProductionWorkflow.class),
            mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));
    private final CurrentUser manager = new CurrentUser(UUID.randomUUID(), "manager", Set.of(Role.WAREHOUSE_MANAGER),
            Set.of(), DataScope.ALL, Set.of());

    @Test
    void anInactiveSubcontractorGetsNoSplit() {
        ProductionOrder.Work work = new ProductionOrder.Work(UUID.randomUUID(), new Sku("CUP-PP-500"), UUID.randomUUID(),
                "a".repeat(64), 10, LocalDate.of(2026, 11, 1));
        ProductionOrder parent = ProductionOrder.forOrderLine(UUID.randomUUID(), "LSX-P", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), work);
        when(orders.findByIdForUpdate(parent.id())).thenReturn(Optional.of(parent));
        UUID subcontractor = UUID.randomUUID();
        when(purchasing.subcontractorTerms(subcontractor)).thenReturn(Optional.of(new SubcontractorTerms(subcontractor,
                "INNHANH", "In Nhanh", true, false, new BigDecimal("2"))));

        assertThatThrownBy(() -> service.split(manager, parent.id(), new Subcontracting.Split(3, subcontractor,
                ProductionOrder.SubcontractMethod.FULL_SERVICE, new BigDecimal("1500"), "VND", LocalDate.of(2026, 11, 1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
        verify(orders, never()).createChild(any(), any(), any());
    }
}
