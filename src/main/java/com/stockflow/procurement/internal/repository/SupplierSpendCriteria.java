package com.stockflow.procurement.internal.repository;

import java.time.LocalDate;
import java.util.UUID;

/** Filter for {@link ProcurementReportRepository#supplierSpend}. Public: {@code internal.service}
 *  builds it, same reasoning as {@code PurchaseOrderSearchCriteria}. */
public record SupplierSpendCriteria(UUID supplierId, LocalDate expectedAtFrom, LocalDate expectedAtTo) {
}
