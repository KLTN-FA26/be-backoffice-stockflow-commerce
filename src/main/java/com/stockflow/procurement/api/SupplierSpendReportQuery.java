package com.stockflow.procurement.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Input to {@link ProcurementService#supplierSpend}. {@code supplierId}, {@code expectedAtFrom}
 * and {@code expectedAtTo} are all optional filters; a {@code supplierId} that does not name a
 * real supplier is rejected with {@code SUPPLIER_NOT_FOUND} rather than returning an empty page —
 * see the service method's own javadoc.
 */
public record SupplierSpendReportQuery(
        int page,
        int size,
        UUID supplierId,
        LocalDate expectedAtFrom,
        LocalDate expectedAtTo
) {
}
