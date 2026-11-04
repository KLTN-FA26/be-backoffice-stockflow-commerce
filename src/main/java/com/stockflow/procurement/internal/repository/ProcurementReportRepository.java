package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.api.PurchaseOrderStatusCount;
import com.stockflow.procurement.api.SupplierSpendSummary;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * SCRUM-119/WBS 3.2.7 read-only reporting queries — deliberately not part of {@code
 * PurchaseOrderRepository}, same reasoning as {@code PurchaseOrderSearchRepository}: {@code Page}/
 * {@code Pageable} are banned from {@code internal.domain}. Implemented by the same adapter class
 * as the other two procurement repository interfaces.
 */
public interface ProcurementReportRepository {

    List<PurchaseOrderStatusCount> statusDashboard();

    Page<SupplierSpendSummary> supplierSpend(SupplierSpendCriteria criteria, Pageable pageable);
}
