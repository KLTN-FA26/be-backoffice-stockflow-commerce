package com.stockflow.procurement.internal.service;

import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.Supplier;
import com.stockflow.procurement.internal.domain.SupplierDetails;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.mockito.Mockito.*;

class ProcurementBusinessDateTest {
    @org.junit.jupiter.api.Test
    void sendingUsesOneInstantEvenAcrossVietnamMidnight() {
        var beforeMidnight = Instant.parse("2026-09-25T16:59:59.999Z");
        var movingClock = mock(Clock.class);
        when(movingClock.instant()).thenReturn(beforeMidnight, beforeMidnight.plusSeconds(1));
        var supplierId = UUID.randomUUID();
        var supplier = new Supplier(supplierId, new SupplierDetails("SUP", "Supplier", null, "s@example.com",
                null, null, SupplierStatus.ACTIVE, 30, 7, SupplierCommunicationChannel.EMAIL, null), null, null);
        var suppliers = mock(SupplierRepository.class);
        when(suppliers.findById(supplierId)).thenReturn(Optional.of(supplier));
        var order = com.stockflow.procurement.internal.domain.PurchaseOrder.draft("PO-MIDNIGHT", supplierId,
                com.stockflow.common.domain.Money.VND, List.of(new com.stockflow.procurement.internal.domain.PoLine(
                        UUID.randomUUID(), new com.stockflow.common.domain.Sku("CHAIR-01"), "Chair", 1, 0,
                        com.stockflow.common.domain.Money.vnd(100))), LocalDate.parse("2026-09-25"));
        order.approve(beforeMidnight);
        var orders = mock(PurchaseOrderRepository.class);
        when(orders.findByIdForUpdate(any())).thenReturn(Optional.of(order));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        var service = new ProcurementServiceImpl(orders, null, null, movingClock, suppliers,
                mock(org.springframework.context.ApplicationEventPublisher.class), mock(com.stockflow.notification.api.NotificationService.class),
                mock(com.stockflow.procurement.internal.repository.PoDeliveryDecisionRepository.class));
        var sent = service.send(order.id().value());
        assertThat(sent.sentAt()).isEqualTo(beforeMidnight);
        assertThat(sent.expectedAt()).isEqualTo(LocalDate.parse("2026-09-25"));
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
        var supplierId = UUID.randomUUID();
        var suppliers = mock(SupplierRepository.class);
        var supplier = new Supplier(supplierId, new SupplierDetails("SUP", "Supplier", null, "s@example.com",
                null, null, SupplierStatus.ACTIVE, 30, 7, SupplierCommunicationChannel.EMAIL, null), null, null);
        when(suppliers.findByIdForUpdate(supplierId)).thenReturn(Optional.of(supplier));
        var orders = mock(PurchaseOrderRepository.class);
        when(orders.nextPoNumber(any())).thenReturn("PO-TEST");
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        var service = new ProcurementServiceImpl(orders, null, null,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), suppliers, null, null, null);
        var lines = List.of(new CreatePOLineCommand("CHAIR-01", "Chair", 1, BigDecimal.TEN));
        var result = service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, "VND", null, lines));
        assertThat(result.expectedAt()).isEqualTo(LocalDate.parse(date).plusDays(7));
        verify(orders).nextPoNumber(LocalDate.parse(date));
        var agreedDate = LocalDate.parse("2026-10-10");
        var explicit = service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, "VND", agreedDate, lines));
        assertThat(explicit.expectedAt()).isEqualTo(agreedDate);
    }
}
