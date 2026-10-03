package com.stockflow.procurement.api;

import com.stockflow.common.api.PageResponse;
import java.util.Optional;
import java.util.UUID;

public interface SupplierService {
    SupplierSummary create(SaveSupplierCommand command);
    SupplierSummary update(UUID supplierId, SaveSupplierCommand command);
    void deactivate(UUID supplierId);
    Optional<SupplierSummary> findById(UUID supplierId);
    PageResponse<SupplierSummary> list(int page, int size, String search, String status, String sort);
    SupplierPerformanceSummary performance(UUID supplierId);
}
