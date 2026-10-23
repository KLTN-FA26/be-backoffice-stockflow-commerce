package com.stockflow.procurement.internal.service;

import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.api.SupplierSummary;
import com.stockflow.product.api.SupplierProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** kltn-docs warehouse/01 §3: only an ACTIVE supplier may be a SKU's default supplier. */
class ProcurementSupplierProbeTest {

    private final SupplierService suppliers = mock(SupplierService.class);
    private final ProcurementSupplierProbe probe = new ProcurementSupplierProbe(suppliers);

    private SupplierSummary summary(UUID id, String status) {
        return new SupplierSummary(id, "ACME", "ACME", null, null, null, null, status, 0, 0, "EMAIL", null, null, false,
                null, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    @DisplayName("an ACTIVE supplier is active; an INACTIVE one is known but not active; an unknown id is empty")
    void standing() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        when(suppliers.findById(active)).thenReturn(Optional.of(summary(active, "ACTIVE")));
        when(suppliers.findById(inactive)).thenReturn(Optional.of(summary(inactive, "INACTIVE")));
        when(suppliers.findById(org.mockito.ArgumentMatchers.argThat(id -> !id.equals(active) && !id.equals(inactive))))
                .thenReturn(Optional.empty());

        assertThat(probe.supplier(active)).map(SupplierProbe.Standing::active).contains(true);
        assertThat(probe.supplier(active)).map(SupplierProbe.Standing::code).contains("ACME");
        assertThat(probe.supplier(inactive)).map(SupplierProbe.Standing::active).contains(false);
        assertThat(probe.supplier(UUID.randomUUID())).isEmpty();
    }
}
