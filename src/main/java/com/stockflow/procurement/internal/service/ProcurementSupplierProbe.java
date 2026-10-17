package com.stockflow.procurement.internal.service;

import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.product.api.SupplierProbe;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Procurement's answer to "may this supplier be a SKU's default supplier?" (docs 01 §3), handed to the
 * product module by Spring: see {@link SupplierProbe} for why it is not called the other way round.
 * Active means what it means for a new purchase order ({@code SUPPLIER_INACTIVE} otherwise).
 */
@Component
class ProcurementSupplierProbe implements SupplierProbe {

    private final SupplierService suppliers;

    ProcurementSupplierProbe(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Standing> supplier(UUID supplierId) {
        return suppliers.findById(supplierId)
                .map(s -> new Standing(s.supplierId(), s.code(), SupplierStatus.ACTIVE.name().equals(s.status())));
    }
}
