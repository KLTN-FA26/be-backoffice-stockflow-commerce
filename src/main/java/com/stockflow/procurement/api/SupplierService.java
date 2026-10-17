package com.stockflow.procurement.api;

import com.stockflow.common.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

public interface SupplierService {
    SupplierSummary create(SaveSupplierCommand command);

    SupplierSummary update(UUID supplierId, SaveSupplierCommand command);

    void deactivate(UUID supplierId);

    Optional<SupplierSummary> findById(UUID supplierId);

    default PageResponse<SupplierSummary> list(
            int page, int size, String search, String status, String sort) {
        return list(page, size, search, status, null, sort);
    }

    /**
     * @param printSubcontractor only suppliers who print for us (BR-PRD-11: the ones a production order may be split
     *                           to) when true, only the others when false, both when null
     */
    PageResponse<SupplierSummary> list(
            int page, int size, String search, String status, Boolean printSubcontractor, String sort);

    SupplierPerformanceSummary performance(UUID supplierId);
}
