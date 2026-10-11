package com.stockflow.procurement.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.SendPurchaseOrderCommand;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.Supplier;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierDetails;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.repository.PoDeliveryDecisionRepository;
import com.stockflow.procurement.internal.repository.PurchaseOrderSearchRepository;
import com.stockflow.product.api.ProductService;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Purchasing dates are Vietnam calendar dates, and one action reads the clock once. */
class ProcurementBusinessDateTest {

    private final UUID supplierId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderSearchRepository search = mock(PurchaseOrderSearchRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final InventoryControlService inventoryControl = mock(InventoryControlService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);

    private ProcurementServiceImpl service(Clock clock) {
        var supplier = new Supplier(supplierId, new SupplierDetails("SUP", "Supplier", null, "s@example.com", null, null,
                SupplierStatus.ACTIVE, 30, 7, SupplierCommunicationChannel.EMAIL, null, null, false, null), null, null);
        when(suppliers.findById(supplierId)).thenReturn(Optional.of(supplier));
        when(suppliers.findByIdForUpdate(supplierId)).thenReturn(Optional.of(supplier));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        when(search.summary(any(), any(Boolean.class))).thenReturn(Optional.of(mock(PurchaseOrderSummary.class)));
        when(warehouses.findWarehouse(warehouseId))
                .thenReturn(Optional.of(new WarehouseView(warehouseId, "HCM", "Kho HCM", "HCM", true)));
        UUID item = UUID.randomUUID();
        when(inventory.itemPolicy(any(Sku.class)))
                .thenReturn(Optional.of(new InventoryItemPolicy(item, "CHAIR-01", false, false, false)));
        when(inventoryControl.item(any())).thenReturn(new InventoryControlItem("CHAIR-01", "EACH", 0, null));
        return new ProcurementServiceImpl(orders, search, null, clock, suppliers, mock(ApplicationEventPublisher.class),
                mock(NotificationService.class), mock(PoDeliveryDecisionRepository.class),
                new PoCommunicationProfile("Buyer", "Address", "Buyer contact", "0901234567", "buyer@example.com",
                        "Warehouse"),
                mock(ProductService.class), inventory, inventoryControl, warehouses, mock(CurrentUserProvider.class));
    }

    private PurchaseOrder saved() {
        var captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(orders, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendingUsesOneInstantEvenAcrossVietnamMidnight() {
        var beforeMidnight = Instant.parse("2026-09-25T16:59:59.999Z");
        var movingClock = mock(Clock.class);
        when(movingClock.instant()).thenReturn(beforeMidnight, beforeMidnight.plusSeconds(1));
        var service = service(movingClock);
        var order = PurchaseOrder.draft("PO-MIDNIGHT", supplierId, warehouseId, Money.VND,
                List.of(PurchaseOrder.line(1, UUID.randomUUID(), new Sku("CHAIR-01"), "EACH", "Chair", 1,
                        Money.vnd(100), null)),
                LocalDate.parse("2026-09-20"), LocalDate.parse("2026-09-25"), null, 30, 7);
        order.submit(UUID.randomUUID(), UUID.randomUUID(), 0, beforeMidnight);
        order.approve(UUID.randomUUID(), beforeMidnight);
        when(orders.findByIdForUpdate(any())).thenReturn(Optional.of(order));

        service.confirm(order.id().value(), UUID.randomUUID(), new SendPurchaseOrderCommand(null, null));

        assertThat(saved().confirmedAt()).isEqualTo(beforeMidnight);
        assertThat(saved().expectedAt()).isEqualTo(LocalDate.parse("2026-09-25"));
        verify(movingClock, times(1)).instant();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-09-23T16:59:59Z,2026-09-23",
        "2026-09-23T17:00:00Z,2026-09-24",
        "2026-09-23T19:00:00Z,2026-09-24",
        "2026-09-24T00:00:00Z,2026-09-24"
    })
    void defaultDeliveryAndNumberUseVietnamDateButExplicitDateIsPreserved(String instant, String date) {
        var service = service(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        when(orders.nextPoNumber(any())).thenReturn("PO-TEST");
        var lines = List.of(new CreatePOLineCommand("CHAIR-01", "Chair", 1, BigDecimal.TEN));

        service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, warehouseId, "VND", null, null, lines));
        assertThat(saved().orderDate()).isEqualTo(LocalDate.parse(date));
        assertThat(saved().expectedAt()).isEqualTo(LocalDate.parse(date).plusDays(7));
        verify(orders).nextPoNumber(LocalDate.parse(date));

        var agreedDate = LocalDate.parse("2026-10-10");
        service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, warehouseId, "VND", agreedDate, null,
                lines));
        assertThat(saved().expectedAt()).isEqualTo(agreedDate);
    }
}
