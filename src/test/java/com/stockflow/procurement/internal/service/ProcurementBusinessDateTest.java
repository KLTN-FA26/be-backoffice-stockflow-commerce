package com.stockflow.procurement.internal.service;

import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.entity.SupplierJpaEntity;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.repository.SupplierJpaRepository;
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
    @ParameterizedTest
    @CsvSource({
        "2026-09-23T16:59:59Z,2026-09-23",
        "2026-09-23T17:00:00Z,2026-09-24",
        "2026-09-23T19:00:00Z,2026-09-24",
        "2026-09-24T00:00:00Z,2026-09-24"
    })
    void defaultDeliveryAndNumberUseVietnamDateButExplicitDateIsPreserved(String instant, String date) {
        var supplierId = UUID.randomUUID();
        var suppliers = mock(SupplierJpaRepository.class);
        var supplier = mock(SupplierJpaEntity.class);
        when(supplier.getStatus()).thenReturn(SupplierStatus.ACTIVE);
        when(supplier.getLeadTimeDays()).thenReturn(7);
        when(suppliers.findByIdForUpdate(supplierId)).thenReturn(Optional.of(supplier));
        var orders = mock(PurchaseOrderRepository.class);
        when(orders.nextPoNumber(any())).thenReturn("PO-TEST");
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        var service = new ProcurementServiceImpl(orders, null, null,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), suppliers, null);
        var lines = List.of(new CreatePOLineCommand("CHAIR-01", "Chair", 1, BigDecimal.TEN));
        var result = service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, "VND", null, lines));
        assertThat(result.expectedAt()).isEqualTo(LocalDate.parse(date).plusDays(7));
        verify(orders).nextPoNumber(LocalDate.parse(date));
        var agreedDate = LocalDate.parse("2026-10-10");
        var explicit = service.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, "VND", agreedDate, lines));
        assertThat(explicit.expectedAt()).isEqualTo(agreedDate);
    }
}
